package io.riverark.ferret

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.DerivedWallet
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.TransactionKeyWitness
import io.riverark.ferret.core.cardano.SignedTransaction
import io.riverark.ferret.core.cardano.TransactionInputReference
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.TransactionSummary
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.requireL1Funding
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.cardano.requireMatches
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.L1OperationDto
import io.riverark.ferret.core.network.ConnectorUtxoDto
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletOperationJournalV2
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import io.riverark.ferret.feature.wallet.DefaultL1WalletRepository
import io.riverark.ferret.feature.wallet.TransferDestination
import io.riverark.ferret.feature.wallet.L1OperationState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith


class L1WalletRepositoryTest {
    private val catalog = testCatalog()
    private fun ada(baseUnits: Long) = AssetAmount(catalog.ada, baseUnits)

    @Test fun balanceExcludesEveryProtectedOutput() = runBlocking {
        val source = WalletProfile(
            WalletId("mainnet-${"0".repeat(56)}"),
            "Mainnet wallet",
            CardanoNetwork.MAINNET,
            "addr1source",
            "stake1source",
        )
        val wallets = WalletRepository().apply { publish(source.id, listOf(source)) }
        val vault = FakeVault(listOf(source))
        val plain = LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))
        val excluded = listOf(
            Json.decodeFromString<ConnectorUtxoDto>(
                """{"transaction_id":"${"11".repeat(32)}","output_index":0,"address":"${source.paymentAddress}","value":[{"unit":"lovelace","quantity":"20000000"}],"datum_hash":"${"aa".repeat(32)}"}""",
            ).ledger(),
            LedgerUtxo("22".repeat(32), 0, source.paymentAddress, Lovelace(20_000_000), datumHex = "d87980"),
            LedgerUtxo("33".repeat(32), 0, source.paymentAddress, Lovelace(20_000_000), scriptRefHashHex = "bb".repeat(28)),
            LedgerUtxo("44".repeat(32), 0, source.paymentAddress, Lovelace(20_000_000), mapOf("cc".repeat(28) to 0)),
            LedgerUtxo("55".repeat(32), 0, "addr1foreign", Lovelace(20_000_000)),
        )
        var ledger = LedgerSnapshot(CardanoNetwork.MAINNET, listOf(plain) + excluded, "{}", 100)
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { ledger }, { emptyList() }, { _, _ -> error("submission not used") },
        { _, _ -> error("lookup not used") }, FakeEngine(), { OPERATION_ID }, { 123L },)

        assertEquals(ada(10_000_000), repository.balance(source.id).assets.first().spendable)
        ledger = ledger.copy(utxos = excluded)
        assertEquals(ada(0), repository.balance(source.id).assets.first().spendable)
    }

    @Test fun balanceRejectsAssetAggregationOverflow() = runBlocking {
        val source = profile('0')
        val unit = catalog.asset("usdm")!!.connectorUnit
        val vault = FakeVault(listOf(source))
        val repository = DefaultL1WalletRepository(
            WalletRepository(),
            vault,
            catalog,
            {
                LedgerSnapshot(
                    CardanoNetwork.PREPROD,
                    listOf(
                        LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(1), mapOf(unit to Long.MAX_VALUE)),
                        LedgerUtxo("11".repeat(32), 0, source.paymentAddress, Lovelace(1), mapOf(unit to 1)),
                    ),
                    "{}",
                    100,
                )
            },
            { emptyList() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            FakeEngine(),
            { OPERATION_ID },
            { 0L },
        )

        assertFailsWith<IllegalArgumentException> { repository.balance(source.id) }
        Unit
    }
    @Test fun previewUsesSelectedInputsAndAllowsExactSpend() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val selected = LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))
        val unselected = LedgerUtxo("11".repeat(32), 0, source.paymentAddress, Lovelace(90_000_000))
        assertEquals(
            ada(4_800_000),
            previewRepository(vault, FakeEngine(), listOf(selected, unselected))
                .previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000)).change,
        )
        assertEquals(
            ada(0),
            previewRepository(vault, FakeEngine(), listOf(selected.copy(lovelace = Lovelace(5_200_000))))
                .previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000)).change,
        )
    }

    @Test fun balanceKeepsHoldingsSeparateFromAdaTransferAvailability() = runBlocking {
        val source = profile('0')
        val unit = catalog.asset("usdm")!!.connectorUnit
        val unknown = "f".repeat(56)
        val vault = FakeVault(listOf(source))
        val ledger = LedgerSnapshot(
            CardanoNetwork.PREPROD,
            listOf(
                LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000)),
                LedgerUtxo("11".repeat(32), 0, source.paymentAddress, Lovelace(2_000_000), mapOf(unit to 1_000_001, unknown to 7)),
            ),
            "{}",
            100,
        )
        val repository = DefaultL1WalletRepository(
            WalletRepository(),
            vault,
            catalog,
            { ledger },
            { emptyList() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            FakeEngine(),
            { OPERATION_ID },
            { 123L },
        )

        val balance = repository.balance(source.id)

        assertEquals(ada(12_000_000), balance.assets.first { it.total.asset == catalog.ada }.total)
        assertEquals(ada(10_000_000), balance.assets.first { it.total.asset == catalog.ada }.spendable)
        assertEquals(AssetAmount(catalog.asset("usdm")!!, 1_000_001), balance.assets.first { it.total.asset.alias == "usdm" }.total)
        assertEquals(AssetAmount(catalog.asset("usdm")!!, 0), balance.assets.first { it.total.asset.alias == "usdm" }.spendable)
        assertEquals(mapOf(unknown to 7L), balance.unsupportedAssets)
    }

    @Test fun nativeTransferRejectsBeforeEngineOrSeedAccess() = runBlocking {
        val source = profile('0')
        val vault = FakeVault(listOf(source))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)

        assertFailsWith<IllegalArgumentException> {
            repository.previewTransfer(
                source.id,
                TransferDestination("External", "addr_test1external"),
                AssetAmount(catalog.asset("usdm")!!, 1),
            )
        }

        assertEquals(0, engine.builds)
        assertEquals(0, vault.seedRequests)
    }

    @Test fun previewRejectsUntrustedConsumedInputsBeforeSideEffects() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val selected = TransactionInputReference("00".repeat(32), 0)
        listOf(
            emptyList(),
            listOf(selected, selected),
            listOf(TransactionInputReference("99".repeat(32), 0)),
        ).forEach { invalidInputs ->
            val vault = FakeVault(listOf(source, destination))
            val engine = FakeEngine().apply { inputOverride = invalidInputs }
            val repository = previewRepository(vault, engine)

            assertFailsWith<IllegalArgumentException> {
                repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
            }
            assertEquals(1, engine.builds)
            assertEquals(0, vault.writes)
            assertEquals(0, vault.seedRequests)
            assertEquals(0, engine.signs)
            assertNull(repository.operation(source.id))
        }
    }

    @Test fun previewAcceptsArbitraryAddressesOnTheSourceNetwork() = runBlocking {
        val source = profile('0')
        val vault = FakeVault(listOf(source))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        val external = TransferDestination("External address", "addr_test1external")

        assertEquals(external, repository.previewTransfer(source.id, external, ada(5_000_000)).destination)
        assertFailsWith<IllegalArgumentException> {
            repository.previewTransfer(source.id, TransferDestination(source.name, source.paymentAddress), ada(5_000_000))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.previewTransfer(source.id, TransferDestination("Wrong network", "addr1external"), ada(5_000_000))
        }
        assertEquals(1, engine.builds)
        assertEquals(0, vault.seedRequests)
    }

    @Test fun submissionRejectsAlteredPreviewBeforeAnySideEffect() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        for (altered in listOf(
            preview.copy(intent = preview.intent!!.copy(sourceAddress = "addr_test1forged")),
            preview.copy(feeBound = ada(300_000)),
            preview.copy(change = ada(94_800_000)),
            preview.copy(amount = ada(0)),
            preview.copy(transactionId = null),
            preview.copy(unsigned = preview.unsigned!!.copy(operationId = NEXT_OPERATION_ID)),
            preview.copy(destination = preview.destination.copy(address = "addr1forged")),
            preview.copy(destination = TransferDestination(source.name, source.paymentAddress)),
        )) {
            assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, altered) }
            assertEquals(0, vault.writes)
            assertEquals(0, vault.seedRequests)
            assertEquals(0, engine.signs)
        }
        val before = engine.inspect(preview.unsigned.cbor)
        preview.unsigned.cbor[0] = 9
        assertEquals(before, engine.inspect(preview.unsigned.cbor))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }
        assertEquals(0, vault.writes)
        assertEquals(0, vault.seedRequests)
        assertEquals(0, engine.signs)
        assertNull(repository.operation(source.id))
    }

    @Test fun submissionAcceptsDestinationOutsideTheVault() = runBlocking {
        val source = profile('0')
        val vault = FakeVault(listOf(source))
        val repository = previewRepository(vault, FakeEngine(), calls = RemoteCalls(true))
        val destination = TransferDestination("External address", "addr_test1external")
        val preview = repository.previewTransfer(source.id, destination, ada(5_000_000))

        repository.submitTransfer(source.id, preview)

        assertEquals(destination.address, repository.operation(source.id)?.destinationAddress)
        assertNull(repository.operation(source.id)?.destinationWalletId)
    }

    @Test fun submissionRevalidatesTheSourceWallet() = runBlocking {
        val source = profile('0')
        val destination = TransferDestination("External address", "addr_test1external")
        val vault = FakeVault(listOf(source))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        val preview = repository.previewTransfer(source.id, destination, ada(5_000_000))
        vault.storedProfiles = listOf(source.copy(paymentAddress = "addr_test1changed"))

        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }
        assertEquals(0, vault.writes)
        assertEquals(0, vault.seedRequests)
        assertEquals(0, engine.signs)
    }

    @Test fun signedBodyMismatchRemainsPreparedAndRejectsAfterRestartWithoutLookup() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine().apply { changeSignedBody = true }
        val repository = previewRepository(vault, engine)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }
        assertEquals(L1OperationState.PREPARED, repository.operation(source.id)?.state)
        assertNull(repository.operation(source.id)?.expectedTransactionId)
        val restarted = previewRepository(vault, engine)
        assertEquals(L1OperationState.REJECTED, restarted.reconcilePending(source.id)?.state)
        assertNull(restarted.operation(source.id)?.expectedTransactionId)
    }
    @Test fun unsignedWitnessesRejectBothL1PreviewsBeforeSideEffects() = runBlocking {
        listOf(false, true).forEach { sweep ->
            val source = profile('0')
            val destination = profile('1')
            val vault = FakeVault(listOf(source, destination))
            val calls = RemoteCalls()
            val engine = FakeEngine().apply { includeUnsignedWitness = true }
            val repository = previewRepository(vault, engine, calls = calls)

            assertFailsWith<IllegalArgumentException> {
                if (sweep) repository.previewSweep(source.id, destination.paymentAddress)
                else repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
            }
            assertEquals(0, vault.writes)
            assertEquals(0, vault.seedRequests)
            assertEquals(0, engine.signs)
            assertEquals(0, calls.submissions)
            assertEquals(0, calls.lookups)
            assertNull(repository.operation(source.id))
        }
    }

    @Test fun invalidSignedWitnessesRejectBothL1SubmissionsAndStayPrepared() = runBlocking {
        listOf(false, true).forEach { sweep ->
            SignedWitnessMode.entries.filter { it != SignedWitnessMode.VALID }.forEach { mode ->
                val source = profile('0')
                val destination = profile('1')
                val vault = FakeVault(listOf(source, destination))
                val calls = RemoteCalls()
                val engine = FakeEngine().apply { signedWitnessMode = mode }
                val repository = previewRepository(vault, engine, calls = calls)
                val preview = if (sweep) {
                    repository.previewSweep(source.id, destination.paymentAddress)
                } else {
                    repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
                }

                assertFailsWith<IllegalArgumentException> {
                    if (sweep) repository.submitSweep(source.id, preview as io.riverark.ferret.core.cardano.SweepPreview)
                    else repository.submitTransfer(source.id, preview as io.riverark.ferret.feature.wallet.TransferPreview)
                }
                assertEquals(1, vault.seedRequests)
                assertEquals(1, engine.signs)
                assertEquals(0, calls.submissions)
                assertEquals(0, calls.lookups)
                assertEquals(L1OperationState.PREPARED, repository.operation(source.id)?.state)
                val restarted = previewRepository(vault, engine, calls = calls)
                assertEquals(L1OperationState.REJECTED, restarted.reconcilePending(source.id)?.state)
                assertEquals(0, calls.lookups)
            }
        }
    }

    @Test fun sweepConvergesBeforeCheckingMinimumAda() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine().apply {
            fee = 168_669
            minimumOutputLovelace = 1_000_000
        }
        val repository = previewRepository(
            vault,
            engine,
            ledgerInputs = listOf(
                LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(1_500_000)),
            ),
        )

        val preview = repository.previewSweep(source.id, destination.paymentAddress)

        assertEquals(ada(1_331_331), preview.amount)
        assertEquals(ada(168_669), preview.fee)
        assertEquals(1, engine.builds)
    }

    @Test fun validSweepSubmitsOnceAndReconciles() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val calls = RemoteCalls(allow = true)
        val repository = previewRepository(vault, FakeEngine(), calls = calls)
        val preview = repository.previewSweep(source.id, destination.paymentAddress)

        assertEquals(OPERATION_ID, repository.submitSweep(source.id, preview))
        assertEquals(L1OperationState.PENDING, repository.operation(source.id)?.state)
        assertEquals(L1OperationState.CONFIRMED, repository.reconcilePending(source.id)?.state)
        assertEquals(1, calls.submissions)
        assertEquals(1, calls.lookups)
    }

    @Test fun minimumAdaDriftRejectsBothL1SubmissionsBeforeSideEffects() = runBlocking {
        listOf(false, true).forEach { sweep ->
            val source = profile('0')
            val destination = profile('1')
            val vault = FakeVault(listOf(source, destination))
            val calls = RemoteCalls()
            val engine = FakeEngine()
            var minimum = "0"
            val repository = previewRepository(vault, engine, calls = calls, protocolParameters = { minimum })
            val preview = if (sweep) {
                repository.previewSweep(source.id, destination.paymentAddress)
            } else {
                repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
            }

            minimum = "10000000"
            assertFailsWith<IllegalArgumentException> {
                if (sweep) repository.submitSweep(source.id, preview as io.riverark.ferret.core.cardano.SweepPreview)
                else repository.submitTransfer(source.id, preview as io.riverark.ferret.feature.wallet.TransferPreview)
            }
            assertEquals(0, vault.writes)
            assertEquals(0, vault.seedRequests)
            assertEquals(0, engine.signs)
            assertEquals(0, calls.submissions)
            assertEquals(0, calls.lookups)
            assertNull(repository.operation(source.id))
        }
    }

    @Test fun postSignMinimumFailureStaysPreparedAndRestartsLookupFree() = runBlocking {
        listOf(false, true).forEach { sweep ->
            val source = profile('0')
            val destination = profile('1')
            val vault = FakeVault(listOf(source, destination))
            val calls = RemoteCalls()
            val engine = FakeEngine().apply { rejectSignedMinimum = true }
            val repository = previewRepository(vault, engine, calls = calls)
            val preview = if (sweep) {
                repository.previewSweep(source.id, destination.paymentAddress)
            } else {
                repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
            }

            assertFailsWith<IllegalArgumentException> {
                if (sweep) repository.submitSweep(source.id, preview as io.riverark.ferret.core.cardano.SweepPreview)
                else repository.submitTransfer(source.id, preview as io.riverark.ferret.feature.wallet.TransferPreview)
            }
            assertEquals(1, vault.seedRequests)
            assertEquals(1, engine.signs)
            assertEquals(0, calls.submissions)
            assertEquals(0, calls.lookups)
            assertEquals(L1OperationState.PREPARED, repository.operation(source.id)?.state)

            val restarted = previewRepository(vault, engine, calls = calls)
            assertEquals(L1OperationState.REJECTED, restarted.reconcilePending(source.id)?.state)
            assertEquals(0, calls.lookups)
        }
    }

    @Test fun pendingChannelBlocksTransferBeforeJournalOrSeedAccess() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine, hasPendingChannel = { true })
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        val writesBefore = vault.writes

        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }

        assertEquals(0, vault.seedRequests)
        assertEquals(writesBefore, vault.writes)
        assertEquals(null, repository.operation(source.id))
    }




    private fun previewRepository(
        vault: FakeVault,
        engine: FakeEngine,
        ledgerInputs: List<LedgerUtxo>? = null,
        calls: RemoteCalls = RemoteCalls(),
        protocolParameters: () -> String = { "{}" },
        hasPendingChannel: suspend (WalletId) -> Boolean = { false },
    ) = DefaultL1WalletRepository(WalletRepository(), vault, catalog, { profile ->
        LedgerSnapshot(
            CardanoNetwork.PREPROD,
            ledgerInputs ?: listOf(LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(10_000_000))),
            protocolParameters(),
            100,
        )
    },
    { emptyList() },
    { _, request -> calls.submit(request.operationId, request.expectedTransactionId) },
    { _, operationId -> calls.lookup(operationId) },
    engine, { OPERATION_ID }, { 123L }, hasPendingChannel,)

    @Test fun rejectsDuplicateInFlightButAllowsNewTransferAfterConfirmation() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        var submissions = 0
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request ->
            submissions++
            L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
        },
        { _, _ -> error("lookup not used") },
        engine,
        { OPERATION_ID },
        { 123L },)

        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        assertEquals(ada(200_000), preview.feeBound)
        assertEquals(ada(4_800_000), preview.change)
        assertEquals(OPERATION_ID, repository.submitTransfer(source.id, preview))
        assertEquals(L1OperationState.PENDING, repository.operation(source.id)?.state)
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }
        assertEquals(1, submissions)

        val restarted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
        engine,
        { error("operation id must remain stable") },
        { 999L },)
        assertEquals(L1OperationState.CONFIRMED, restarted.reconcilePending(source.id)?.state)

        val next = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 200) },
        { emptyList<TransactionRecord>() },
        { _, request ->
            submissions++
            L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
        },
        { _, _ -> error("lookup not used") },
        engine,
        { NEXT_OPERATION_ID },
        { 1_000L },)
        val nextPreview = next.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(4_000_000))
        assertEquals(NEXT_OPERATION_ID, next.submitTransfer(source.id, nextPreview))
        assertEquals(2, submissions)
        assertEquals(2, next.operations(source.id).size)
        assertEquals(L1OperationState.CONFIRMED, next.operations(source.id).first().state)
        assertEquals(L1OperationState.PENDING, next.operations(source.id).last().state)
    }

    @Test fun successfulSubmissionKeepsPreparationTimeAndLocalMetadata() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        var clock = 99L
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
        { _, _ -> error("lookup not used") },
        FakeEngine(),
        { OPERATION_ID },
        { ++clock },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        repository.submitTransfer(source.id, preview)

        val accepted = repository.operation(source.id)!!
        assertEquals(OPERATION_ID, accepted.operationId)
        assertEquals(TRANSACTION_ID, accepted.expectedTransactionId)
        assertEquals(destination.id, accepted.destinationWalletId)
        assertEquals(ada(5_000_000), accepted.amount)
        assertEquals(ada(200_000), accepted.fee)
        assertEquals(100L, accepted.createdAtEpochMillis)
        assertEquals(L1OperationState.PENDING, accepted.state)
    }

    @Test fun lostSubmissionResponseReconcilesAcrossRestartsWithImmutableLocalDetails() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        var submissions = 0
        var lookups = 0
        var clock = 99L
        var remote: L1OperationDto? = null
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request ->
            submissions++
            remote = L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
            error("response lost after acceptance")
        },
        { _, _ -> error("lookup not used before restart") },
        engine,
        { OPERATION_ID },
        { ++clock },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))

        assertFailsWith<IllegalStateException> { repository.submitTransfer(source.id, preview) }
        val submitting = repository.operation(source.id)!!
        assertEquals(L1OperationState.SUBMITTING, submitting.state)
        assertEquals(OPERATION_ID, submitting.operationId)
        assertEquals(TRANSACTION_ID, submitting.expectedTransactionId)
        assertEquals(destination.id, submitting.destinationWalletId)
        assertEquals(ada(5_000_000), submitting.amount)
        assertEquals(ada(200_000), submitting.fee)
        assertEquals(100L, submitting.createdAtEpochMillis)

        val accepted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, _ ->
            lookups++
            requireNotNull(remote).copy(depth = 4)
        },
        engine, { error("operation id must remain stable") }, { ++clock },)
        val pending = accepted.reconcilePending(source.id)!!
        assertEquals(L1OperationState.PENDING, pending.state)
        assertEquals(100L, pending.createdAtEpochMillis)
        assertEquals(io.riverark.ferret.core.model.TransactionState.PENDING, accepted.history(source.id).single().state)

        val confirmedAtFive = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, operationId ->
            lookups++
            L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5)
        },
        engine, { error("operation id must remain stable") }, { ++clock },)
        assertEquals(L1OperationState.CONFIRMED, confirmedAtFive.reconcilePending(source.id)?.state)
        assertEquals(io.riverark.ferret.core.model.TransactionState.CONFIRMED, confirmedAtFive.history(source.id).single().state)

        val confirmedAt2159 = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, operationId ->
            lookups++
            L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 2_159)
        },
        engine, { error("operation id must remain stable") }, { ++clock },)
        val deeplyConfirmed = confirmedAt2159.reconcilePending(source.id)!!
        assertEquals(L1OperationState.CONFIRMED, deeplyConfirmed.state)
        assertEquals(100L, deeplyConfirmed.createdAtEpochMillis)
        assertEquals(io.riverark.ferret.core.model.TransactionState.CONFIRMED, confirmedAt2159.history(source.id).single().state)

        val settledAt2160 = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, operationId ->
            lookups++
            L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "settled", 2_160)
        },
        engine, { error("operation id must remain stable") }, { ++clock },)
        val settled = settledAt2160.reconcilePending(source.id)!!
        assertEquals(L1OperationState.SETTLED, settled.state)
        assertEquals(OPERATION_ID, settled.operationId)
        assertEquals(TRANSACTION_ID, settled.expectedTransactionId)
        assertEquals(destination.id, settled.destinationWalletId)
        assertEquals(ada(5_000_000), settled.amount)
        assertEquals(ada(200_000), settled.fee)
        assertEquals(100L, settled.createdAtEpochMillis)
        val history = settledAt2160.history(source.id).single()
        assertEquals(TRANSACTION_ID, history.id)
        assertEquals(100L, history.timestampEpochMillis)
        assertEquals(listOf(ada(5_000_000)), history.amounts)
        assertEquals(ada(200_000), history.fee)
        assertEquals(io.riverark.ferret.core.model.TransactionState.SETTLED, history.state)

        val terminal = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, _ -> error("settled operation must not be looked up") },
        engine, { error("operation id must remain stable") }, { ++clock },)
        assertEquals(L1OperationState.SETTLED, terminal.reconcilePending(source.id)?.state)
        assertEquals(1, submissions)
        assertEquals(4, lookups)
    }

    @Test fun confirmedOperationContinuesReconcilingUntilSettlement() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
        { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
        engine,
        { OPERATION_ID },
        { 123L },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertEquals(L1OperationState.CONFIRMED, repository.reconcilePending(source.id)?.state)

        val restarted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger must not reload during reconciliation") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("a mutation must not be retried") },
        { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "settled", 2_160) },
        engine, { error("operation id must remain stable") }, { 999L },)
        assertEquals(L1OperationState.SETTLED, restarted.reconcilePending(source.id)?.state)
    }

    @Test fun confirmedOperationCanRollBackAndBeConfirmedAgainWithoutResubmission() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        var submissions = 0
        var lookup = L1OperationDto(OPERATION_ID, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5)
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request ->
            submissions++
            L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
        },
        { _, _ -> lookup },
        engine,
        { OPERATION_ID },
        { 123L },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertEquals(L1OperationState.CONFIRMED, repository.reconcilePending(source.id)?.state)

        val confirmed = repository.operation(source.id)!!
        for (rolledBack in listOf(
            L1OperationDto(OPERATION_ID, TRANSACTION_ID, TRANSACTION_ID, "accepted", 4),
            L1OperationDto(OPERATION_ID, TRANSACTION_ID, status = "pending", depth = 0),
        )) {
            lookup = rolledBack
            assertEquals(confirmed.copy(state = L1OperationState.PENDING), repository.reconcilePending(source.id))
            lookup = L1OperationDto(OPERATION_ID, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5)
            assertEquals(confirmed, repository.reconcilePending(source.id))
        }
        lookup = L1OperationDto(OPERATION_ID, TRANSACTION_ID, status = "rejected", depth = 0)
        val rejected = repository.reconcilePending(source.id)
        assertEquals(confirmed.copy(state = L1OperationState.REJECTED), rejected)
        lookup = L1OperationDto(OPERATION_ID, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5)
        assertEquals(rejected, repository.reconcilePending(source.id))
        assertEquals(1, submissions)
    }

    @Test fun submissionRejectsWrongOperationIdentityWithoutOverwritingDurableRecord() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request -> L1OperationDto(NEXT_OPERATION_ID, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
        { _, _ -> error("lookup not used") },
        engine,
        { OPERATION_ID },
        { 123L },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }

        val restarted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger not used") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("submission not used") },
        { _, _ -> error("lookup not used") },
        engine, { error("operation id not used") }, { 999L },)
        val durable = restarted.operation(source.id)!!
        assertEquals(OPERATION_ID, durable.operationId)
        assertEquals(TRANSACTION_ID, durable.expectedTransactionId)
        assertEquals(L1OperationState.SUBMITTING, durable.state)
    }

    @Test fun submissionRejectsWrongExpectedHashWithoutOverwritingDurableRecord() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request -> L1OperationDto(request.operationId, OTHER_TRANSACTION_ID, OTHER_TRANSACTION_ID, "accepted", 0) },
        { _, _ -> error("lookup not used") },
        engine,
        { OPERATION_ID },
        { 123L },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }

        val restarted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger not used") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("submission not used") },
        { _, _ -> error("lookup not used") },
        engine, { error("operation id not used") }, { 999L },)
        val durable = restarted.operation(source.id)!!
        assertEquals(OPERATION_ID, durable.operationId)
        assertEquals(TRANSACTION_ID, durable.expectedTransactionId)
        assertEquals(L1OperationState.SUBMITTING, durable.state)
    }

    @Test fun reconciliationRejectsWrongOperationIdentityWithoutOverwritingDurableRecord() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
        { _, _ -> L1OperationDto(NEXT_OPERATION_ID, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
        engine,
        { OPERATION_ID },
        { 123L },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertFailsWith<IllegalArgumentException> { repository.reconcilePending(source.id) }

        val restarted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger not used") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("submission not used") },
        { _, _ -> error("lookup not used") },
        engine, { error("operation id not used") }, { 999L },)
        val durable = restarted.operation(source.id)!!
        assertEquals(OPERATION_ID, durable.operationId)
        assertEquals(TRANSACTION_ID, durable.expectedTransactionId)
        assertEquals(L1OperationState.PENDING, durable.state)
    }

    @Test fun reconciliationRejectsWrongExpectedHashWithoutOverwritingDurableRecord() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
        { _, operationId -> L1OperationDto(operationId, OTHER_TRANSACTION_ID, OTHER_TRANSACTION_ID, "confirmed", 5) },
        engine,
        { OPERATION_ID },
        { 123L },)
        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertFailsWith<IllegalArgumentException> { repository.reconcilePending(source.id) }

        val restarted = DefaultL1WalletRepository(wallets, vault, catalog, { error("ledger not used") },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("submission not used") },
        { _, _ -> error("lookup not used") },
        engine, { error("operation id not used") }, { 999L },)
        val durable = restarted.operation(source.id)!!
        assertEquals(OPERATION_ID, durable.operationId)
        assertEquals(TRANSACTION_ID, durable.expectedTransactionId)
        assertEquals(L1OperationState.PENDING, durable.state)
    }

    @Test fun abandonsPreparedOperationWhenSigningNeverCompleted() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val repository = DefaultL1WalletRepository(wallets, vault, catalog, { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
        { emptyList<TransactionRecord>() },
        { _, _ -> error("submission must not start") },
        { _, _ -> error("lookup must not run") },
        FakeEngine(failSigning = true),
        { OPERATION_ID },
        { 123L },)

        val preview = repository.previewTransfer(source.id, TransferDestination(destination.name, destination.paymentAddress), ada(5_000_000))
        assertFailsWith<IllegalStateException> { repository.submitTransfer(source.id, preview) }
        assertEquals(L1OperationState.PREPARED, repository.operation(source.id)?.state)
        assertEquals(L1OperationState.REJECTED, repository.reconcilePending(source.id)?.state)
    }

    @Test fun legacyL1ValuesMigrateExactlyAndPreserveChannelsBlob() = runBlocking {
        val source = profile('0')
        val vault = FakeVault(listOf(source))
        val legacyRecord =
            """{"operationId":"$OPERATION_ID","amount":5000000,"fee":200000,"createdAtEpochMillis":123,"state":"SETTLED"}"""
        val legacyL1 = """{"records":[$legacyRecord]}""".encodeToByteArray()
        val legacyEnvelope =
            """{"l1":${Json.encodeToString(legacyL1)},"channel":[1,2,3],"payment":[]}""".encodeToByteArray()
        vault.install(source.id, WalletEncryptedStateV1(operationJournal = legacyEnvelope))
        val repository = DefaultL1WalletRepository(
            WalletRepository(),
            vault,
            catalog,
            { error("ledger not used") },
            { emptyList() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            FakeEngine(),
            { error("operation id not used") },
            { 0L },
        )

        val migrated = repository.reconcilePending(source.id)!!

        assertEquals(ada(5_000_000), migrated.amount)
        assertEquals(ada(200_000), migrated.fee)
        assertEquals(123L, migrated.createdAtEpochMillis)
        val envelope = Json.decodeFromString<WalletOperationJournalV2>(
            vault.walletState(source.id).operationJournal.decodeToString(),
        )
        assertEquals(2, envelope.schema)
        assertEquals(listOf<Byte>(1, 2, 3), envelope.channels.toList())
    }

    private enum class SignedWitnessMode { VALID, MISSING, WRONG, DUPLICATE, INVALID }

    private class RemoteCalls(private val allow: Boolean = false) {
        var submissions = 0
        var lookups = 0

        fun submit(operationId: String, transactionId: String): L1OperationDto {
            submissions++
            check(allow)
            return L1OperationDto(operationId, transactionId, transactionId, "accepted", 0)
        }

        fun lookup(operationId: String): L1OperationDto {
            lookups++
            check(allow)
            return L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5)
        }
    }

    private class FakeEngine(private val failSigning: Boolean = false) : CardanoTransactionEngine {
        private lateinit var intent: CardanoIntent
        private lateinit var selectedInput: LedgerUtxo
        var builds = 0
        var signs = 0
        var changeSignedBody = false
        var inputOverride: List<TransactionInputReference>? = null
        var includeUnsignedWitness = false
        var signedWitnessMode = SignedWitnessMode.VALID
        var minimumOutputLovelace = 0L
        var fee = 200_000L
        var rejectSignedMinimum = false
        override suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet = error("not used")
        override suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction {
            builds++
            this.intent = intent
            selectedInput = ledger.utxos.first()
            return UnsignedTransaction(byteArrayOf(0), intent.operationId, Lovelace(fee))
        }
        override suspend fun buildSweep(
            intent: CardanoIntent.SweepWallet,
            ledger: LedgerSnapshot,
        ): UnsignedTransaction = build(
            intent.copy(amount = Lovelace(ledger.utxos.sumOf { it.lovelace.value } - fee)),
            ledger,
        )
        override fun requireMinimumAda(cbor: ByteArray, protocolParametersJson: String) {
            require(!(rejectSignedMinimum && cbor.size > 1))
            val minimum = protocolParametersJson.toLongOrNull() ?: minimumOutputLovelace
            require(inspect(cbor).outputs.all { it.lovelace.value >= minimum })
        }
        override fun minimumAdaForOutput(
            cbor: ByteArray,
            protocolParametersJson: String,
            outputIndex: Int,
        ) = Lovelace(protocolParametersJson.toLongOrNull() ?: minimumOutputLovelace)
        override fun decodeChannelDatum(cborHex: String): io.riverark.ferret.core.cardano.ChannelDatum =
            error("not used")
        override fun requireAuthorized(unsigned: UnsignedTransaction, intent: CardanoIntent, ledger: LedgerSnapshot) {
            require(unsigned.operationId == intent.operationId)
            inspect(unsigned.cbor).also {
                it.requireMatches(intent, ledger.network, unsigned.feeBound)
                it.requireL1Funding(intent, ledger)
                it.requireL1Witnesses(intent.sourceAddress.substringAfterLast('_').repeat(56), signed = false)
            }
            requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)
        }
        override fun sign(
            unsigned: UnsignedTransaction,
            seed: ByteArray,
            intent: CardanoIntent,
            ledger: LedgerSnapshot,
        ): SignedTransaction {
            requireAuthorized(unsigned, intent, ledger)
            signs++
            check(!failSigning)
            return SignedTransaction(byteArrayOf(if (changeSignedBody) 9 else 0, 2, 3)).also { signed ->
                inspect(signed.cbor).also {
                    it.requireMatches(intent, ledger.network, unsigned.feeBound)
                    it.requireL1Funding(intent, ledger)
                    it.requireL1Witnesses(intent.sourceAddress.substringAfterLast('_').repeat(56), signed = true)
                }
                requireMinimumAda(signed.cbor, ledger.protocolParametersJson)
            }
        }
        override fun inspect(signedCbor: ByteArray): TransactionSummary {
            val destination = when (val value = intent) {
                is CardanoIntent.Transfer -> value.destinationAddress
                is CardanoIntent.SweepWallet -> value.destinationAddress
                else -> error("unsupported intent")
            }
            val change = selectedInput.lovelace.value - intent.amount.value - fee
            val credential = intent.sourceAddress.substringAfterLast('_').repeat(56)
            val witness = TransactionKeyWitness("", credential, "", true)
            val witnesses = if (signedCbor.size == 1) {
                if (includeUnsignedWitness) listOf(witness) else emptyList()
            } else {
                when (signedWitnessMode) {
                    SignedWitnessMode.VALID -> listOf(witness)
                    SignedWitnessMode.MISSING -> emptyList()
                    SignedWitnessMode.WRONG -> listOf(witness.copy(keyHashHex = "f".repeat(56)))
                    SignedWitnessMode.DUPLICATE -> listOf(witness, witness)
                    SignedWitnessMode.INVALID -> listOf(witness.copy(signatureValid = false))
                }
            }
            return TransactionSummary(
                CardanoNetwork.PREPROD,
                listOfNotNull(
                    TransactionOutputSummary(destination, intent.amount, emptyMap()),
                    change.takeIf { it > 0 }?.let { TransactionOutputSummary(intent.sourceAddress, Lovelace(it), emptyMap()) },
                ),
                Lovelace(fee),
                emptySet(),
                intent.validFrom,
                intent.validUntil,
                inputOverride ?: listOf(TransactionInputReference(selectedInput.transactionId, selectedInput.index)),
                keyWitnesses = witnesses,
            )
        }
        override fun transactionId(signedCbor: ByteArray) = if (signedCbor[0] == 0.toByte()) TRANSACTION_ID else OTHER_TRANSACTION_ID
    }

    private class FakeVault(var storedProfiles: List<WalletProfile>) : SecureVault {
        var seedRequests = 0
        var writes = 0
        private val states = storedProfiles.associate { it.id to WalletEncryptedStateV1() }.toMutableMap()
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
        override suspend fun walletState(walletId: WalletId): WalletEncryptedStateV1 = states.getValue(walletId).copy(
            channelRecovery = states.getValue(walletId).channelRecovery.copyOf(),
            operationJournal = states.getValue(walletId).operationJournal.copyOf(),
        )
        fun install(walletId: WalletId, state: WalletEncryptedStateV1) {
            states[walletId] = state
        }
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            writes++
            states[walletId] = state.copy(
                channelRecovery = state.channelRecovery.copyOf(),
                operationJournal = state.operationJournal.copyOf(),
            )
        }
    }

    private fun profile(digit: Char) = WalletProfile(
        WalletId("preprod-" + digit.toString().repeat(56)),
        "Wallet $digit",
        CardanoNetwork.PREPROD,
        "addr_test1_$digit",
        "stake_test1_$digit",
    )

    private fun testCatalog(): AssetCatalog {
        val digest = "a".repeat(64)
        return AssetCatalog(
            listOf(
                ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest),
                ChannelAsset("usda", "1".repeat(56), "", 6, AssetPricing.USD_PEG, digest),
                ChannelAsset("usdcx", "2".repeat(56), "", 6, AssetPricing.USD_PEG, digest),
                ChannelAsset("usdm", "3".repeat(56), "", 6, AssetPricing.USD_PEG, digest),
            ),
            digest,
            emptyMap(),
        )
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000001"
        const val NEXT_OPERATION_ID = "00000000-0000-4000-8000-000000000002"
        val TRANSACTION_ID = "11".repeat(32)
        val OTHER_TRANSACTION_ID = "22".repeat(32)
    }
}
