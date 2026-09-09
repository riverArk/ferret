package io.riverark.ferret.core.cardano

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnsignedInteger
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.spec.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.InvalidRecoveryPhraseException
import io.riverark.ferret.core.security.AndroidRecoveryPhraseCodec
import io.riverark.ferret.core.network.ConnectorUtxoDto
import io.riverark.ferret.core.network.MAINNET
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class AndroidCardanoTransactionEngineTest {
    private val processor = object : TransactionProcessor {
        override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
        @Suppress("UNCHECKED_CAST")
        override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> =
            Result.success("fixture").withValue(Collections.emptyList<EvaluationResult>()) as Result<List<EvaluationResult>>
    }

    @Test fun derivesStableNetworkCorrectCip1852Identity() = runBlocking {
        val engine = AndroidCardanoTransactionEngine(processor)
        val entropy = ByteArray(32) { it.toByte() }
        val first = engine.deriveWallet(entropy, CardanoNetwork.PREPROD)
        val second = engine.deriveWallet(entropy, CardanoNetwork.PREPROD)
        val mainnet = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)

        assertEquals(first, second)
        assertTrue(first.paymentAddress.startsWith("addr_test1"))
        assertTrue(mainnet.paymentAddress.startsWith("addr1"))
        assertEquals(56, first.paymentCredentialHex.length)
        assertEquals(first.paymentCredentialHex, mainnet.paymentCredentialHex)
    }

    @Test fun restoresGeneratedRecoveryPhrase() = runBlocking {
        val words = "enemy mean dumb tail desert second lift barely minimum stove figure rack milk resource sand kiwi delay sand cupboard resource melt capable office card".split(" ")
        val entropy = AndroidRecoveryPhraseCodec().entropy(words)
        val wallet = deriveAndroidWallet(entropy, CardanoNetwork.PREPROD)

        assertEquals(words, AndroidRecoveryPhraseCodec().words(entropy))
        assertTrue(wallet.paymentAddress.startsWith("addr_test1"))
        entropy.fill(0)
    }

    @Test fun rejectsChecksumInvalidRecoveryPhrase() {
        val words = "abandon mean dumb tail desert second lift barely minimum stove figure rack milk resource sand kiwi delay sand cupboard resource melt capable office card".split(" ")
        assertFailsWith<InvalidRecoveryPhraseException> { AndroidRecoveryPhraseCodec().entropy(words) }
    }

    @Test fun transferBuildPreservesConfirmedSemantics() = runBlocking {
        val engine = AndroidCardanoTransactionEngine(processor)
        val source = engine.deriveWallet(ByteArray(32) { it.toByte() }, CardanoNetwork.PREPROD)
        val destination = engine.deriveWallet(ByteArray(32) { (it + 1).toByte() }, CardanoNetwork.PREPROD)
        val intent = CardanoIntent.Transfer(
            source.paymentAddress,
            destination.paymentAddress,
            Lovelace(5_000_000),
            "00000000-0000-0000-0000-000000000001",
            100,
            200,
        )
        val ledger = LedgerSnapshot(
            CardanoNetwork.PREPROD,
            listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))),
            PROTOCOL_PARAMETERS,
            100,
        )

        val unsigned = engine.build(intent, ledger)
        engine.inspect(unsigned.cbor).requireMatches(intent, CardanoNetwork.PREPROD, unsigned.feeBound)
    }

    @Test fun transferRejectsEveryProtectedOnlyFundingForm() = runBlocking<Unit> {
        val engine = AndroidCardanoTransactionEngine(processor)
        val source = engine.deriveWallet(ByteArray(32) { it.toByte() }, CardanoNetwork.MAINNET)
        val destination = engine.deriveWallet(ByteArray(32) { (it + 1).toByte() }, CardanoNetwork.MAINNET)
        val intent = CardanoIntent.Transfer(
            source.paymentAddress,
            destination.paymentAddress,
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000002",
            100,
            200,
        )
        val protected = listOf(
            Json.decodeFromString<ConnectorUtxoDto>(
                """{"transaction_id":"${"22".repeat(32)}","output_index":0,"address":"${source.paymentAddress}","value":[{"unit":"lovelace","quantity":"100000000"}],"datum_hash":"${"33".repeat(32)}"}""",
            ).ledger(),
            LedgerUtxo("33".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), datumHex = "d87980"),
            LedgerUtxo("44".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), scriptRefHashHex = "55".repeat(28)),
            LedgerUtxo("55".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), mapOf("66".repeat(28) to 0)),
        )

        protected.forEach { utxo ->
            assertFailsWith<Exception> {
                engine.build(intent, LedgerSnapshot(CardanoNetwork.MAINNET, listOf(utxo), PROTOCOL_PARAMETERS, 100))
            }
        }
        val plain = protected.first().copy(datumHashHex = null)
        val unsigned = engine.build(intent, LedgerSnapshot(CardanoNetwork.MAINNET, listOf(plain), PROTOCOL_PARAMETERS, 100))
        assertEquals(setOf(plain.transactionId to plain.index), Transaction.deserialize(unsigned.cbor).body.inputs.map { it.transactionId to it.index }.toSet())
        engine.inspect(unsigned.cbor).requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
    }

    @Test fun automaticWalletIntentsSpendOnlyEligibleInputs() = runBlocking {
        val engine = AndroidCardanoTransactionEngine(processor)
        val source = engine.deriveWallet(ByteArray(32) { it.toByte() }, CardanoNetwork.MAINNET)
        val destination = engine.deriveWallet(ByteArray(32) { (it + 1).toByte() }, CardanoNetwork.MAINNET)
        val eligible = LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))
        val ledger = LedgerSnapshot(
            CardanoNetwork.MAINNET,
            listOf(
                eligible,
                LedgerUtxo("11".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), datumHashHex = "aa".repeat(32)),
                LedgerUtxo("22".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), datumHex = "d87980"),
                LedgerUtxo("33".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), scriptRefHashHex = "bb".repeat(28)),
                LedgerUtxo("44".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), mapOf("cc".repeat(28) to 0)),
                LedgerUtxo("55".repeat(32), 0, destination.paymentAddress, Lovelace(100_000_000)),
            ),
            PROTOCOL_PARAMETERS,
            100,
        )
        val intents = listOf<CardanoIntent>(
            CardanoIntent.Transfer(source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000), "00000000-0000-4000-8000-000000000003", 100, 200),
            CardanoIntent.SweepWallet(source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000), "00000000-0000-4000-8000-000000000004", 100, 200),
        )

        intents.forEach { intent ->
            val unsigned = engine.build(intent, ledger)
            val summary = engine.inspect(unsigned.cbor)
            assertEquals(listOf(TransactionInputReference(eligible.transactionId, eligible.index)), summary.inputs)
            summary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
            if (intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet) {
                summary.requireL1Funding(intent, ledger)
            }
        }
    }

    @Test fun signingPreservesOriginalBodyHashAndBodyMutationsAreDetected() = runBlocking<Unit> {
        val engine = AndroidCardanoTransactionEngine(processor)
        val entropy = ByteArray(32) { it.toByte() }
        val source = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)
        val destination = engine.deriveWallet(ByteArray(32) { (it + 1).toByte() }, CardanoNetwork.MAINNET)
        val intent = CardanoIntent.Transfer(
            source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000001", 100, 200,
        )
        val eligible = LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))
        val protected = LedgerUtxo("11".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000), datumHashHex = "aa".repeat(32))
        val ledger = LedgerSnapshot(
            CardanoNetwork.MAINNET,
            listOf(eligible, protected),
            PROTOCOL_PARAMETERS,
            100,
        )
        val unsigned = engine.build(intent, ledger)
        val signed = try { engine.sign(unsigned, entropy) } finally { entropy.fill(0) }
        try {
            val summary = engine.inspect(unsigned.cbor)
            summary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
            summary.requireL1Funding(intent, ledger)
            val signedSummary = engine.inspect(signed.cbor)
            signedSummary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
            signedSummary.requireL1Funding(intent, ledger)
            assertEquals(summary.inputs, signedSummary.inputs)
            assertEquals(engine.transactionId(unsigned.cbor), engine.transactionId(signed.cbor))

            val changedInput = Transaction.deserialize(signed.cbor)
            changedInput.body.inputs = listOf(TransactionInput.builder().transactionId("22".repeat(32)).index(0).build())
            val changedBytes = changedInput.serialize()
            engine.inspect(changedBytes).requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
            assertFailsWith<IllegalArgumentException> { engine.inspect(changedBytes).requireL1Funding(intent, ledger) }
            assertNotEquals(engine.transactionId(unsigned.cbor), engine.transactionId(changedBytes))

            val protectedInput = Transaction.deserialize(unsigned.cbor)
            protectedInput.body.inputs = listOf(TransactionInput.builder().transactionId(protected.transactionId).index(protected.index).build())
            assertFailsWith<IllegalArgumentException> {
                engine.inspect(protectedInput.serialize()).requireL1Funding(intent, ledger)
            }

            val changedChange = Transaction.deserialize(unsigned.cbor)
            changedChange.body.outputs = changedChange.body.outputs.map { output ->
                if (output.address == source.paymentAddress) {
                    TransactionOutput.builder().address(output.address)
                        .value(Value(output.value.coin.add(BigInteger.ONE), emptyList())).build()
                } else {
                    output
                }
            }
            val changedChangeSummary = engine.inspect(changedChange.serialize())
            changedChangeSummary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
            assertFailsWith<IllegalArgumentException> { changedChangeSummary.requireL1Funding(intent, ledger) }

            val extraOutput = Transaction.deserialize(unsigned.cbor)
            extraOutput.body.outputs = extraOutput.body.outputs + TransactionOutput.builder()
                .address(destination.paymentAddress).value(Value(BigInteger.valueOf(1_000_000), emptyList())).build()
            assertFailsWith<IllegalArgumentException> {
                engine.inspect(extraOutput.serialize()).requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
            }
        } finally {
            signed.cbor.fill(0)
        }
    }
    @Test fun l1AuthorizationEnvelopeComesFromRawBytes() = runBlocking<Unit> {
        val engine = AndroidCardanoTransactionEngine(processor)
        val entropy = ByteArray(32) { it.toByte() }
        val otherEntropy = ByteArray(32) { (it + 2).toByte() }
        val destinationEntropy = ByteArray(32) { (it + 1).toByte() }
        val sensitive = mutableListOf<ByteArray>()
        try {
            val source = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)
            val destination = engine.deriveWallet(destinationEntropy, CardanoNetwork.MAINNET)
            val ledger = LedgerSnapshot(
                CardanoNetwork.MAINNET,
                listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))),
                PROTOCOL_PARAMETERS,
                100,
            )
            listOf<CardanoIntent>(
                CardanoIntent.Transfer(
                    source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000),
                    "00000000-0000-4000-8000-000000000005", 100, 200,
                ),
                CardanoIntent.SweepWallet(
                    source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000),
                    "00000000-0000-4000-8000-000000000006", 100, 200,
                ),
            ).forEach { intent ->
                val unsigned = engine.build(intent, ledger)
                val unsignedSummary = engine.inspect(unsigned.cbor)
                unsignedSummary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                unsignedSummary.requireL1Witnesses(source.paymentCredentialHex, signed = false)

                val signed = engine.sign(unsigned, entropy).cbor.also(sensitive::add)
                val signedSummary = engine.inspect(signed)
                signedSummary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                signedSummary.requireL1Witnesses(source.paymentCredentialHex, signed = true)
                val bodyId = engine.transactionId(signed)

                val wrongSigner = engine.sign(unsigned, otherEntropy).cbor.also(sensitive::add)
                val stripped = mutate(signed) { envelope ->
                    (envelope.dataItems[1] as CborMap).remove(UnsignedInteger(0))
                }.also(sensitive::add)
                val duplicate = mutate(signed) { envelope ->
                    val witnesses = (envelope.dataItems[1] as CborMap)[UnsignedInteger(0)] as CborArray
                    witnesses.add(witnesses.dataItems.single())
                }.also(sensitive::add)
                val extra = mutate(signed) { envelope ->
                    val witnesses = (envelope.dataItems[1] as CborMap)[UnsignedInteger(0)] as CborArray
                    val otherEnvelope = decode(wrongSigner)
                    val otherWitnesses = (otherEnvelope.dataItems[1] as CborMap)[UnsignedInteger(0)] as CborArray
                    witnesses.add(otherWitnesses.dataItems.single())
                }.also(sensitive::add)
                val corrupt = mutate(signed) { envelope ->
                    val witnesses = (envelope.dataItems[1] as CborMap)[UnsignedInteger(0)] as CborArray
                    val witness = witnesses.dataItems.single() as CborArray
                    val signature = (witness.dataItems[1] as ByteString).bytes.copyOf()
                    signature[0] = (signature[0].toInt() xor 1).toByte()
                    witness.dataItems[1] = ByteString(signature)
                }.also(sensitive::add)

                listOf(stripped, duplicate, extra, corrupt, wrongSigner).forEach { rejected ->
                    assertEquals(bodyId, engine.transactionId(rejected))
                    assertFailsWith<IllegalArgumentException> {
                        engine.inspect(rejected).requireL1Witnesses(source.paymentCredentialHex, signed = true)
                    }
                }

                val nonKeyWitness = mutate(signed) { envelope ->
                    (envelope.dataItems[1] as CborMap).put(UnsignedInteger(1), CborArray())
                }.also(sensitive::add)
                val nonKeySummary = engine.inspect(nonKeyWitness)
                assertTrue(nonKeySummary.containsNonKeyWitnesses)
                assertFailsWith<IllegalArgumentException> {
                    nonKeySummary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                }

                val datumOutput = mutate(unsigned.cbor) { envelope ->
                    val body = envelope.dataItems[0] as CborMap
                    val outputs = body[UnsignedInteger(1)] as CborArray
                    (outputs.dataItems.first() as CborArray).add(ByteString(ByteArray(32) { 1 }))
                }
                assertTrue(engine.inspect(datumOutput).outputs.any { it.datum is TransactionDatum.Hash })
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(datumOutput).requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                }

                val prohibited = mutate(unsigned.cbor) { envelope ->
                    (envelope.dataItems[0] as CborMap).put(UnsignedInteger(22), UnsignedInteger(1))
                }
                assertTrue(ProhibitedBodyField.DONATION in engine.inspect(prohibited).prohibitedBodyFields)
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(prohibited).requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                }
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(mutate(unsigned.cbor) { envelope ->
                        (envelope.dataItems[0] as CborMap).put(UnsignedInteger(23), UnsignedInteger(0))
                    })
                }
                val auxiliary = mutate(unsigned.cbor) { it.dataItems[3] = CborMap() }
                assertTrue(ProhibitedBodyField.AUXILIARY_DATA in engine.inspect(auxiliary).prohibitedBodyFields)
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(mutate(unsigned.cbor) { envelope ->
                        (envelope.dataItems[0] as CborMap).put(UnsignedInteger(15), UnsignedInteger(0))
                    })
                }
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(mutate(unsigned.cbor) { it.dataItems[2] = SimpleValue.FALSE })
                }
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(mutate(unsigned.cbor) { it.dataItems[2] = UnsignedInteger(1) })
                }
                assertFailsWith<IllegalArgumentException> {
                    engine.inspect(mutate(unsigned.cbor) { it.add(SimpleValue.NULL) })
                }
            }
        } finally {
            entropy.fill(0)
            otherEntropy.fill(0)
            destinationEntropy.fill(0)
            sensitive.forEach { it.fill(0) }
        }
    }

    private fun decode(cbor: ByteArray) =
        CborDecoder(ByteArrayInputStream(cbor)).decodeNext() as CborArray

    private fun mutate(cbor: ByteArray, mutation: (CborArray) -> Unit): ByteArray {
        val envelope = decode(cbor)
        mutation(envelope)
        return ByteArrayOutputStream().also { CborEncoder(it).encode(envelope) }.toByteArray()
    }


    companion object {
        private const val PROTOCOL_PARAMETERS = """{
          "min_fee_a":44,"min_fee_b":155381,"max_tx_size":16384,
          "key_deposit":"2000000","pool_deposit":"500000000","min_pool_cost":"170000000",
          "protocol_major_ver":10,"protocol_minor_ver":0,"coins_per_utxo_size":"4310",
          "collateral_percent":150,"max_collateral_inputs":3
        }"""
    }
}
