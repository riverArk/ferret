package io.riverark.ferret

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.DerivedWallet
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.SignedTransaction
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.TransactionSummary
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.L1OperationDto
import io.riverark.ferret.core.network.ConnectorUtxoDto
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import io.riverark.ferret.feature.wallet.DefaultL1WalletRepository
import io.riverark.ferret.feature.wallet.L1OperationState
import io.riverark.ferret.feature.wallet.parseAdaAmount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith


class L1WalletRepositoryTest {
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
            LedgerUtxo("33".repeat(32), 0, source.paymentAddress, Lovelace(20_000_000), scriptRefHex = "bb".repeat(28)),
            LedgerUtxo("44".repeat(32), 0, source.paymentAddress, Lovelace(20_000_000), mapOf("cc".repeat(28) to 0)),
            LedgerUtxo("55".repeat(32), 0, "addr1foreign", Lovelace(20_000_000)),
        )
        var ledger = LedgerSnapshot(CardanoNetwork.MAINNET, listOf(plain) + excluded, "{}", 100)
        val repository = DefaultL1WalletRepository(
            wallets, vault, { ledger }, { emptyList() }, { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") }, FakeEngine(), { OPERATION_ID }, { 123L },
        )

        assertEquals(Lovelace(10_000_000), repository.balance(source.id).spendable)
        ledger = ledger.copy(utxos = excluded)
        assertEquals(Lovelace(0), repository.balance(source.id).spendable)
    }
    @Test fun previewUsesSelectedInputsAndAllowsExactSpend() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        assertEquals(Lovelace(4_800_000), repository.previewTransfer(source.id, destination, Lovelace(5_000_000)).change)
        engine.change = null
        assertEquals(Lovelace(0), repository.previewTransfer(source.id, destination, Lovelace(5_000_000)).change)
    }

    @Test fun previewRejectsDestinationsOutsideVaultIdentity() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        for (invalid in listOf(profile('2'), source, destination.copy(network = CardanoNetwork.MAINNET),
            destination.copy(paymentAddress = "addr_test1forged"))) {
            assertFailsWith<RuntimeException> { repository.previewTransfer(source.id, invalid, Lovelace(5_000_000)) }
        }
        vault.storedProfiles = listOf(source, destination.copy(network = CardanoNetwork.MAINNET))
        assertFailsWith<IllegalArgumentException> { repository.previewTransfer(source.id, destination, Lovelace(5_000_000)) }
        vault.storedProfiles = listOf(source, destination.copy(paymentAddress = source.paymentAddress))
        assertFailsWith<IllegalArgumentException> {
            repository.previewTransfer(source.id, vault.storedProfiles.last(), Lovelace(5_000_000))
        }
        assertEquals(0, engine.builds)
        assertEquals(0, vault.seedRequests)
        assertNull(repository.operation(source.id))
    }

    @Test fun submissionRejectsAlteredPreviewBeforeAnySideEffect() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        for (altered in listOf(
            preview.copy(intent = preview.intent!!.copy(sourceAddress = "addr_test1forged")),
            preview.copy(feeBound = Lovelace(300_000)),
            preview.copy(change = Lovelace(94_800_000)),
            preview.copy(amount = Lovelace(0)),
            preview.copy(transactionId = null),
            preview.copy(unsigned = preview.unsigned!!.copy(operationId = NEXT_OPERATION_ID)),
            preview.copy(destination = destination.copy(paymentAddress = "addr_test1forged")),
            preview.copy(destination = source),
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

    @Test fun submissionRevalidatesCurrentVaultMembershipAndAddresses() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        val repository = previewRepository(vault, engine)
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        for (profiles in listOf(
            listOf(source),
            listOf(source, destination.copy(network = CardanoNetwork.MAINNET)),
            listOf(source, destination.copy(paymentAddress = "addr_test1changed")),
            listOf(source.copy(paymentAddress = "addr_test1changed"), destination),
        )) {
            vault.storedProfiles = profiles
            assertFailsWith<RuntimeException> { repository.submitTransfer(source.id, preview) }
            assertEquals(0, vault.writes)
            assertEquals(0, vault.seedRequests)
            assertEquals(0, engine.signs)
        }
    }

    @Test fun signedBodyMismatchRemainsPreparedAndRejectsAfterRestartWithoutLookup() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine().apply { changeSignedBody = true }
        val repository = previewRepository(vault, engine)
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }
        assertEquals(L1OperationState.PREPARED, repository.operation(source.id)?.state)
        assertNull(repository.operation(source.id)?.expectedTransactionId)
        val restarted = previewRepository(vault, engine)
        assertEquals(L1OperationState.REJECTED, restarted.reconcilePending(source.id)?.state)
        assertNull(restarted.operation(source.id)?.expectedTransactionId)
    }

    private fun previewRepository(vault: FakeVault, engine: FakeEngine) = DefaultL1WalletRepository(
        WalletRepository(), vault,
        { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, it.paymentAddress, Lovelace(100_000_000))), "{}", 100) },
        { emptyList() },
        { _, _ -> error("submission must not start") },
        { _, _ -> error("lookup must not run") },
        engine, { OPERATION_ID }, { 123L },
    )

    @Test fun rejectsDuplicateInFlightButAllowsNewTransferAfterConfirmation() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        var submissions = 0
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request ->
                submissions++
                L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
            },
            { _, _ -> error("lookup not used") },
            engine,
            { OPERATION_ID },
            { 123L },
        )

        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        assertEquals(Lovelace(200_000), preview.feeBound)
        assertEquals(Lovelace(4_800_000), preview.change)
        assertEquals(OPERATION_ID, repository.submitTransfer(source.id, preview))
        assertEquals(L1OperationState.PENDING, repository.operation(source.id)?.state)
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }
        assertEquals(1, submissions)

        val restarted = DefaultL1WalletRepository(
            wallets,
            vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
            engine,
            { error("operation id must remain stable") },
            { 999L },
        )
        assertEquals(L1OperationState.CONFIRMED, restarted.reconcilePending(source.id)?.state)

        val next = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 200) },
            { emptyList<TransactionRecord>() },
            { _, request ->
                submissions++
                L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
            },
            { _, _ -> error("lookup not used") },
            engine,
            { NEXT_OPERATION_ID },
            { 1_000L },
        )
        val nextPreview = next.previewTransfer(source.id, destination, Lovelace(4_000_000))
        assertEquals(NEXT_OPERATION_ID, next.submitTransfer(source.id, nextPreview))
        assertEquals(2, submissions)
    }

    @Test fun successfulSubmissionKeepsPreparationTimeAndLocalMetadata() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        var clock = 99L
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
            { _, _ -> error("lookup not used") },
            FakeEngine(),
            { OPERATION_ID },
            { ++clock },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        repository.submitTransfer(source.id, preview)

        val accepted = repository.operation(source.id)!!
        assertEquals(OPERATION_ID, accepted.operationId)
        assertEquals(TRANSACTION_ID, accepted.expectedTransactionId)
        assertEquals(destination.id, accepted.destinationWalletId)
        assertEquals(Lovelace(5_000_000), accepted.amount)
        assertEquals(Lovelace(200_000), accepted.fee)
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request ->
                submissions++
                remote = L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
                error("response lost after acceptance")
            },
            { _, _ -> error("lookup not used before restart") },
            engine,
            { OPERATION_ID },
            { ++clock },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))

        assertFailsWith<IllegalStateException> { repository.submitTransfer(source.id, preview) }
        val submitting = repository.operation(source.id)!!
        assertEquals(L1OperationState.SUBMITTING, submitting.state)
        assertEquals(OPERATION_ID, submitting.operationId)
        assertEquals(TRANSACTION_ID, submitting.expectedTransactionId)
        assertEquals(destination.id, submitting.destinationWalletId)
        assertEquals(Lovelace(5_000_000), submitting.amount)
        assertEquals(Lovelace(200_000), submitting.fee)
        assertEquals(100L, submitting.createdAtEpochMillis)

        val accepted = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, _ ->
                lookups++
                requireNotNull(remote).copy(depth = 4)
            },
            engine, { error("operation id must remain stable") }, { ++clock },
        )
        val pending = accepted.reconcilePending(source.id)!!
        assertEquals(L1OperationState.PENDING, pending.state)
        assertEquals(100L, pending.createdAtEpochMillis)
        assertEquals(io.riverark.ferret.core.model.TransactionState.PENDING, accepted.history(source.id).single().state)

        val confirmedAtFive = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, operationId ->
                lookups++
                L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5)
            },
            engine, { error("operation id must remain stable") }, { ++clock },
        )
        assertEquals(L1OperationState.CONFIRMED, confirmedAtFive.reconcilePending(source.id)?.state)
        assertEquals(io.riverark.ferret.core.model.TransactionState.CONFIRMED, confirmedAtFive.history(source.id).single().state)

        val confirmedAt2159 = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, operationId ->
                lookups++
                L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 2_159)
            },
            engine, { error("operation id must remain stable") }, { ++clock },
        )
        val deeplyConfirmed = confirmedAt2159.reconcilePending(source.id)!!
        assertEquals(L1OperationState.CONFIRMED, deeplyConfirmed.state)
        assertEquals(100L, deeplyConfirmed.createdAtEpochMillis)
        assertEquals(io.riverark.ferret.core.model.TransactionState.CONFIRMED, confirmedAt2159.history(source.id).single().state)

        val settledAt2160 = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, operationId ->
                lookups++
                L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "settled", 2_160)
            },
            engine, { error("operation id must remain stable") }, { ++clock },
        )
        val settled = settledAt2160.reconcilePending(source.id)!!
        assertEquals(L1OperationState.SETTLED, settled.state)
        assertEquals(OPERATION_ID, settled.operationId)
        assertEquals(TRANSACTION_ID, settled.expectedTransactionId)
        assertEquals(destination.id, settled.destinationWalletId)
        assertEquals(Lovelace(5_000_000), settled.amount)
        assertEquals(Lovelace(200_000), settled.fee)
        assertEquals(100L, settled.createdAtEpochMillis)
        val history = settledAt2160.history(source.id).single()
        assertEquals(TRANSACTION_ID, history.id)
        assertEquals(100L, history.timestampEpochMillis)
        assertEquals(Lovelace(5_000_000), history.amount)
        assertEquals(Lovelace(200_000), history.fee)
        assertEquals(io.riverark.ferret.core.model.TransactionState.SETTLED, history.state)

        val terminal = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, _ -> error("settled operation must not be looked up") },
            engine, { error("operation id must remain stable") }, { ++clock },
        )
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
            { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
            engine,
            { OPERATION_ID },
            { 123L },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertEquals(L1OperationState.CONFIRMED, repository.reconcilePending(source.id)?.state)

        val restarted = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "settled", 2_160) },
            engine, { error("operation id must remain stable") }, { 999L },
        )
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request ->
                submissions++
                L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0)
            },
            { _, _ -> lookup },
            engine,
            { OPERATION_ID },
            { 123L },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request -> L1OperationDto(NEXT_OPERATION_ID, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
            { _, _ -> error("lookup not used") },
            engine,
            { OPERATION_ID },
            { 123L },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }

        val restarted = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger not used") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            engine, { error("operation id not used") }, { 999L },
        )
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request -> L1OperationDto(request.operationId, OTHER_TRANSACTION_ID, OTHER_TRANSACTION_ID, "accepted", 0) },
            { _, _ -> error("lookup not used") },
            engine,
            { OPERATION_ID },
            { 123L },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        assertFailsWith<IllegalArgumentException> { repository.submitTransfer(source.id, preview) }

        val restarted = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger not used") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            engine, { error("operation id not used") }, { 999L },
        )
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
            { _, _ -> L1OperationDto(NEXT_OPERATION_ID, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
            engine,
            { OPERATION_ID },
            { 123L },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertFailsWith<IllegalArgumentException> { repository.reconcilePending(source.id) }

        val restarted = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger not used") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            engine, { error("operation id not used") }, { 999L },
        )
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request -> L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "accepted", 0) },
            { _, operationId -> L1OperationDto(operationId, OTHER_TRANSACTION_ID, OTHER_TRANSACTION_ID, "confirmed", 5) },
            engine,
            { OPERATION_ID },
            { 123L },
        )
        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        repository.submitTransfer(source.id, preview)
        assertFailsWith<IllegalArgumentException> { repository.reconcilePending(source.id) }

        val restarted = DefaultL1WalletRepository(
            wallets, vault,
            { error("ledger not used") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("submission not used") },
            { _, _ -> error("lookup not used") },
            engine, { error("operation id not used") }, { 999L },
        )
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
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("submission must not start") },
            { _, _ -> error("lookup must not run") },
            FakeEngine(failSigning = true),
            { OPERATION_ID },
            { 123L },
        )

        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        assertFailsWith<IllegalStateException> { repository.submitTransfer(source.id, preview) }
        assertEquals(L1OperationState.PREPARED, repository.operation(source.id)?.state)
        assertEquals(L1OperationState.REJECTED, repository.reconcilePending(source.id)?.state)
    }

    @Test fun parsesOnlyPositiveAdaWithAtMostSixDecimals() {
        assertEquals(Lovelace(1_230_000), parseAdaAmount("1.23"))
        assertEquals(Lovelace(1), parseAdaAmount("0.000001"))
        assertNull(parseAdaAmount("0"))
        assertNull(parseAdaAmount("1.0000001"))
        assertNull(parseAdaAmount("-1"))
    }

    private class FakeEngine(private val failSigning: Boolean = false) : CardanoTransactionEngine {
        private lateinit var intent: CardanoIntent.Transfer
        var change: Lovelace? = Lovelace(4_800_000)
        var builds = 0
        var signs = 0
        var changeSignedBody = false
        override suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet = error("not used")
        override suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction {
            builds++
            this.intent = intent as CardanoIntent.Transfer
            return UnsignedTransaction(byteArrayOf(0), intent.operationId, Lovelace(200_000))
        }
        override fun sign(unsigned: UnsignedTransaction, seed: ByteArray): SignedTransaction {
            signs++
            check(!failSigning)
            return SignedTransaction(byteArrayOf(if (changeSignedBody) 9 else 0, 2, 3))
        }
        override fun inspect(signedCbor: ByteArray) = TransactionSummary(
            CardanoNetwork.PREPROD,
            listOfNotNull(
                TransactionOutputSummary(intent.destinationAddress, intent.amount, emptyMap()),
                change?.let { TransactionOutputSummary(intent.sourceAddress, it, emptyMap()) },
            ),
            Lovelace(200_000),
            emptySet(),
            intent.validFrom,
            intent.validUntil,
        )
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

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000001"
        const val NEXT_OPERATION_ID = "00000000-0000-4000-8000-000000000002"
        val TRANSACTION_ID = "11".repeat(32)
        val OTHER_TRANSACTION_ID = "22".repeat(32)
    }
}
