package io.riverark.ferret.core.cardano

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnsignedInteger
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Tag
import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.address.Address
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier
import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.api.helper.impl.FeeCalculationServiceImpl
import com.bloxbean.cardano.client.api.model.ProtocolParams
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.spec.Value
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.api.common.OrderEnum
import com.bloxbean.cardano.client.util.HexUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Optional
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import io.riverark.ferret.feature.wallet.DefaultL1WalletRepository
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.InvalidRecoveryPhraseException
import io.riverark.ferret.core.security.AndroidRecoveryPhraseCodec
import io.riverark.ferret.core.network.ConnectorUtxoDto
import io.riverark.ferret.core.network.ConnectorAssetDto
import io.riverark.ferret.core.network.MAINNET
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidCardanoTransactionEngineTest {
    private val processor = object : TransactionProcessor {
        override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
        @Suppress("UNCHECKED_CAST")
        override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> =
            Result.success("fixture").withValue(Collections.emptyList<EvaluationResult>()) as Result<List<EvaluationResult>>
    }
    private val channelVectors = Json.parseToJsonElement(
        requireNotNull(requireNotNull(javaClass.classLoader).getResource("konduit/channel-conformance.json")).readText(),
    ).jsonObject
    private val channelProtocolParameters = requireNotNull(channelVectors["protocol_parameters_fixture"]).toString()

    private fun fixture(name: String) = requireNotNull(channelVectors[name]).jsonPrimitive.content

    private fun protocolParams(json: String): ProtocolParams =
        ObjectMapper().readValue(json, ProtocolParams::class.java)

    private fun feeCalculator(params: ProtocolParams) = FeeCalculationServiceImpl(
        object : UtxoSupplier {
            override fun getPage(address: String, count: Int?, page: Int?, order: OrderEnum?) = emptyList<Utxo>()
            override fun getTxOutput(hash: String, index: Int) = Optional.empty<Utxo>()
        },
        ProtocolParamsSupplier { params },
    )

    private fun channelDatum(stage: ChannelDatumStage, addVerificationKeyHex: String = "02".repeat(32)) = ChannelDatum(
        fixture("validator_hash"),
        ChannelConstants("01".repeat(32), addVerificationKeyHex, "03".repeat(32), 1_800_000),
        stage,
    )

    private fun walletAddKey(entropy: ByteArray) = HexUtil.encodeHexString(
        Account.createFromMnemonic(
            Networks.mainnet(),
            MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" "),
        ).publicKeyBytes(),
    )

    private fun fixtureReference() = ConnectorUtxoDto(
        "11".repeat(32),
        0,
        MAINNET.scriptDeploymentAddress,
        listOf(ConnectorAssetDto("lovelace", "2000000")),
        referenceScriptHash = fixture("validator_hash"),
        referenceScriptVersion = 3,
        referenceScript = fixture("reference_script"),
    ).ledger()

    private fun exactEvaluation(cbor: ByteArray, memory: BigInteger = BigInteger.valueOf(10_000), steps: BigInteger = BigInteger.valueOf(10_000_000)) =
        Transaction.deserialize(cbor).witnessSet?.redeemers.orEmpty().map { redeemer ->
            EvaluationResult.builder()
                .redeemerTag(redeemer.tag)
                .index(redeemer.index.intValueExact())
                .exUnits(ExUnits.builder().mem(memory).steps(steps).build())
                .build()
        }

    private fun processor(evaluate: (ByteArray) -> Result<List<EvaluationResult>>) = object : TransactionProcessor {
        override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
        override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>) = evaluate(cbor)
    }

    @Test fun channelCodecMatchesPinnedKonduitVectors() {
        val datums = mapOf(
            "datum_opened_empty" to channelDatum(ChannelDatumStage.Opened(0)),
            "datum_opened_used" to channelDatum(ChannelDatumStage.Opened(123, listOf(USED_EVIDENCE))),
            "datum_closed_used" to channelDatum(ChannelDatumStage.Closed(123, listOf(USED_EVIDENCE), 1_700_000_000_000)),
            "datum_responded_pending" to channelDatum(ChannelDatumStage.Responded(123, listOf(PENDING_EVIDENCE))),
        )
        datums.forEach { (name, datum) -> assertEquals(fixture(name), datum.plutus().serializeToHex()) }
        ChannelRedeemer.entries.forEach { redeemer ->
            assertEquals(fixture("redeemer_${redeemer.name.lowercase()}"), redeemer.plutus().serializeToHex())
        }
    }

    @Test fun channelCodecRejectsInvalidEvidence() {
        listOf(
            ChannelDatumStage.Opened(0, listOf("00")),
            ChannelDatumStage.Opened(0, listOf(USED_EVIDENCE + "00")),
            ChannelDatumStage.Opened(0, listOf("d87980")),
            ChannelDatumStage.Opened(0, listOf("9f2000ff")),
            ChannelDatumStage.Opened(0, listOf("9f1b800000000000000000ff")),
            ChannelDatumStage.Opened(0, listOf(PENDING_EVIDENCE)),
            ChannelDatumStage.Responded(0, listOf(USED_EVIDENCE)),
            ChannelDatumStage.Opened(0, List(11) { USED_EVIDENCE }),
        ).forEach { stage ->
            assertEquals(
                "invalid channel evidence",
                assertFailsWith<IllegalArgumentException> { channelDatum(stage).plutus() }.message,
            )
        }
        assertEquals(
            "invalid channel timestamp",
            assertFailsWith<IllegalArgumentException> {
                channelDatum(ChannelDatumStage.Closed(0, emptyList(), -1)).plutus()
            }.message,
        )
    }

    @Test fun channelReferenceRequiresRawPinnedV3Script() {
        val reference = fixtureReference()
        val script = reference.requireChannelReferenceScript(fixture("validator_hash"))
        assertEquals(fixture("validator_hash"), script.policyId)
        listOf(
            reference.copy(scriptRefHex = reference.scriptRefHex!!.replaceRange(0, 2, "00")),
            reference.copy(scriptRefHashHex = "00".repeat(28)),
            reference.copy(scriptRefVersion = 2),
            reference.copy(scriptRefHex = HexUtil.encodeHexString(script.scriptRefBytes())),
            reference.copy(scriptRefHex = ""),
            reference.copy(scriptRefHex = null, scriptRefVersion = null, scriptRefHashHex = null),
        ).forEach { invalid ->
            assertEquals(
                "invalid channel reference script",
                assertFailsWith<IllegalArgumentException> {
                    invalid.requireChannelReferenceScript(fixture("validator_hash"))
                }.message,
            )
        }
    }

    @Test fun channelExecutionPricesProduceExactScriptFee() {
        val params = protocolParams(channelProtocolParameters)
        val exUnits = ExUnits.builder()
            .mem(BigInteger.valueOf(10_000))
            .steps(BigInteger.valueOf(10_000_000))
            .build()
        assertEquals(BigInteger.valueOf(1_298), feeCalculator(params).calculateScriptFee(listOf(exUnits), params))
    }

    @Test fun channelEvaluationRequiresExactCoverageAndChecksFinalBytes() = runBlocking {
        val requests = mutableListOf<ByteArray>()
        val engine = AndroidCardanoTransactionEngine(processor { cbor ->
            requests += cbor.copyOf()
            @Suppress("UNCHECKED_CAST")
            (Result.success("fixture").withValue(exactEvaluation(cbor)) as Result<List<EvaluationResult>>)
        })
        val entropy = ByteArray(32) { it.toByte() }
        val source = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)
        val current = channelDatum(ChannelDatumStage.Opened(0), walletAddKey(entropy))
        val reference = fixtureReference()
        val channelInput = LedgerUtxo(
            "22".repeat(32),
            0,
            MAINNET.validatorAddress,
            Lovelace(5_000_000),
            datumHex = current.plutus().serializeToHex(),
        )
        val intent = CardanoIntent.AddChannelFunds(
            source.paymentAddress,
            channelInput,
            reference,
            current,
            current,
            Lovelace(1_000_000),
            "00000000-0000-4000-8000-000000000018",
            4_492_800,
            4_492_900,
        )
        val ledger = LedgerSnapshot(
            CardanoNetwork.MAINNET,
            listOf(
                LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000)),
                reference,
                channelInput,
            ),
            channelProtocolParameters,
            4_492_800,
        )

        val unsigned = engine.build(intent, ledger)
        assertTrue(requests.size >= 2)
        assertContentEquals(unsigned.cbor, requests.last())
        var changingCalls = 0
        assertEquals(
            "channel evaluation budget changed",
            assertFailsWith<IllegalArgumentException> {
                AndroidCardanoTransactionEngine(processor { cbor ->
                    changingCalls++
                    val memory = if (changingCalls == requests.size) BigInteger.valueOf(10_001) else BigInteger.valueOf(10_000)
                    @Suppress("UNCHECKED_CAST")
                    (Result.success("fixture").withValue(exactEvaluation(cbor, memory)) as Result<List<EvaluationResult>>)
                }).build(intent, ledger)
            }.message,
        )

        suspend fun reject(response: (ByteArray) -> Result<List<EvaluationResult>>) {
            assertFailsWith<IllegalArgumentException> {
                AndroidCardanoTransactionEngine(processor(response)).build(intent, ledger)
            }
        }
        fun success(values: List<EvaluationResult>): Result<List<EvaluationResult>> {
            @Suppress("UNCHECKED_CAST")
            return Result.success("fixture").withValue(values) as Result<List<EvaluationResult>>
        }
        reject { success(emptyList()) }
        reject { cbor -> success(exactEvaluation(cbor) + exactEvaluation(cbor)) }
        reject { cbor ->
            val row = exactEvaluation(cbor).single()
            success(listOf(EvaluationResult.builder().redeemerTag(row.redeemerTag).index(row.index + 1).exUnits(row.exUnits).build()))
        }
        reject { cbor ->
            val row = exactEvaluation(cbor).single()
            success(listOf(EvaluationResult.builder().redeemerTag(RedeemerTag.Mint).index(row.index).exUnits(row.exUnits).build()))
        }
        reject { cbor ->
            val row = exactEvaluation(cbor).single()
            success(listOf(EvaluationResult.builder().redeemerTag(row.redeemerTag).index(row.index).exUnits(ExUnits.builder().mem(BigInteger.valueOf(-1)).steps(BigInteger.ZERO).build()).build()))
        }

        reject { cbor ->
            val row = exactEvaluation(cbor).single()
            success(listOf(EvaluationResult.builder().redeemerTag(null).index(row.index).exUnits(row.exUnits).build()))
        }
        reject { cbor ->
            val row = exactEvaluation(cbor).single()
            success(listOf(EvaluationResult.builder().redeemerTag(row.redeemerTag).index(row.index).exUnits(null).build()))
        }
        reject { cbor -> success(exactEvaluation(cbor, BigInteger("16500001"), BigInteger.ZERO)) }
        @Suppress("UNCHECKED_CAST")
        reject { Result.error("node failure") as Result<List<EvaluationResult>> }
        reject { error("node failure") }
    }
    @Test fun channelSemanticsBindWalletLedgerStagesAndSlotTimes() = runBlocking {
        val entropy = ByteArray(32) { it.toByte() }
        val engine = AndroidCardanoTransactionEngine(processor { cbor ->
            @Suppress("UNCHECKED_CAST")
            (Result.success("fixture").withValue(exactEvaluation(cbor)) as Result<List<EvaluationResult>>)
        })
        val source = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)
        val constants = ChannelConstants("01".repeat(32), walletAddKey(entropy), "03".repeat(32), 1_800_000)
        fun datum(stage: ChannelDatumStage) = ChannelDatum(fixture("validator_hash"), constants, stage)
        val reference = fixtureReference()
        fun channelInput(stage: ChannelDatumStage, lovelace: Long = 5_000_000) = LedgerUtxo(
            "22".repeat(32),
            0,
            MAINNET.validatorAddress,
            Lovelace(lovelace),
            datumHex = datum(stage).plutus().serializeToHex(),
        )
        val fuel = LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))
        fun ledger(channel: LedgerUtxo, utxos: List<LedgerUtxo> = listOf(fuel, reference, channel), slot: Long = 4_492_800) =
            LedgerSnapshot(CardanoNetwork.MAINNET, utxos, channelProtocolParameters, slot)
        suspend fun buildAndSign(intent: CardanoIntent, snapshot: LedgerSnapshot): UnsignedTransaction {
            val unsigned = engine.build(intent, snapshot)
            val signed = engine.sign(unsigned, entropy, intent, snapshot)
            try {
                val summary = engine.inspect(signed.cbor)
                summary.requireL1Witnesses(source.paymentCredentialHex, signed = true)
                assertEquals(engine.transactionId(unsigned.cbor), engine.transactionId(signed.cbor))
            } finally {
                signed.cbor.fill(0)
            }
            return unsigned
        }

        val opened = ChannelDatumStage.Opened(0)
        val input = channelInput(opened)
        val add = CardanoIntent.AddChannelFunds(
            source.paymentAddress, input, reference, datum(opened), datum(opened), Lovelace(1_000_000),
            "00000000-0000-4000-8000-000000000030", 4_492_800, 4_492_900,
        )
        buildAndSign(add, ledger(input))
        assertFailsWith<IllegalArgumentException> {
            engine.build(add.copy(currentDatum = datum(opened).copy(constants = constants.copy(addVerificationKeyHex = "02".repeat(32)))), ledger(input))
        }
        assertFailsWith<IllegalArgumentException> { engine.build(add, ledger(input, listOf(fuel, reference))) }
        assertFailsWith<IllegalArgumentException> {
            engine.build(add, ledger(input, listOf(fuel, reference, input.copy(lovelace = Lovelace(5_000_001)))))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.build(add.copy(resultingDatum = datum(ChannelDatumStage.Opened(1))), ledger(input))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.build(add.copy(sourceAddress = MAINNET.validatorAddress), ledger(input))
        }

        val closeDeadline = 1_596_060_991_000L
        val closed = ChannelDatumStage.Closed(0, emptyList(), closeDeadline)
        val close = CardanoIntent.CloseChannel(
            source.paymentAddress, input, reference, datum(opened), CloseChannelStep.CLOSE, datum(closed),
            input.lovelace, "00000000-0000-4000-8000-000000000031", 4_492_800, 4_492_900,
        )
        buildAndSign(close, ledger(input))
        assertFailsWith<IllegalArgumentException> {
            engine.build(close.copy(resultingDatum = datum(closed.copy(elapseAtEpochMillis = closeDeadline - 1))), ledger(input))
        }

        val elapsedInput = channelInput(closed)
        val elapse = CardanoIntent.CloseChannel(
            source.paymentAddress, elapsedInput, reference, datum(closed), CloseChannelStep.ELAPSE, null,
            elapsedInput.lovelace, "00000000-0000-4000-8000-000000000032", 4_494_700, 4_494_800,
        )
        buildAndSign(elapse, ledger(elapsedInput, slot = 4_494_700))
        assertFailsWith<IllegalArgumentException> {
            val late = closed.copy(elapseAtEpochMillis = closeDeadline + 1)
            val lateInput = channelInput(late)
            engine.build(elapse.copy(channelInput = lateInput, currentDatum = datum(late)), ledger(lateInput, slot = 4_494_700))
        }

        val endAt = 1_596_060_991_000L
        val pending = ListPlutusData.of(
            BigIntPlutusData.of(1),
            BigIntPlutusData.of(endAt),
            BytesPlutusData.of(ByteArray(32) { 4 }),
        ).serializeToHex()
        val responded = ChannelDatumStage.Responded(0, listOf(pending))
        val respondedInput = channelInput(responded)
        val end = CardanoIntent.CloseChannel(
            source.paymentAddress, respondedInput, reference, datum(responded), CloseChannelStep.END, null,
            respondedInput.lovelace, "00000000-0000-4000-8000-000000000033", 4_494_700, 4_494_800,
        )
        buildAndSign(end, ledger(respondedInput, slot = 4_494_700))
        val latePending = ListPlutusData.of(
            BigIntPlutusData.of(1),
            BigIntPlutusData.of(endAt + 1),
            BytesPlutusData.of(ByteArray(32) { 4 }),
        ).serializeToHex()
        val lateResponded = ChannelDatumStage.Responded(0, listOf(pending, latePending))
        val lateInput = channelInput(lateResponded)
        assertFailsWith<IllegalArgumentException> {
            engine.build(end.copy(channelInput = lateInput, currentDatum = datum(lateResponded)), ledger(lateInput, slot = 4_494_700))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.build(add.copy(validFrom = Long.MAX_VALUE - 1, validUntil = Long.MAX_VALUE), ledger(input, slot = Long.MAX_VALUE - 1))
        }
        entropy.fill(0)
        Unit
    }

    @Test fun channelAuthorizationRejectsTransactionMutationsBeforeSigning() = runBlocking<Unit> {
        val entropy = ByteArray(32) { it.toByte() }
        val otherEntropy = ByteArray(32) { (it + 1).toByte() }
        val engine = AndroidCardanoTransactionEngine(processor { cbor ->
            @Suppress("UNCHECKED_CAST")
            (Result.success("fixture").withValue(exactEvaluation(cbor)) as Result<List<EvaluationResult>>)
        })
        try {
            val source = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)
            val datum = channelDatum(ChannelDatumStage.Opened(0), walletAddKey(entropy))
            val reference = fixtureReference()
            val channel = LedgerUtxo(
                "22".repeat(32),
                0,
                MAINNET.validatorAddress,
                Lovelace(5_000_000),
                datumHex = datum.plutus().serializeToHex(),
            )
            val fuel = LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))
            val ledger = LedgerSnapshot(
                CardanoNetwork.MAINNET,
                listOf(fuel, reference, channel),
                channelProtocolParameters,
                4_492_800,
            )
            val intent = CardanoIntent.AddChannelFunds(
                source.paymentAddress, channel, reference, datum, datum, Lovelace(1_000_000),
                "00000000-0000-4000-8000-000000000034", 4_492_800, 4_492_900,
            )
            val unsigned = engine.build(intent, ledger)
            engine.requireAuthorized(unsigned, intent, ledger)
            val unsignedSummary = engine.inspect(unsigned.cbor)
            assertEquals(1L, unsignedSummary.redeemers.single().index)
            assertEquals(setOf(source.paymentCredentialHex), unsignedSummary.requiredSigners)
            assertTrue(unsignedSummary.keyWitnesses.isEmpty())
            unsignedSummary.requireChannelFunding(intent, ledger)

            val signed = engine.sign(unsigned, entropy, intent, ledger)
            try {
                val signedSummary = engine.inspect(signed.cbor)
                assertEquals(unsignedSummary.inputs, signedSummary.inputs)
                assertEquals(unsignedSummary.referenceInputs, signedSummary.referenceInputs)
                assertEquals(unsignedSummary.redeemers, signedSummary.redeemers)
                signedSummary.requireL1Witnesses(source.paymentCredentialHex, signed = true)
                assertEquals(engine.transactionId(unsigned.cbor), engine.transactionId(signed.cbor))
            } finally {
                signed.cbor.fill(0)
            }
            assertFailsWith<IllegalArgumentException> { engine.sign(unsigned, otherEntropy, intent, ledger) }
            assertFailsWith<IllegalArgumentException> {
                engine.requireAuthorized(unsigned, intent, ledger.copy(currentSlot = intent.validUntil))
            }

            fun reject(bytes: ByteArray, changedIntent: CardanoIntent = intent, changedLedger: LedgerSnapshot = ledger) {
                assertFailsWith<IllegalArgumentException> {
                    engine.requireAuthorized(UnsignedTransaction(bytes, unsigned.operationId, unsigned.feeBound), changedIntent, changedLedger)
                }
            }
            reject(unsigned.cbor, intent.copy(amount = Lovelace(2_000_000)))
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.body.requiredSigners = listOf(ByteArray(28))
            }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.witnessSet.redeemers.single().setIndex(0)
            }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.witnessSet.redeemers.single().data = ChannelRedeemer.CLOSE.plutus()
            }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.body.referenceInputs = listOf(TransactionInput.builder().transactionId("33".repeat(32)).index(0).build())
            }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also { it.body.collateral = emptyList() }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.body.outputs.first { output -> output.address == MAINNET.validatorAddress }.value.coin =
                    it.body.outputs.first { output -> output.address == MAINNET.validatorAddress }.value.coin.add(BigInteger.ONE)
            }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.body.totalCollateral = it.body.totalCollateral.add(BigInteger.ONE)
            }.serialize())
            reject(Transaction.deserialize(unsigned.cbor).also {
                it.body.scriptDataHash = it.body.scriptDataHash.copyOf().also { hash -> hash[0] = (hash[0].toInt() xor 1).toByte() }
            }.serialize())
            reject(mutate(unsigned.cbor) { envelope ->
                (envelope.dataItems[1] as CborMap).put(UnsignedInteger(1), CborArray())
            })
            reject(mutate(unsigned.cbor) { envelope ->
                (envelope.dataItems[0] as CborMap).put(UnsignedInteger(9), CborMap())
            })
            reject(unsigned.cbor + byteArrayOf(0))
        } finally {
            entropy.fill(0)
            otherEntropy.fill(0)
        }
    }

    @Test fun channelSpendRejectsSameReferenceIdentityBeforeEvaluation() = runBlocking {
        var evaluations = 0
        val countingProcessor = object : TransactionProcessor {
            override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
            override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> {
                evaluations++
                error("evaluation must not run")
            }
        }
        val engine = AndroidCardanoTransactionEngine(countingProcessor)
        val source = engine.deriveWallet(ByteArray(32) { it.toByte() }, CardanoNetwork.MAINNET)
        val current = channelDatum(ChannelDatumStage.Opened(0))
        val reference = fixtureReference()
        val channelInput = LedgerUtxo(
            reference.transactionId,
            reference.index,
            MAINNET.validatorAddress,
            Lovelace(5_000_000),
            datumHex = current.plutus().serializeToHex(),
        )
        val intent = CardanoIntent.AddChannelFunds(
            source.paymentAddress,
            channelInput,
            reference,
            current,
            current,
            Lovelace(1_000_000),
            "00000000-0000-4000-8000-000000000019",
            100,
            200,
        )

        assertFailsWith<IllegalArgumentException> {
            engine.build(
                intent,
                LedgerSnapshot(
                    CardanoNetwork.MAINNET,
                    listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))),
                    PROTOCOL_PARAMETERS,
                    100,
                ),
            )
        }
        assertEquals(0, evaluations)
    }

    @Test fun channelProtocolParametersFailClosedBeforeEvaluation() = runBlocking {
        var evaluations = 0
        val engine = AndroidCardanoTransactionEngine(object : TransactionProcessor {
            override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
            override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> {
                evaluations++
                error("evaluation must not run")
            }
        })
        val source = engine.deriveWallet(ByteArray(32) { it.toByte() }, CardanoNetwork.MAINNET)
        val reference = fixtureReference()
        val opened = channelDatum(ChannelDatumStage.Opened(0))
        val responded = channelDatum(ChannelDatumStage.Responded(0, listOf(PENDING_EVIDENCE)))
        fun channelInput(transactionId: String, datum: ChannelDatum) = LedgerUtxo(
            transactionId,
            0,
            MAINNET.validatorAddress,
            Lovelace(5_000_000),
            datumHex = datum.plutus().serializeToHex(),
        )
        val open = CardanoIntent.OpenChannel(
            source.paymentAddress,
            MAINNET.validatorAddress,
            reference,
            opened,
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000020",
            100,
            200,
        )
        val add = CardanoIntent.AddChannelFunds(
            source.paymentAddress,
            channelInput("22".repeat(32), opened),
            reference,
            opened,
            opened,
            Lovelace(1_000_000),
            "00000000-0000-4000-8000-000000000021",
            100,
            200,
        )
        val close = CardanoIntent.CloseChannel(
            source.paymentAddress,
            channelInput("33".repeat(32), responded),
            reference,
            responded,
            CloseChannelStep.END,
            null,
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000022",
            100,
            200,
        )
        val parameters = requireNotNull(channelVectors["protocol_parameters_fixture"]).jsonObject
        suspend fun reject(intent: CardanoIntent, protocolParameters: String) {
            var unsigned: UnsignedTransaction? = null
            val error = assertFailsWith<IllegalArgumentException> {
                unsigned = engine.build(
                    intent,
                    LedgerSnapshot(
                        CardanoNetwork.MAINNET,
                        listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))),
                        protocolParameters,
                        100,
                    ),
                )
            }
            assertEquals("invalid channel protocol parameters", error.message)
            assertNull(unsigned)
            assertEquals(0, evaluations)
        }

        listOf(
            "price_mem",
            "price_step",
            "min_fee_ref_script_cost_per_byte",
            "max_tx_ex_mem",
            "max_tx_ex_steps",
            "cost_models_raw",
        ).forEach { required ->
            reject(open, JsonObject(parameters - required).toString())
        }
        val v3 = requireNotNull(parameters["cost_models_raw"]).jsonObject.getValue("PlutusV3")
        listOf(
            JsonObject(parameters + ("price_mem" to JsonPrimitive("-0.1"))),
            JsonObject(parameters + ("max_tx_ex_mem" to JsonPrimitive("9223372036854775808"))),
            JsonObject(parameters + ("max_tx_ex_steps" to JsonPrimitive("01"))),
            JsonObject(parameters + ("cost_models_raw" to JsonObject(emptyMap()))),
            JsonObject(parameters + ("cost_models_raw" to JsonArray(emptyList()))),
            JsonObject(parameters + ("cost_models_raw" to JsonObject(mapOf(
                "PlutusV3" to v3,
                "PlutusV2" to JsonArray(emptyList()),
            )))),
            JsonObject(parameters + ("cost_models_raw" to JsonObject(mapOf(
                "PlutusV3" to JsonArray(listOf(JsonPrimitive(BigInteger("9223372036854775808")))),
            )))),
            JsonObject(parameters + ("cost_models_raw" to JsonObject(mapOf(
                "PlutusV3" to JsonArray(List(1025) { JsonPrimitive(0) }),
            )))),
        ).forEach { malformed -> reject(open, malformed.toString()) }
        reject(add, PROTOCOL_PARAMETERS)
        reject(close, PROTOCOL_PARAMETERS)
    }

    @Test fun openChannelBuildAndSignPreservePinnedKonduitSemantics() = runBlocking {
        val engine = AndroidCardanoTransactionEngine(processor)
        val entropy = ByteArray(32) { it.toByte() }
        try {
            assertEquals(PINNED_KONDUIT_COMMIT, fixture("source_commit"))
            val source = engine.deriveWallet(entropy, CardanoNetwork.MAINNET)
            val reference = fixtureReference()
            val datum = channelDatum(ChannelDatumStage.Opened(0), walletAddKey(entropy))
            val intent = CardanoIntent.OpenChannel(
                source.paymentAddress,
                MAINNET.validatorAddress,
                reference,
                datum,
                Lovelace(5_000_000),
                "00000000-0000-4000-8000-000000000018",
                4_492_800,
                4_492_900,
            )
            val ledger = LedgerSnapshot(
                CardanoNetwork.MAINNET,
                listOf(
                    LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000)),
                    reference,
                ),
                channelProtocolParameters,
                4_492_800,
            )

            val unsigned = engine.build(intent, ledger)
            val unsignedSummary = engine.inspect(unsigned.cbor)
            engine.requireAuthorized(unsigned, intent, ledger)
            unsignedSummary.requireL1Witnesses(source.paymentCredentialHex, signed = false)
            val channelOutput = unsignedSummary.outputs.single { it.address == MAINNET.validatorAddress }
            assertEquals(Lovelace(5_000_000), channelOutput.lovelace)
            assertTrue(channelOutput.assets.isEmpty())
            assertEquals(TransactionDatum.Inline(datum.plutus().serializeToHex()), channelOutput.datum)
            val expectedReference = TransactionInputReference(reference.transactionId, reference.index)
            assertEquals(listOf(expectedReference), unsignedSummary.referenceInputs)
            assertTrue(expectedReference !in unsignedSummary.inputs)
            val inputs = ledger.utxos.associateBy { TransactionInputReference(it.transactionId, it.index) }
            val inputTotal = unsignedSummary.inputs.sumOf { requireNotNull(inputs[it]).lovelace.value }
            val outputAndFeeTotal = unsignedSummary.outputs.sumOf { it.lovelace.value } + unsignedSummary.fee.value
            assertEquals(inputTotal, outputAndFeeTotal)
            engine.requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)

            val signed = engine.sign(unsigned, entropy, intent, ledger)
            try {
                val signedSummary = engine.inspect(signed.cbor)
                assertEquals(engine.transactionId(unsigned.cbor), engine.transactionId(signed.cbor))
                assertEquals(unsignedSummary.inputs, signedSummary.inputs)
                assertEquals(unsignedSummary.referenceInputs, signedSummary.referenceInputs)
                assertEquals(unsignedSummary.outputs, signedSummary.outputs)
                signedSummary.requireL1Witnesses(source.paymentCredentialHex, signed = true)
                engine.requireMinimumAda(signed.cbor, ledger.protocolParametersJson)

                val highParams = protocolParams(channelProtocolParameters)
                val calculator = feeCalculator(highParams)
                val referenceScriptBytes = reference
                    .requireChannelReferenceScript(fixture("validator_hash"))
                    .scriptRefBytes()
                    .size
                    .toLong()
                val requiredFee = calculator.calculateFee(signed.cbor, highParams)
                    .add(calculator.tierRefScriptFee(referenceScriptBytes))
                assertTrue(BigInteger.valueOf(signedSummary.fee.value) >= requiredFee)

                val zeroReferencePrice = JsonObject(
                    Json.parseToJsonElement(channelProtocolParameters).jsonObject +
                        ("min_fee_ref_script_cost_per_byte" to JsonPrimitive("0")),
                ).toString()
                val zeroLedger = ledger.copy(protocolParametersJson = zeroReferencePrice)
                val zeroUnsigned = engine.build(intent, zeroLedger)
                val zeroSigned = engine.sign(zeroUnsigned, entropy, intent, zeroLedger)
                try {
                    val zeroSummary = engine.inspect(zeroSigned.cbor)
                    assertTrue(signedSummary.fee.value > zeroSummary.fee.value)
                    val zeroParams = protocolParams(zeroReferencePrice)
                    val zeroCalculator = feeCalculator(zeroParams)
                    val zeroRequiredFee = zeroCalculator.calculateFee(zeroSigned.cbor, zeroParams)
                        .add(zeroCalculator.tierRefScriptFee(referenceScriptBytes))
                    assertTrue(BigInteger.valueOf(zeroSummary.fee.value) >= zeroRequiredFee)
                    assertEquals(
                        requiredFee.subtract(zeroRequiredFee).longValueExact(),
                        signedSummary.fee.value - zeroSummary.fee.value,
                    )
                    assertEquals(
                        zeroSummary.outputs.filterNot { it.address == source.paymentAddress },
                        signedSummary.outputs.filterNot { it.address == source.paymentAddress },
                    )
                    val zeroChange = zeroSummary.outputs.single { it.address == source.paymentAddress }
                    val highChange = signedSummary.outputs.single { it.address == source.paymentAddress }
                    assertEquals(zeroChange.assets, highChange.assets)
                    assertEquals(zeroChange.datum, highChange.datum)
                    assertEquals(
                        zeroChange.lovelace.value - (signedSummary.fee.value - zeroSummary.fee.value),
                        highChange.lovelace.value,
                    )
                } finally {
                    zeroSigned.cbor.fill(0)
                }
            } finally {
                signed.cbor.fill(0)
            }
        } finally {
            entropy.fill(0)
        }
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
        val signed = try { engine.sign(unsigned, entropy, intent, ledger) } finally { entropy.fill(0) }
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

                val signed = engine.sign(unsigned, entropy, intent, ledger).cbor.also(sensitive::add)
                val signedSummary = engine.inspect(signed)
                signedSummary.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                signedSummary.requireL1Witnesses(source.paymentCredentialHex, signed = true)
                val bodyId = engine.transactionId(signed)

                assertFailsWith<IllegalArgumentException> {
                    engine.sign(unsigned, otherEntropy, intent, ledger)
                }
                val wrongSigner = Account.createFromMnemonic(
                    Networks.mainnet(),
                    MnemonicCode.INSTANCE.toMnemonic(otherEntropy).joinToString(" "),
                ).sign(Transaction.deserialize(unsigned.cbor)).serialize().also(sensitive::add)
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

    @Test fun enforcesPinnedKonduitOutputMinimumsWithoutNormalization() {
        val engine = AndroidCardanoTransactionEngine(processor)
        // a68cfedd4a0188ef9adad970e89c12b2b805b678:packages/cardano/sdk/src/cardano/output.rs
        val vectors = listOf(
            OutputMinimumVector(ENTERPRISE_ADDRESS, true, 39, 857_690),
            OutputMinimumVector(BASE_ADDRESS, true, 67, 978_370),
            OutputMinimumVector(ENTERPRISE_ADDRESS, false, 37, 849_070),
            OutputMinimumVector(BASE_ADDRESS, false, 65, 969_750),
        )
        vectors.forEach { vector ->
            val exactOutput = output(vector.address, vector.minimum, vector.postAlonzo)
            assertEquals(vector.encodedSize, CborSerializationUtil.serialize(exactOutput).size)
            val exact = transaction(listOf(exactOutput))
            val original = exact.copyOf()
            engine.requireMinimumAda(exact, protocolParameters("4310"))
            assertContentEquals(original, exact)
            assertFailsWith<IllegalArgumentException> {
                engine.requireMinimumAda(
                    transaction(listOf(output(vector.address, vector.minimum - 1, vector.postAlonzo))),
                    protocolParameters("4310"),
                )
            }

            engine.requireMinimumAda(
                transaction(listOf(output(vector.address, vector.minimum * 2, vector.postAlonzo))),
                protocolParameters("8620"),
            )
            assertFailsWith<IllegalArgumentException> {
                engine.requireMinimumAda(
                    transaction(listOf(output(vector.address, vector.minimum * 2 - 1, vector.postAlonzo))),
                    protocolParameters("8620"),
                )
            }
        }

        val lowCost = output(ENTERPRISE_ADDRESS, 194, false)
        assertEquals(34, CborSerializationUtil.serialize(lowCost).size)
        engine.requireMinimumAda(transaction(listOf(lowCost)), protocolParameters("1"))
        assertFailsWith<IllegalArgumentException> {
            engine.requireMinimumAda(
                transaction(listOf(output(ENTERPRISE_ADDRESS, 193, false))),
                protocolParameters("1"),
            )
        }
    }

    @Test fun observesExactOutputMinimumAndStrictChannelDatums() {
        val engine = AndroidCardanoTransactionEngine(processor)
        val first = output(ENTERPRISE_ADDRESS, 1_000_000, false)
        val second = output(BASE_ADDRESS, 2_000_000, true)
        val cbor = transaction(listOf(first, second))
        assertEquals(
            Lovelace((160L + CborSerializationUtil.serialize(second).size) * 4_310L),
            engine.minimumAdaForOutput(cbor, protocolParameters("4310"), 1),
        )
        assertFailsWith<IllegalArgumentException> {
            engine.minimumAdaForOutput(cbor, protocolParameters("4310"), 2)
        }

        listOf(
            ChannelDatumStage.Opened(0),
            ChannelDatumStage.Closed(1, elapseAtEpochMillis = 2),
            ChannelDatumStage.Responded(3),
        ).forEach { stage ->
            val expected = channelDatum(stage)
            val encoded = expected.plutus().serializeToHex()
            assertEquals(expected, engine.decodeChannelDatum(encoded))
            assertFailsWith<IllegalArgumentException> { engine.decodeChannelDatum(encoded + "00") }
            assertFailsWith<IllegalArgumentException> { engine.decodeChannelDatum(encoded.uppercase()) }
        }
    }

    @Test fun rejectsInvalidProtocolOutputCostsAndOverflow() {
        val engine = AndroidCardanoTransactionEngine(processor)
        val valid = transaction(listOf(output(ENTERPRISE_ADDRESS, 1_000_000, false)))
        listOf(
            "{}",
            """{"coins_per_utxo_size":null}""",
            """{"coins_per_utxo_size":4310}""",
            """{"coins_per_utxo_size":"4.310"}""",
            """{"coins_per_utxo_size":"0"}""",
            """{"coins_per_utxo_size":"-1"}""",
            """{"coins_per_utxo_size":"9223372036854775808"}""",
            "{",
        ).forEach { invalid ->
            val failure = assertFailsWith<IllegalArgumentException> {
                engine.requireMinimumAda(valid, invalid)
            }
            assertEquals("invalid coins_per_utxo_size", failure.message)
        }
        assertEquals(
            "invalid transaction CBOR",
            assertFailsWith<IllegalArgumentException> {
                engine.requireMinimumAda(valid, protocolParameters(Long.MAX_VALUE.toString()))
            }.message,
        )
    }

    @Test fun checksEveryOutputCollateralDatumAndOriginalEncoding() {
        val engine = AndroidCardanoTransactionEngine(processor)
        val recipient = output(ENTERPRISE_ADDRESS, 857_690, true)
        val change = output(BASE_ADDRESS, 978_370, true)
        val complete = transaction(listOf(recipient, change), recipient)
        val original = complete.copyOf()
        engine.requireMinimumAda(complete, protocolParameters("4310"))
        assertContentEquals(original, complete)

        listOf(
            transaction(listOf(output(ENTERPRISE_ADDRESS, 857_689, true), change)),
            transaction(listOf(recipient, output(BASE_ADDRESS, 978_369, true))),
            transaction(listOf(recipient, change), output(ENTERPRISE_ADDRESS, 857_689, true)),
        ).forEach { insufficient ->
            assertFailsWith<IllegalArgumentException> {
                engine.requireMinimumAda(insufficient, protocolParameters("4310"))
            }
        }

        val provisionalDatum = inlineOutput(ENTERPRISE_ADDRESS, 1_000_000)
        val datumMinimum = (160L + CborSerializationUtil.serialize(provisionalDatum).size) * 4310
        val exactDatum = inlineOutput(ENTERPRISE_ADDRESS, datumMinimum)
        assertEquals(
            CborSerializationUtil.serialize(provisionalDatum).size,
            CborSerializationUtil.serialize(exactDatum).size,
        )
        assertTrue(datumMinimum > 857_690)
        engine.requireMinimumAda(transaction(listOf(exactDatum)), protocolParameters("4310"))
        assertFailsWith<IllegalArgumentException> {
            engine.requireMinimumAda(
                transaction(listOf(inlineOutput(ENTERPRISE_ADDRESS, datumMinimum - 1))),
                protocolParameters("4310"),
            )
        }

        val belowUint32 = output(ENTERPRISE_ADDRESS, 4_294_967_295, false)
        val atUint32 = output(ENTERPRISE_ADDRESS, 4_294_967_296, false)
        assertEquals(4, CborSerializationUtil.serialize(atUint32).size - CborSerializationUtil.serialize(belowUint32).size)
        engine.requireMinimumAda(transaction(listOf(atUint32)), protocolParameters("21367996"))

        val canonical = transaction(listOf(output(ENTERPRISE_ADDRESS, 194, false)))
        val nonminimal = nonminimalCoin(canonical)
        val nonminimalOriginal = nonminimal.copyOf()
        assertFailsWith<IllegalArgumentException> {
            engine.requireMinimumAda(nonminimal, protocolParameters("1"))
        }
        assertContentEquals(nonminimalOriginal, nonminimal)

        val missingValue = CborMap().apply {
            put(UnsignedInteger(0), ByteString(Address(ENTERPRISE_ADDRESS).bytes))
        }
        listOf(
            byteArrayOf(),
            canonical + byteArrayOf(0),
            transaction(emptyList()),
            transaction(listOf(SimpleValue.NULL)),
            transaction(listOf(missingValue)),
        ).forEach { malformed ->
            assertFailsWith<IllegalArgumentException> {
                engine.requireMinimumAda(malformed, protocolParameters("1"))
            }
        }
    }

    @Test fun localMainnetTransferAndSweepPassAtCurrentAndDoubledCost() = runBlocking {
        val engine = AndroidCardanoTransactionEngine(processor)
        val sourceEntropy = ByteArray(32) { it.toByte() }
        val destinationEntropy = ByteArray(32) { (it + 1).toByte() }
        try {
            val source = engine.deriveWallet(sourceEntropy, CardanoNetwork.MAINNET)
            val destination = engine.deriveWallet(destinationEntropy, CardanoNetwork.MAINNET)
            listOf("4310", "8620").forEach { cost ->
                val ledger = LedgerSnapshot(
                    CardanoNetwork.MAINNET,
                    listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))),
                    protocolParameters(cost),
                    100,
                )
                listOf<CardanoIntent>(
                    CardanoIntent.Transfer(
                        source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000),
                        "00000000-0000-4000-8000-000000000015", 100, 200,
                    ),
                    CardanoIntent.SweepWallet(
                        source.paymentAddress, destination.paymentAddress, Lovelace(5_000_000),
                        "00000000-0000-4000-8000-000000000016", 100, 200,
                    ),
                ).forEach { intent ->
                    val unsigned = engine.build(intent, ledger)
                    val unsignedBytes = unsigned.cbor.copyOf()
                    engine.inspect(unsigned.cbor).also {
                        it.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                        it.requireL1Funding(intent, ledger)
                        it.requireL1Witnesses(source.paymentCredentialHex, signed = false)
                    }
                    engine.requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)
                    assertContentEquals(unsignedBytes, unsigned.cbor)

                    val signed = engine.sign(unsigned, sourceEntropy, intent, ledger)
                    try {
                        engine.inspect(signed.cbor).also {
                            it.requireMatches(intent, CardanoNetwork.MAINNET, unsigned.feeBound)
                            it.requireL1Funding(intent, ledger)
                            it.requireL1Witnesses(source.paymentCredentialHex, signed = true)
                        }
                        engine.requireMinimumAda(signed.cbor, ledger.protocolParametersJson)
                        assertEquals(engine.transactionId(unsigned.cbor), engine.transactionId(signed.cbor))
                    } finally {
                        signed.cbor.fill(0)
                    }
                }
            }
        } finally {
            sourceEntropy.fill(0)
            destinationEntropy.fill(0)
        }
    }

    @Test fun repositoryRejectsTransferAndSweepParameterDriftBeforeSideEffects() = runBlocking {
        val engine = AndroidCardanoTransactionEngine(processor)
        val sourceEntropy = ByteArray(32) { it.toByte() }
        val destinationEntropy = ByteArray(32) { (it + 1).toByte() }
        try {
            val sourceWallet = engine.deriveWallet(sourceEntropy, CardanoNetwork.MAINNET)
            val destinationWallet = engine.deriveWallet(destinationEntropy, CardanoNetwork.MAINNET)
            val source = WalletProfile(
                WalletId("mainnet-${sourceWallet.paymentCredentialHex}"),
                "Source",
                CardanoNetwork.MAINNET,
                sourceWallet.paymentAddress,
                sourceWallet.stakeAddress,
            )
            val destination = WalletProfile(
                WalletId("mainnet-${destinationWallet.paymentCredentialHex}"),
                "Destination",
                CardanoNetwork.MAINNET,
                destinationWallet.paymentAddress,
                destinationWallet.stakeAddress,
            )

            suspend fun rejectDrift(
                previewAndSubmit: suspend (
                    DefaultL1WalletRepository,
                    LedgerSnapshot,
                    (LedgerSnapshot) -> Unit,
                ) -> Unit,
            ) {
                val vault = CountingVault(listOf(source, destination))
                val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
                var ledger = LedgerSnapshot(
                    CardanoNetwork.MAINNET,
                    listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(100_000_000))),
                    protocolParameters("4310"),
                    100,
                )
                var remoteCalls = 0
                val repository = DefaultL1WalletRepository(
                    wallets,
                    vault,
                    { ledger },
                    { emptyList<TransactionRecord>() },
                    { _, _ -> remoteCalls++; error("submission must not run") },
                    { _, _ -> remoteCalls++; error("lookup must not run") },
                    engine,
                    { "00000000-0000-4000-8000-000000000017" },
                    { 123L },
                )
                previewAndSubmit(repository, ledger) { ledger = it }
                assertEquals(0, vault.seedRequests)
                assertEquals(0, vault.writes)
                assertEquals(0, remoteCalls)
                assertNull(repository.operation(source.id))
            }

            rejectDrift { repository, ledger, updateLedger ->
                val preview = repository.previewTransfer(source.id, destination, Lovelace(1_000_000))
                engine.requireMinimumAda(preview.unsigned!!.cbor, ledger.protocolParametersJson)
                updateLedger(ledger.copy(protocolParametersJson = protocolParameters("8620")))
                assertFailsWith<IllegalArgumentException> {
                    repository.submitTransfer(source.id, preview)
                }
            }

            rejectDrift { repository, ledger, updateLedger ->
                val intent = CardanoIntent.SweepWallet(
                    source.paymentAddress,
                    destination.paymentAddress,
                    Lovelace(1_000_000),
                    "00000000-0000-4000-8000-000000000017",
                    100,
                    200,
                )
                val built = engine.build(intent, ledger)
                val destinationIndex = engine.inspect(built.cbor).outputs.indexOfFirst {
                    it.address == destination.paymentAddress && it.lovelace == intent.amount
                }
                require(destinationIndex >= 0)
                val envelope = decode(built.cbor)
                val outputs = (envelope.dataItems[0] as CborMap)[UnsignedInteger(1)] as CborArray
                val retained = outputs.dataItems[destinationIndex]
                outputs.dataItems.clear()
                outputs.add(retained)
                val cbor = CborSerializationUtil.serialize(envelope)
                val summary = engine.inspect(cbor)
                val exactLedger = ledger.copy(
                    utxos = listOf(ledger.utxos.single().copy(
                        lovelace = Lovelace(Math.addExact(intent.amount.value, summary.fee.value)),
                    )),
                )
                summary.requireMatches(intent, CardanoNetwork.MAINNET, summary.fee)
                summary.requireL1Funding(intent, exactLedger)
                engine.requireMinimumAda(cbor, exactLedger.protocolParametersJson)
                val unsigned = UnsignedTransaction(cbor, intent.operationId, summary.fee)
                val preview = SweepPreview(
                    destination.paymentAddress,
                    intent.amount,
                    summary.fee,
                    intent,
                    unsigned,
                    engine.transactionId(cbor),
                )

                updateLedger(exactLedger.copy(protocolParametersJson = protocolParameters("8620")))
                assertFailsWith<IllegalArgumentException> {
                    repository.submitSweep(source.id, preview)
                }
            }
        } finally {
            sourceEntropy.fill(0)
            destinationEntropy.fill(0)
        }
    }


    private fun output(address: String, lovelace: Long, postAlonzo: Boolean): DataItem {
        val addressBytes = ByteString(Address(address).bytes)
        val coin = UnsignedInteger(lovelace)
        return if (postAlonzo) {
            CborMap().apply {
                put(UnsignedInteger(0), addressBytes)
                put(UnsignedInteger(1), coin)
            }
        } else {
            CborArray().apply {
                add(addressBytes)
                add(coin)
            }
        }
    }

    private fun transaction(outputs: List<DataItem>, collateralReturn: DataItem? = null): ByteArray {
        val body = CborMap().apply {
            put(UnsignedInteger(1), CborArray().apply { outputs.forEach(::add) })
            collateralReturn?.let { put(UnsignedInteger(16), it) }
        }
        val envelope = CborArray().apply {
            add(body)
            add(CborMap())

            add(SimpleValue.TRUE)
            add(SimpleValue.NULL)
        }
        return CborSerializationUtil.serialize(envelope)
    }
    private fun inlineOutput(address: String, lovelace: Long): DataItem {
        val datum = ByteString(CborSerializationUtil.serialize(UnsignedInteger(0))).apply { tag = Tag(24) }
        return CborMap().apply {
            put(UnsignedInteger(0), ByteString(Address(address).bytes))
            put(UnsignedInteger(1), UnsignedInteger(lovelace))
            put(UnsignedInteger(2), CborArray().apply {
                add(UnsignedInteger(1))
                add(datum)
            })
        }
    }

    private fun nonminimalCoin(cbor: ByteArray): ByteArray {
        val index = (0 until cbor.lastIndex).single {
            cbor[it] == 0x18.toByte() && cbor[it + 1] == 0xc2.toByte()
        }
        return cbor.copyOf(cbor.size + 1).also { result ->
            cbor.copyInto(result, index + 3, index + 2)
            result[index] = 0x19
            result[index + 1] = 0
            result[index + 2] = 0xc2.toByte()
        }
    }

    private fun protocolParameters(coinsPerUtxoSize: String) =
        PROTOCOL_PARAMETERS.replace("\"4310\"", "\"$coinsPerUtxoSize\"")

    private data class OutputMinimumVector(
        val address: String,
        val postAlonzo: Boolean,
        val encodedSize: Int,
        val minimum: Long,
    )


    private fun decode(cbor: ByteArray) =
        CborDecoder(ByteArrayInputStream(cbor)).decodeNext() as CborArray

    private fun mutate(cbor: ByteArray, mutation: (CborArray) -> Unit): ByteArray {
        val envelope = decode(cbor)
        mutation(envelope)
        return ByteArrayOutputStream().also { CborEncoder(it).encode(envelope) }.toByteArray()
    }

    private class CountingVault(private val storedProfiles: List<WalletProfile>) : SecureVault {
        private val states = storedProfiles.associate { it.id to WalletEncryptedStateV1() }.toMutableMap()
        var seedRequests = 0
        var writes = 0
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = storedProfiles
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
            seedRequests++
            return action(ByteArray(32))
        }
        override suspend fun walletState(walletId: WalletId): WalletEncryptedStateV1 =
            states.getValue(walletId).copy(
                channelRecovery = states.getValue(walletId).channelRecovery.copyOf(),
                operationJournal = states.getValue(walletId).operationJournal.copyOf(),
            )
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            writes++
            states[walletId] = state.copy(
                channelRecovery = state.channelRecovery.copyOf(),
                operationJournal = state.operationJournal.copyOf(),
            )
        }
    }


    companion object {
        private const val ENTERPRISE_ADDRESS = "addr1v83gkkw3nqzakg5xynlurqcfqhgd65vkfvf5xv8tx25ufds2yvy2h"
        private const val PINNED_KONDUIT_COMMIT = "b9ac1e08897e39f5f0253541548c8845f392b3b4"
        private const val USED_EVIDENCE = "9f07187bff"
        private const val PENDING_EVIDENCE = "9f1901c81b0000018bcfe5687b58200404040404040404040404040404040404040404040404040404040404040404ff"
        private const val BASE_ADDRESS = "addr1qytp6yfl9wwamcqu3j5kqhjz8hlgkt62nd82d837g9dlsmn85wjc8sjtq2wqxfmahmpn6h85y0ug7mzclf2jl4zyt3vq587s69"
        private const val PROTOCOL_PARAMETERS = """{
          "min_fee_a":44,"min_fee_b":155381,"max_tx_size":16384,
          "key_deposit":"2000000","pool_deposit":"500000000","min_pool_cost":"170000000",
          "protocol_major_ver":10,"protocol_minor_ver":0,"coins_per_utxo_size":"4310",
          "collateral_percent":150,"max_collateral_inputs":3
        }"""
    }
}
