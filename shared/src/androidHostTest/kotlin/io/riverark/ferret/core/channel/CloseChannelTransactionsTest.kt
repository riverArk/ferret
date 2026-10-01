package io.riverark.ferret.core.channel

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.util.HexUtil
import io.riverark.ferret.core.cardano.*
import io.riverark.ferret.core.model.*
import io.riverark.ferret.core.network.*
import io.riverark.ferret.core.security.*
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class CloseChannelTransactionsTest {
    @Test fun closeKeepsSeparateFeeFuelWhenFiveAdaCollateralAlreadyExists() = runBlocking {
        for (native in listOf(false, true)) for (reverse in listOf(false, true)) {
            val f = CloseTransactionFixture()
            val asset = if (native) f.catalog.assets.single { it.alias == "usdcx" } else f.catalog.ada
            val channel = f.install(asset, if (native) 1_085_308 else 3_000_000)
            val wallet = f.ledger.utxos.filter { it.address == f.profile.paymentAddress }.map {
                if (it.lovelace.value == 100_000_000L) it.copy(lovelace = Lovelace(2_612_780)) else it
            }
            f.ledger = f.ledger.copy(utxos = (if (reverse) wallet.reversed() else wallet) +
                f.ledger.utxos.filter { it.address != f.profile.paymentAddress })
            val preview = f.transactions.previewClose(f.profile.id, channel, f.operationId)
            val summary = f.engine.inspect(f.payload(preview.operation).unsignedBody)
            val collateralAda = summary.collateralInputs.sumOf { ref ->
                wallet.single { it.transactionId == ref.transactionId && it.index == ref.index }.lovelace.value
            }
            assertEquals(5_000_000L, collateralAda)
            assertTrue(summary.inputs.any { ref ->
                wallet.any { it.transactionId == ref.transactionId && it.index == ref.index &&
                    it.lovelace.value == 2_612_780L }
            })
            assertEquals(0, f.signingCalls)
        }
    }

    @Test fun closeRetainsGrossValueAndHistoricalConstantsIncludingZeroCapacity() = runBlocking {
        val f = CloseTransactionFixture()
        for (asset in listOf(f.catalog.ada, f.usda)) {
            for (quantity in listOf(0L, if (asset == f.catalog.ada) 3_000_000L else 40L)) {
                val channel = f.install(asset = asset, quantity = quantity)
                val input = f.channelInput
                val preview = f.transactions.previewClose(f.profile.id, channel, f.operationId)
                val intent = f.intent(preview.operation)
                val output = f.engine.inspect(f.payload(preview.operation).unsignedBody).outputs.single {
                    it.address == MAINNET.validatorAddress
                }
                assertEquals(ChannelAction.Close, preview.operation.action)
                assertEquals(channel.state, preview.operation.priorChannelState)
                assertEquals(channel.spendableBalance, preview.operation.priorSpendableBalance)
                assertEquals(channel.spendableBalance, preview.resultingSpendableBalance)
                assertEquals(AssetAmount(asset, quantity), preview.amount)
                assertEquals(4_492_800L, intent.validFrom)
                assertEquals(4_493_400L, intent.validUntil)
                assertEquals(ChannelDatumStage.Closed(0, elapseAtEpochMillis = 1_596_059_751_000), intent.resultingDatum?.stage)
                assertEquals(60_000L, intent.currentDatum.constants.closePeriodMillis)
                assertEquals(input.assets, output.assets)
                assertTrue(output.lovelace.value >= input.lovelace.value)
                assertEquals(output.lovelace.value, preview.outputAda.baseUnits)
                assertEquals(input.lovelace, intent.amount)
                assertEquals(0, f.signingCalls)
                assertEquals(0, f.vault.signingCalls)
            }
        }
    }

    @Test fun terminalPreviewsUseActualInputNotTrackedBalanceAndDeadlineEquality() = runBlocking {
        for (assetIsAda in listOf(true, false)) {
            val f = CloseTransactionFixture()
            val asset = if (assetIsAda) f.catalog.ada else f.usda
            for (stage in listOf(
                ChannelDatumStage.Closed(0, elapseAtEpochMillis = f.deadline),
                ChannelDatumStage.Responded(0, listOf(f.pending(f.deadline))),
            )) {
                var channel = f.install(asset, if (assetIsAda) 3_000_000 else 40, stage)
                channel = channel.copy(spendableBalance = AssetAmount(asset, 0))
                f.ledger = f.ledger.copy(currentSlot = 4_492_979)
                val wait = assertFailsWith<ChannelReturnNotReadyException> {
                    f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId)
                }
                assertEquals(f.deadline, wait.afterEpochMillis)
                f.ledger = f.ledger.copy(currentSlot = 4_492_980)
                val preview = f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId)
                val intent = f.intent(preview.operation)
                assertEquals(if (stage is ChannelDatumStage.Closed) CloseChannelStep.ELAPSE else CloseChannelStep.END, intent.step)
                assertEquals(AssetAmount(asset, if (assetIsAda) 5_000_000 else 40), preview.amount)
                assertEquals(f.channelInput.lovelace.value, preview.outputAda.baseUnits)
                assertEquals(AssetAmount(asset, 0), preview.resultingSpendableBalance)
                assertNull(intent.resultingDatum)
                assertTrue(f.engine.inspect(f.payload(preview.operation).unsignedBody).outputs.all {
                    it.address == f.profile.paymentAddress && it.datum == TransactionDatum.Absent && it.scriptReference == null
                })
            }
            val empty = f.install(asset, 0, ChannelDatumStage.Responded(0))
            assertEquals(CloseChannelStep.END, f.intent(f.transactions.previewReturnFunds(f.profile.id, empty, f.operationId).operation).step)
        }
    }

    @Test fun batchObservationUsesUniqueEvidenceDepthAndHistoricalPeriod() = runBlocking<Unit> {
        val f = CloseTransactionFixture()
        val first = f.install(stage = ChannelDatumStage.Closed(0, elapseAtEpochMillis = f.deadline))
        val secondInput = f.channelInput.copy(transactionId = "44".repeat(32), datumHex = f.datum(
            f.catalog.ada, ChannelDatumStage.Responded(0), tag = "02",
        ).plutus().serializeToHex())
        val second = first.copy(keytag = f.keytag("02"), state = ChannelState.Responded)
        val foreign = f.channelInput.copy(transactionId = "55".repeat(32), datumHex = "00")
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos + secondInput + foreign, currentSlot = 4_492_980)
        f.transaction = f.observationTransaction(depth = 4)
        val reads = f.ledgerReads
        var observed = f.transactions.observe(f.profile.id, listOf(first, second))
        assertEquals(reads + 1, f.ledgerReads)
        assertEquals(setOf(first.keytag, second.keytag), observed.keys)
        assertFalse(observed.getValue(first.keytag).canReturn)
        f.transaction = f.observationTransaction(depth = 5)
        observed = f.transactions.observe(f.profile.id, listOf(first, second))
        assertTrue(observed.values.all { it.canReturn })
        assertEquals(f.deadline, observed.getValue(first.keytag).returnAfterEpochMillis)
        assertEquals(0L, observed.getValue(second.keytag).returnAfterEpochMillis)
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos + f.channelInput.copy(transactionId = "66".repeat(32)))
        assertFailsWith<IllegalArgumentException> { f.transactions.observe(f.profile.id, listOf(first, second)) }
    }

    @Test fun malformedTrackedEvidenceAndChangedStageCannotAuthorizeReturn() = runBlocking {
        val f = CloseTransactionFixture()
        val channel = f.install(stage = ChannelDatumStage.Responded(0))
        val evidence = f.pending(f.deadline)
        val malformed = f.datum(f.catalog.ada, ChannelDatumStage.Responded(0, listOf(evidence)))
            .plutus().serializeToHex().replace(evidence, "8101")
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos.map {
            if (it == f.channelInput) it.copy(datumHex = malformed) else it
        })
        assertFailsWith<ChannelStateChangedException> { f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId) }
        f.install(stage = ChannelDatumStage.Opened(0))
        assertFailsWith<ChannelStateChangedException> { f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId) }
        assertEquals(0, f.signingCalls)
    }

    @Test fun signingAndReplayPreserveSavedBytesAfterLowerBoundStartsButRejectExpiryAndDrift() = runBlocking {
        val f = CloseTransactionFixture()
        val channel = f.install()
        val preview = f.transactions.previewClose(f.profile.id, channel, f.operationId)
        val bytes = f.payload(preview.operation).unsignedBody.copyOf()
        f.ledger = f.ledger.copy(currentSlot = 4_492_801)
        val signed = f.transactions.sign(f.profile.id, preview.operation)
        assertContentEquals(bytes, f.payload(signed).unsignedBody)
        val signedBytes = f.payload(signed).signedTransaction.copyOf()
        f.transactions.validateReplay(f.profile.id, signed)
        assertContentEquals(signedBytes, f.payload(signed).signedTransaction)
        assertEquals(1, f.signingCalls)
        f.ledger = f.ledger.copy(currentSlot = f.intent(signed).validUntil)
        assertFailsWith<IllegalArgumentException> { f.transactions.validateReplay(f.profile.id, signed) }
        f.ledger = f.ledger.copy(currentSlot = 4_492_801, utxos = f.ledger.utxos.map {
            if (it == f.channelInput) it.copy(lovelace = Lovelace(it.lovelace.value + 1)) else it
        })
        assertFailsWith<ChannelStateChangedException> { f.transactions.sign(f.profile.id, preview.operation) }
        assertEquals(1, f.signingCalls)
    }

    @Test fun savedShorterCloseWindowRemainsImmutableThroughSigningReplayAndConfirmation() = runBlocking {
        val f = CloseTransactionFixture()
        val channel = f.install()
        val preview = f.transactions.previewClose(f.profile.id, channel, f.operationId)
        val fresh = f.intent(preview.operation)
        val until = fresh.validFrom + 120
        val intent = fresh.copy(
            validUntil = until,
            resultingDatum = fresh.currentDatum.copy(stage = ChannelDatumStage.Closed(
                fresh.currentDatum.stage.accountedAmount, fresh.currentDatum.stage.evidenceCborHex,
                cardanoSlotEpochMillis(f.profile.network, until) + fresh.currentDatum.constants.closePeriodMillis)),
        )
        val unsigned = f.engine.build(intent, f.ledger)
        val hash = f.engine.transactionId(unsigned.cbor)
        val operation = preview.operation.copy(intentHash = hash, payload = ChannelPayload.Transaction(
            unsigned.cbor.copyOf(), hash, intent = intent, feeBound = unsigned.feeBound))
        val signed = f.transactions.sign(f.profile.id, operation)
        val bytes = f.payload(signed).signedTransaction.copyOf()
        f.ledger = f.ledger.copy(currentSlot = until - 1)
        f.transactions.validateReplay(f.profile.id, signed)
        assertContentEquals(bytes, f.payload(signed).signedTransaction)
        assertEquals(until, f.engine.inspect(bytes).validityEnd)
        assertEquals(1, f.signingCalls)
        f.ledger = f.ledger.copy(currentSlot = until)
        assertFailsWith<IllegalArgumentException> { f.transactions.validateReplay(f.profile.id, signed) }
        f.transaction = f.project(signed, depth = 5)
        assertEquals(ChannelState.Closed, f.transactions.confirmOperation(f.profile.id, signed)?.first?.state)
        assertEquals(1, f.signingCalls)
    }

    @Test fun confirmationRequiresExactSavedTransactionAtDepthFiveButAllowsSpentWalletOutputs() = runBlocking {
        for (close in listOf(true, false)) {
            val f = CloseTransactionFixture()
            val signed = f.signedOperation(close)
            val exact = f.project(signed, depth = 5)
            f.transaction = exact.copy(depth = 4)
            assertNull(f.transactions.confirmOperation(f.profile.id, signed))
            val changed = listOf(
                exact.copy(id = "ff".repeat(32)),
                exact.copy(invalidAfter = requireNotNull(exact.invalidAfter) + 1),
                exact.copy(inputs = exact.inputs.drop(1)),
                exact.copy(inputs = exact.inputs.mapIndexed { index, input -> if (index == 0) input.copy(outputIndex = input.outputIndex + 1) else input }),
                exact.copy(outputs = exact.outputs.mapIndexed { index, output -> if (index == 0) output.copy(address = MAINNET.scriptDeploymentAddress) else output }),
                exact.copy(outputs = exact.outputs.mapIndexed { index, output -> if (index == 0) output.copy(value = listOf(ConnectorAssetDto("lovelace", "1"))) else output }),
                exact.copy(outputs = exact.outputs.mapIndexed { index, output -> if (index != 0) output
                    else if (output.datumInline != null) output.copy(datumInline = "00")
                    else output.copy(datumHash = "aa".repeat(32)) }),
            )
            for (mismatch in changed) {
                f.transaction = mismatch
                assertNull(f.transactions.confirmOperation(f.profile.id, signed))
            }
            f.transaction = exact.copy(outputs = exact.outputs.map { it.copy(consumedBy = "ee".repeat(32)) })
            val (result, proof) = requireNotNull(f.transactions.confirmOperation(f.profile.id, signed))
            assertEquals(OperationState.COMPLETED, result.status)
            assertEquals(5L, result.confirmationDepth)
            if (close) assertNull(proof) else {
                val returned = requireNotNull(proof)
                assertEquals(f.channelInput, returned.channelInput)
                assertEquals(AssetAmount(f.catalog.ada, 5_000_000), returned.returnedAmount)
                assertEquals(f.channelInput.lovelace, returned.releasedAda)
                assertEquals(f.engine.inspect(f.payload(signed).signedTransaction).fee, returned.fee)
            }
        }
    }

    @Test fun nativeTerminalProofIncludesTokensAndFullAdaWithSeparateFee() = runBlocking {
        val f = CloseTransactionFixture()
        val channel = f.install(f.usda, 40, ChannelDatumStage.Responded(0))
        val signed = f.transactions.sign(f.profile.id, f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId).operation)
        f.transaction = f.project(signed, 5)
        val proof = requireNotNull(requireNotNull(f.transactions.confirmOperation(f.profile.id, signed)).second)
        assertEquals(AssetAmount(f.usda, 40), proof.returnedAmount)
        assertEquals(Lovelace(2_000_000), proof.releasedAda)
    }

    @Test fun collateralOnlyPhaseTwoFailureRequiresExactProjectionAndUnspentChannel() = runBlocking {
        for (close in listOf(true, false)) {
            val f = CloseTransactionFixture()
            val signed = f.signedOperation(close)
            val exact = f.project(signed, 5, phaseTwoFailure = true)
            f.transaction = exact.copy(depth = 4)
            assertNull(f.transactions.confirmOperation(f.profile.id, signed))
            f.transaction = exact.copy(outputs = emptyList())
            if (exact.outputs.isNotEmpty()) assertNull(f.transactions.confirmOperation(f.profile.id, signed))
            f.transaction = exact
            val (result, proof) = requireNotNull(f.transactions.confirmOperation(f.profile.id, signed))
            assertEquals(OperationState.FAILED, result.status)
            assertEquals(signed.priorChannelState, result.state)
            assertNull(proof)
            assertEquals(f.payload(signed).expectedTransactionId, result.transactionId)
            assertEquals(f.intent(signed).step, result.closeStep)
            assertTrue(requireNotNull(result.failureMessage).contains(requireNotNull(f.engine.inspect(f.payload(signed).signedTransaction).totalCollateral).value.toString()))
            f.ledger = f.ledger.copy(utxos = f.ledger.utxos.filterNot { it == f.channelInput })
            assertNull(f.transactions.confirmOperation(f.profile.id, signed))
        }
    }

    @Test fun nativeWalletFundingIsConservedAndSiblingChannelAssetsAreRejected() = runBlocking<Unit> {
        val f = CloseTransactionFixture()
        val channel = f.install(f.usda, 40, ChannelDatumStage.Responded(0))
        val siblingUnit = f.catalog.assets.single { it.alias == "usdm" }.connectorUnit
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos.map {
            if (it.transactionId == "00".repeat(32)) it.copy(assets = mapOf(siblingUnit to 7)) else it
        })
        val preview = f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId)
        val summary = f.engine.inspect(f.payload(preview.operation).unsignedBody)
        assertEquals(40L, summary.outputs.sumOf { it.assets[f.usda.connectorUnit] ?: 0 })
        assertEquals(7L, summary.outputs.sumOf { it.assets[siblingUnit] ?: 0 })
        f.transactions.sign(f.profile.id, preview.operation)
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos.map {
            if (it == f.channelInput) it.copy(assets = mapOf(siblingUnit to 40)) else it
        })
        assertFailsWith<IllegalArgumentException> { f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId) }
    }

    @Test fun phaseTwoFailureRejectsForgedCollateralValueAddressAndChannelContent() = runBlocking {
        val f = CloseTransactionFixture()
        val channel = f.install(f.usda, 40, ChannelDatumStage.Responded(0))
        val signed = f.transactions.sign(f.profile.id, f.transactions.previewReturnFunds(f.profile.id, channel, f.operationId).operation)
        val exact = f.project(signed, 5, phaseTwoFailure = true)
        for (projection in listOf(
            exact.copy(inputs = exact.inputs.map { it.copy(address = MAINNET.validatorAddress) }),
            exact.copy(inputs = exact.inputs.map { it.copy(value = listOf(ConnectorAssetDto("lovelace", "5000001"))) }),
            exact.copy(inputs = exact.inputs.map { it.copy(value = it.value + ConnectorAssetDto(f.usda.connectorUnit, "1")) }),
        )) {
            f.transaction = projection
            assertNull(f.transactions.confirmOperation(f.profile.id, signed))
        }
        f.transaction = exact
        assertEquals(OperationState.FAILED, requireNotNull(f.transactions.confirmOperation(f.profile.id, signed)).first.status)
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos.map {
            if (it == f.channelInput) it.copy(lovelace = Lovelace(2_000_001)) else it
        })
        assertNull(f.transactions.confirmOperation(f.profile.id, signed))
    }

    @Test fun absentCallbacksAlsoRejectPersistedCloseAuthorizationAndConfirmationBeforeReads() = runBlocking {
        val f = CloseTransactionFixture()
        val signed = f.signedOperation(close = true)
        val unavailable = f.transactions(enabled = false)
        val reads = f.ledgerReads
        val signs = f.signingCalls
        assertFailsWith<IllegalStateException> { unavailable.sign(f.profile.id, signed) }
        assertFailsWith<IllegalStateException> { unavailable.validateReplay(f.profile.id, signed) }
        assertFailsWith<IllegalStateException> { unavailable.confirmOperation(f.profile.id, signed) }
        assertEquals(reads, f.ledgerReads)
        assertEquals(signs, f.signingCalls)
    }

    @Test fun collateralOnlyFailureWithNoReturnProvesTheFullRecordedCollateralLoss() = runBlocking {
        val f = CloseTransactionFixture()
        val original = f.signedOperation()
        val summary = f.engine.inspect(f.payload(original).signedTransaction)
        val total = summary.collateralInputs.sumOf { ref ->
            f.ledger.utxos.single { it.transactionId == ref.transactionId && it.index == ref.index }.lovelace.value
        }
        // Exercise connector proof of a valid signed no-return body, independently of builder selection.
        val transaction = Transaction.deserialize(f.payload(original).unsignedBody)
        transaction.body.collateralReturn = null
        transaction.body.totalCollateral = BigInteger.valueOf(total)
        val unsigned = transaction.serialize()
        val account = Account.createFromMnemonic(Networks.mainnet(),
            MnemonicCode.INSTANCE.toMnemonic(f.entropy).joinToString(" "))
        val signed = account.sign(transaction).serialize()
        val hash = f.engine.transactionId(signed)
        val operation = original.copy(intentHash = hash, payload = f.payload(original).copy(
            unsignedBody = unsigned, signedTransaction = signed, expectedTransactionId = hash))
        f.transaction = f.project(operation, phaseTwoFailure = true)
        assertTrue(requireNotNull(f.transaction).outputs.isEmpty())
        val (failure, proof) = requireNotNull(f.transactions.confirmOperation(f.profile.id, operation))
        assertEquals(OperationState.FAILED, failure.status)
        assertNull(proof)
        assertTrue(requireNotNull(failure.failureMessage).contains(total.toString()))
        assertEquals(original.priorChannelState, failure.state)
    }

    @Test fun absentCallbacksRejectCloseBeforeReadsOrSigningAndKeepOpenAddSupported() = runBlocking {
        val f = CloseTransactionFixture()
        val channel = f.install()
        val transactions = f.transactions(enabled = false)
        assertFalse(transactions.closeAvailable)
        val reads = f.ledgerReads
        assertFailsWith<IllegalStateException> { transactions.previewClose(f.profile.id, channel, f.operationId) }
        assertFailsWith<IllegalStateException> { transactions.previewReturnFunds(f.profile.id, channel, f.operationId) }
        assertFailsWith<IllegalStateException> { transactions.observe(f.profile.id, listOf(channel)) }
        assertEquals(reads, f.ledgerReads)
        assertEquals(0, f.signingCalls)
        f.info = f.info.copy(channelParameters = f.info.channelParameters.copy(closePeriod = AdaptorClosePeriodDto(60, 0)))
        val add = transactions.previewAdd(f.profile.id, channel, AssetAmount(f.catalog.ada, 1_000_000), f.operationId)
        assertEquals(AssetAmount(f.catalog.ada, 4_000_000), add.resultingSpendableBalance)
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos.filterNot { it == f.channelInput })
        val opened = transactions.previewOpen(f.profile.id, AssetAmount(f.catalog.ada, 5_000_000), f.operationId)
        assertEquals(AssetAmount(f.catalog.ada, 3_000_000), opened.resultingSpendableBalance)
    }
}

internal class CloseTransactionFixture {
    val digest = "a".repeat(64)
    val catalog = AssetCatalog(listOf(
        ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest),
        ChannelAsset("usda", "fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae456", "55534441", 6, AssetPricing.USD_PEG, digest),
        ChannelAsset("usdcx", "1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e34", "5553444378", 6, AssetPricing.USD_PEG, digest),
        ChannelAsset("usdm", "c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad", "0014df105553444d", 6, AssetPricing.USD_PEG, digest),
    ), digest, emptyMap())
    val usda = catalog.assets.single { it.alias == "usda" }
    private val vectors = Json.parseToJsonElement(requireNotNull(javaClass.classLoader?.getResource("konduit/channel-conformance.json")).readText()).jsonObject
    private fun vector(name: String) = requireNotNull(vectors[name]).jsonPrimitive.content
    val entropy = ByteArray(32) { it.toByte() }
    private val realEngine = AndroidCardanoTransactionEngine(object : TransactionProcessor {
        override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
        @Suppress("UNCHECKED_CAST")
        override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> =
            Result.success("fixture").withValue(Transaction.deserialize(cbor).witnessSet?.redeemers.orEmpty().map {
                EvaluationResult.builder().redeemerTag(it.tag).index(it.index.intValueExact()).exUnits(
                    ExUnits.builder().mem(BigInteger.valueOf(10_000)).steps(BigInteger.valueOf(10_000_000)).build(),
                ).build()
            }) as Result<List<EvaluationResult>>
    }, catalog)
    var signingCalls = 0
    val engine = object : CardanoTransactionEngine by realEngine {
        override fun sign(unsigned: UnsignedTransaction, seed: ByteArray, intent: CardanoIntent, ledger: LedgerSnapshot): SignedTransaction {
            signingCalls++
            return realEngine.sign(unsigned, seed, intent, ledger)
        }
    }
    val derived = runBlocking { engine.deriveWallet(entropy, CardanoNetwork.MAINNET) }
    val profile = WalletProfile(WalletId("mainnet-${derived.paymentCredentialHex}"), "Mainnet wallet", CardanoNetwork.MAINNET, derived.paymentAddress, derived.stakeAddress)
    val verificationKey = Account.createFromMnemonic(Networks.mainnet(), MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" ")).let { HexUtil.encodeHexString(it.publicKeyBytes()) }
    val vault = CloseTestVault(profile, entropy)
    val operationId = "00000000-0000-4000-8000-000000000019"
    val deadline = 1_596_059_271_000L
    val reference = ConnectorUtxoDto("11".repeat(32), 0, MAINNET.scriptDeploymentAddress,
        listOf(ConnectorAssetDto("lovelace", "2000000")), referenceScriptHash = vector("validator_hash"),
        referenceScriptVersion = 3, referenceScript = vector("reference_script")).ledger()
    var ledger = LedgerSnapshot(CardanoNetwork.MAINNET, emptyList(), requireNotNull(vectors["protocol_parameters_fixture"]).toString(), 4_492_800)
    lateinit var channelInput: LedgerUtxo
    var transaction: ConnectorTransactionDto? = null
    var ledgerReads = 0
    var info = AdaptorInfoDto(AdaptorTermsDto(0), AdaptorChannelParametersDto(MAINNET.adaptorIdentityHex, AdaptorClosePeriodDto(1_800, 0), 32),
        AdaptorTransactionHelpDto(MAINNET.scriptDeploymentAddress, MAINNET.validatorHashHex), digest)
    val transactions = transactions()
    fun transactions(enabled: Boolean = true) = ChannelTransactions(vault, engine, catalog,
        loadLedger = { ledgerReads++; ledger }, loadInfo = { info }, verificationKey = { verificationKey }, availability = {},
        newTag = { ByteArray(32) { 1 } }, nowEpochMillis = { 123 },
        loadTransaction = if (enabled) { _, id ->
            transaction?.let { tx ->
                when {
                    tx.id == "00".repeat(32) -> tx.copy(id = id)
                    id == channelInput.transactionId && tx.id != id -> observationTransaction(5).copy(id = id)
                    else -> tx
                }
            }
        } else null,
        closeReturnAfterEpochMillis = if (enabled) { encoded -> androidChannelReturnAfterEpochMillis(encoded, catalog) } else null)
    fun keytag(tag: String = "01") = ProtocolKeytag.from(verificationKey, ProtocolTag(tag.repeat(32)), 32)
    fun datum(asset: ChannelAsset = catalog.ada, stage: ChannelDatumStage = ChannelDatumStage.Opened(0), tag: String = "01") =
        ChannelDatum(MAINNET.validatorHashHex, ChannelConstants(tag.repeat(32), verificationKey, MAINNET.adaptorIdentityHex, 60_000, asset), stage)
    fun install(asset: ChannelAsset = catalog.ada, quantity: Long = 3_000_000, stage: ChannelDatumStage = ChannelDatumStage.Opened(0)): ChannelSnapshot {
        val native = if (asset != catalog.ada && quantity > 0) mapOf(requireNotNull(asset.policyId) + requireNotNull(asset.assetName) to quantity) else emptyMap()
        channelInput = LedgerUtxo("22".repeat(32), 0, MAINNET.validatorAddress, Lovelace(if (asset == catalog.ada) quantity + 2_000_000 else 2_000_000), native, datumHex = datum(asset, stage).plutus().serializeToHex())
        ledger = ledger.copy(currentSlot = 4_492_800, utxos = listOf(
            LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(100_000_000)),
            LedgerUtxo("33".repeat(32), 0, profile.paymentAddress, Lovelace(5_000_000)), reference, channelInput))
        transaction = observationTransaction(5)
        return ChannelSnapshot(keytag(), asset, when (stage) {
            is ChannelDatumStage.Opened -> ChannelState.Open("opening-transaction")
            is ChannelDatumStage.Closed -> ChannelState.Closed
            is ChannelDatumStage.Responded -> ChannelState.Responded
        }, spendableBalance = AssetAmount(asset, quantity))
    }
    fun pending(timeout: Long) = ListPlutusData.of(BigIntPlutusData.of(1), BigIntPlutusData.of(timeout), BytesPlutusData.of(ByteArray(32) { 4 })).serializeToHex()
    fun payload(operation: PreparedChannelOperation) = operation.payload as ChannelPayload.Transaction
    fun intent(operation: PreparedChannelOperation) = requireNotNull(payload(operation).intent) as CardanoIntent.CloseChannel
    suspend fun signedOperation(close: Boolean = false): PreparedChannelOperation {
        val channel = install(stage = if (close) ChannelDatumStage.Opened(0) else ChannelDatumStage.Responded(0))
        val preview = if (close) transactions.previewClose(profile.id, channel, operationId) else transactions.previewReturnFunds(profile.id, channel, operationId)
        return transactions.sign(profile.id, preview.operation)
    }
    fun observationTransaction(depth: Long) = ConnectorTransactionDto("00".repeat(32), 0, depth, 1_596_059_091, inputs = emptyList(), outputs = emptyList())
    fun project(operation: PreparedChannelOperation, depth: Long = 5, phaseTwoFailure: Boolean = false): ConnectorTransactionDto {
        val summary = engine.inspect(payload(operation).signedTransaction)
        val inputs = if (phaseTwoFailure) summary.collateralInputs else summary.inputs
        return ConnectorTransactionDto(payload(operation).expectedTransactionId, 0, depth, 1_596_059_091,
            summary.validityStart, summary.validityEnd, inputs.map { input ->
                val utxo = ledger.utxos.single { it.transactionId == input.transactionId && it.index == input.index }
                ConnectorInputDto(utxo.transactionId, utxo.index.toLong(), utxo.address, value(utxo.lovelace, utxo.assets), utxo.datumHashHex, utxo.datumHex, utxo.scriptRefHashHex)
            }, (if (phaseTwoFailure) listOfNotNull(summary.collateralReturn) else summary.outputs).map { output ->
                ConnectorOutputDto(output.address, value(output.lovelace, output.assets),
                    (output.datum as? TransactionDatum.Hash)?.hex, (output.datum as? TransactionDatum.Inline)?.cborHex, output.scriptReference?.hashHex)
            })
    }
    private fun value(ada: Lovelace, assets: Map<String, Long>) = listOf(ConnectorAssetDto("lovelace", ada.value.toString())) + assets.map { (unit, quantity) -> ConnectorAssetDto(unit, quantity.toString()) }
}

internal class CloseTestVault(private val profile: WalletProfile, private val seed: ByteArray) : SecureVault {
    var signingCalls = 0
    override val isUnlocked = true
    override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
    override fun lock() = Unit
    override suspend fun profiles() = listOf(profile)
    override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
    override suspend fun updateProfile(profile: WalletProfile) = error("not used")
    override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
    override suspend fun deleteWallet(walletId: WalletId) = error("not used")
    override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
        signingCalls++
        val copy = seed.copyOf()
        return try { action(copy) } finally { copy.fill(0) }
    }
    override suspend fun walletState(walletId: WalletId) = WalletEncryptedStateV1()
    override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) = Unit
}
