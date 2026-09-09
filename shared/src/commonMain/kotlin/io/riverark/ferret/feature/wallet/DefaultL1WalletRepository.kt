package io.riverark.ferret.feature.wallet

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.requireMatches
import io.riverark.ferret.core.cardano.SweepPreview
import io.riverark.ferret.core.cardano.requireL1Funding
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.ConnectorClient
import io.riverark.ferret.core.network.L1OperationDto
import io.riverark.ferret.core.network.L1SubmitRequest
import io.riverark.ferret.core.security.WalletOperationJournalV1
import io.riverark.ferret.core.security.SecureVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class L1OperationState { PREPARED, SUBMITTING, PENDING, CONFIRMED, SETTLED, REJECTED }

@Serializable
data class L1OperationRecord(
    val operationId: String,
    val expectedTransactionId: String? = null,
    val destinationWalletId: WalletId? = null,
    val destinationAddress: String? = null,
    val amount: Lovelace,
    val fee: Lovelace,
    val createdAtEpochMillis: Long,
    val state: L1OperationState,
)
@Serializable
private data class L1OperationsV1(val records: List<L1OperationRecord>)

class DefaultL1WalletRepository(
    private val wallets: WalletRepository,
    private val vault: SecureVault,
    private val loadLedger: suspend (WalletProfile) -> LedgerSnapshot,
    private val loadTransactions: suspend (WalletProfile) -> List<TransactionRecord>,
    private val submitOperation: suspend (WalletProfile, L1SubmitRequest) -> L1OperationDto,
    private val lookupOperation: suspend (WalletProfile, String) -> L1OperationDto,
    private val engine: CardanoTransactionEngine,
    private val newOperationId: () -> String,
    private val nowEpochMillis: () -> Long,
    private val hasPendingChannel: suspend (WalletId) -> Boolean = { false },
) : L1WalletRepository {
    constructor(
        wallets: WalletRepository,
        vault: SecureVault,
        connector: (WalletProfile) -> ConnectorClient,
        engine: CardanoTransactionEngine,
        newOperationId: () -> String,
        nowEpochMillis: () -> Long,
        hasPendingChannel: suspend (WalletId) -> Boolean = { false },
    ) : this(
        wallets,
        vault,
        { profile -> connector(profile).ledger(profile.paymentAddress, profile.network) },
        { profile -> connector(profile).transactions(profile.paymentAddress) },
        { profile, request -> connector(profile).submitL1(request) },
        { profile, operationId -> connector(profile).operation(operationId) },
        engine,
        newOperationId,
        nowEpochMillis,
        hasPendingChannel = hasPendingChannel,
    )
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun balance(walletId: WalletId): WalletBalance {
        val profile = profile(walletId)
        val ledger = loadLedger(profile)
        val ledgerSpendable = ledger.utxos
            .filter { it.isSpendableBy(profile.paymentAddress) }
            .fold(Lovelace(0)) { total, utxo -> total + utxo.lovelace }
        val pending = operations(walletId)
            .filter { it.state in setOf(L1OperationState.PREPARED, L1OperationState.SUBMITTING, L1OperationState.PENDING) }
            .fold(Lovelace(0)) { total, operation -> total + operation.amount + operation.fee }
        return WalletBalance(
            Lovelace((ledgerSpendable.value - pending.value).coerceAtLeast(0)),
            pending,
        )
    }

    suspend fun hasNativeAssets(walletId: WalletId): Boolean {
        val profile = profile(walletId)
        return loadLedger(profile).utxos.any { it.address == profile.paymentAddress && it.assets.isNotEmpty() }
    }
    override suspend fun history(walletId: WalletId): List<TransactionRecord> {
        val profile = profile(walletId)
        val remote = loadTransactions(profile)
        val local = operations(walletId)
        return (remote + local
            .filterNot { operation -> remote.any { it.id == (operation.expectedTransactionId ?: operation.operationId) } }
            .map { operation ->
                TransactionRecord(
                    operation.expectedTransactionId ?: operation.operationId,
                    operation.createdAtEpochMillis,
                    operation.amount,
                    operation.fee,
                    io.riverark.ferret.core.model.Realm.L1,
                    when (operation.state) {
                        L1OperationState.CONFIRMED -> io.riverark.ferret.core.model.TransactionState.CONFIRMED
                        L1OperationState.SETTLED -> io.riverark.ferret.core.model.TransactionState.SETTLED
                        L1OperationState.REJECTED -> io.riverark.ferret.core.model.TransactionState.FAILED
                        else -> io.riverark.ferret.core.model.TransactionState.PENDING
                    },
                )
            }).sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
    }

    override suspend fun previewTransfer(walletId: WalletId, destination: WalletProfile, amount: Lovelace): TransferPreview =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            val resolvedDestination = transferDestination(profile, destination)
            require(amount.value > 0)
            val ledger = loadLedger(profile)
            require(ledger.currentSlot <= Long.MAX_VALUE - TRANSFER_VALIDITY_SLOTS)
            val intent = CardanoIntent.Transfer(
                profile.paymentAddress,
                resolvedDestination.paymentAddress,
                amount,
                newOperationId(),
                ledger.currentSlot,
                ledger.currentSlot + TRANSFER_VALIDITY_SLOTS,
            )
            val unsigned = engine.build(intent, ledger)
            require(unsigned.operationId == intent.operationId)
            val summary = engine.inspect(unsigned.cbor)
            summary.requireMatches(intent, profile.network, unsigned.feeBound)
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            summary.requireL1Funding(intent, ledger)
            engine.requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)
            val change = summary.outputs.singleOrNull { it.address == profile.paymentAddress }?.lovelace ?: Lovelace(0)
            TransferPreview(resolvedDestination, amount, unsigned.feeBound, change, intent, unsigned, engine.transactionId(unsigned.cbor))
        }

    override suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview): String =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            val destination = transferDestination(profile, preview.destination)
            val intent = requireNotNull(preview.intent)
            val unsigned = requireNotNull(preview.unsigned)
            val previewTransactionId = requireNotNull(preview.transactionId)
            require(intent.sourceAddress == profile.paymentAddress)
            require(intent.operationId == unsigned.operationId)
            require(intent.destinationAddress == destination.paymentAddress)
            require(intent.amount == preview.amount && preview.amount.value > 0)
            require(preview.feeBound == unsigned.feeBound)
            val summary = engine.inspect(unsigned.cbor)
            summary.requireMatches(intent, profile.network, preview.feeBound)
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            require((summary.outputs.singleOrNull { it.address == profile.paymentAddress }?.lovelace ?: Lovelace(0)) == preview.change)
            require(engine.transactionId(unsigned.cbor) == previewTransactionId)
            val submitLedger = loadLedger(profile)
            require(submitLedger.network == profile.network)
            engine.requireAuthorized(unsigned, intent, submitLedger)
            val existing = operations(walletId)
            require(existing.none { it.state in UNRESOLVED_STATES }) { "another wallet operation is unresolved" }
            require(!hasPendingChannel(walletId)) { "channel operation is unresolved" }
            val prepared = L1OperationRecord(
                intent.operationId,
                destinationWalletId = preview.destination.id,
                amount = preview.amount,
                fee = preview.feeBound,
                createdAtEpochMillis = nowEpochMillis(),
                state = L1OperationState.PREPARED,
            )
            writeOperation(walletId, prepared)
            val signed = vault.withWalletSeed(walletId) { engine.sign(unsigned, it, intent, submitLedger) }
            try {
                val transactionId = engine.transactionId(signed.cbor)
                require(transactionId == previewTransactionId)
                val submitting = prepared.copy(
                    expectedTransactionId = transactionId,
                    state = L1OperationState.SUBMITTING,
                )
                writeOperation(walletId, submitting)
                val remote = submitOperation(profile, L1SubmitRequest(intent.operationId, transactionId, signed.cbor.hex()))
                writeOperation(walletId, submitting.withRemote(remote))
                intent.operationId
            } finally {
                signed.cbor.fill(0)
            }
        }

    override suspend fun previewSweep(walletId: WalletId, destinationAddress: String): SweepPreview =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(profile.network.accepts(destinationAddress) && destinationAddress != profile.paymentAddress)
            val ledger = loadLedger(profile)
            val owned = ledger.utxos.filter { it.address == profile.paymentAddress }
            require(owned.none { it.assets.isNotEmpty() }) { "move native assets before sweeping" }
            val total = owned
                .filter { it.isSpendableBy(profile.paymentAddress) }
                .fold(Lovelace(0)) { sum, utxo -> sum + utxo.lovelace }
            require(total.value > INITIAL_SWEEP_FEE)
            val operationId = newOperationId()
            var amount = Lovelace(total.value - INITIAL_SWEEP_FEE)
            repeat(MAX_SWEEP_PASSES) {
                val intent = CardanoIntent.SweepWallet(
                    profile.paymentAddress,
                    destinationAddress,
                    amount,
                    operationId,
                    ledger.currentSlot,
                    ledger.currentSlot + TRANSFER_VALIDITY_SLOTS,
                )
                val unsigned = engine.build(intent, ledger)
                val summary = engine.inspect(unsigned.cbor)
                summary.requireMatches(intent, profile.network, unsigned.feeBound)
                summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
                summary.requireL1Funding(intent, ledger)
                engine.requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)
                val nextAmount = total - summary.fee
                if (nextAmount == amount && summary.outputs.singleOrNull()?.address == destinationAddress) {
                    return@withWalletLock SweepPreview(
                        destinationAddress,
                        amount,
                        summary.fee,
                        intent,
                        unsigned,
                        engine.transactionId(unsigned.cbor),
                    )
                }
                amount = nextAmount
            }
            error("sweep fee did not converge")
        }

    override suspend fun submitSweep(walletId: WalletId, preview: SweepPreview): String =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(profile.network.accepts(preview.destinationAddress))
            require(preview.intent.sourceAddress == profile.paymentAddress)
            require(preview.intent.destinationAddress == preview.destinationAddress)
            require(preview.intent.amount == preview.amount && preview.intent.operationId == preview.unsigned.operationId)
            val summary = engine.inspect(preview.unsigned.cbor)
            summary.requireMatches(preview.intent, profile.network, preview.unsigned.feeBound)
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            require(summary.outputs.singleOrNull()?.let {
                it.address == preview.destinationAddress && it.lovelace == preview.amount && it.assets.isEmpty()
            } == true)
            require(summary.fee == preview.fee)
            require(engine.transactionId(preview.unsigned.cbor) == preview.expectedTransactionId)
            val submitLedger = loadLedger(profile)
            require(submitLedger.network == profile.network)
            engine.requireAuthorized(preview.unsigned, preview.intent, submitLedger)
            val existing = operations(walletId)
            require(existing.none { it.state in UNRESOLVED_STATES }) { "another wallet operation is unresolved" }
            require(!hasPendingChannel(walletId)) { "channel operation is unresolved" }
            val prepared = L1OperationRecord(
                preview.intent.operationId,
                destinationAddress = preview.destinationAddress,
                amount = preview.amount,
                fee = preview.fee,
                createdAtEpochMillis = nowEpochMillis(),
                state = L1OperationState.PREPARED,
            )
            writeOperation(walletId, prepared)
            val signed = vault.withWalletSeed(walletId) {
                engine.sign(preview.unsigned, it, preview.intent, submitLedger)
            }
            try {
                val transactionId = engine.transactionId(signed.cbor)
                require(transactionId == preview.expectedTransactionId)
                val submitting = prepared.copy(expectedTransactionId = transactionId, state = L1OperationState.SUBMITTING)
                writeOperation(walletId, submitting)
                val remote = submitOperation(
                    profile,
                    L1SubmitRequest(preview.intent.operationId, transactionId, signed.cbor.hex()),
                )
                writeOperation(walletId, submitting.withRemote(remote))
                preview.intent.operationId
            } finally {
                signed.cbor.fill(0)
            }
        }

    suspend fun reconcilePending(walletId: WalletId): L1OperationRecord? = wallets.withWalletLock(walletId) {
        val local = operations(walletId)
        if (local.isEmpty()) return@withWalletLock null
        val profile = profile(walletId)
        val reconciled = local.map { operation ->
            when (operation.state) {
                L1OperationState.PREPARED -> operation.copy(state = L1OperationState.REJECTED)
                L1OperationState.SETTLED, L1OperationState.REJECTED -> operation
                else -> operation.withRemote(lookupOperation(profile, operation.operationId))
            }
        }
        writeOperations(walletId, reconciled)
        reconciled.last()
    }

    suspend fun operation(walletId: WalletId): L1OperationRecord? = operations(walletId).lastOrNull()

    suspend fun operations(walletId: WalletId): List<L1OperationRecord> {
        val bytes = vault.walletState(walletId).operationJournal
        if (bytes.isEmpty()) return emptyList()
        val journal = try {
            json.decodeFromString<WalletOperationJournalV1>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
        if (journal.l1.isEmpty()) {
            journal.channel.fill(0)
            journal.payment.fill(0)
            return emptyList()
        }
        return try {
            val encoded = journal.l1.decodeToString()
            runCatching { json.decodeFromString<L1OperationsV1>(encoded).records }
                .getOrElse { listOf(json.decodeFromString<L1OperationRecord>(encoded)) }
        } finally {
            journal.l1.fill(0)
            journal.channel.fill(0)
            journal.payment.fill(0)
        }
    }

    private suspend fun writeOperation(walletId: WalletId, operation: L1OperationRecord) {
        val operations = operations(walletId).toMutableList()
        val existing = operations.indexOfFirst { it.operationId == operation.operationId }
        if (existing < 0) operations += operation else operations[existing] = operation
        writeOperations(walletId, operations)
    }

    private suspend fun writeOperations(walletId: WalletId, operations: List<L1OperationRecord>) {
        val current = vault.walletState(walletId)
        val journal = if (current.operationJournal.isEmpty()) WalletOperationJournalV1() else
            json.decodeFromString<WalletOperationJournalV1>(current.operationJournal.decodeToString())
        val l1 = json.encodeToString(L1OperationsV1(operations)).encodeToByteArray()
        val encoded = json.encodeToString(journal.copy(l1 = l1)).encodeToByteArray()
        try {
            vault.updateWalletState(walletId, current.copy(operationJournal = encoded))
        } finally {
            current.channelRecovery.fill(0)
            current.operationJournal.fill(0)
            journal.l1.fill(0)
            journal.channel.fill(0)
            journal.payment.fill(0)
            l1.fill(0)
            encoded.fill(0)
        }
    }

    private suspend fun profile(walletId: WalletId): WalletProfile =
        vault.profiles().single { it.id == walletId }

    private suspend fun transferDestination(source: WalletProfile, supplied: WalletProfile): WalletProfile {
        val resolved = profile(supplied.id)
        require(resolved.id != source.id)
        require(supplied.network == source.network && resolved.network == source.network)
        require(supplied.paymentAddress == resolved.paymentAddress && resolved.paymentAddress != source.paymentAddress)
        return resolved
    }

    private fun L1OperationRecord.withRemote(remote: L1OperationDto): L1OperationRecord {
        require(remote.operationId == operationId && remote.expectedTransactionId == expectedTransactionId) {
            "operation identity mismatch"
        }
        return copy(
            state = when (remote.status) {
                "confirmed" -> L1OperationState.CONFIRMED
                "settled" -> L1OperationState.SETTLED
                "rejected" -> L1OperationState.REJECTED
                else -> L1OperationState.PENDING
            },
        )
    }

    private fun ByteArray.hex() = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun io.riverark.ferret.core.model.CardanoNetwork.accepts(address: String) =
        if (this == io.riverark.ferret.core.model.CardanoNetwork.MAINNET) {
            address.startsWith("addr1")
        } else {
            address.startsWith("addr_test1")
        }

    private companion object {
        const val INITIAL_SWEEP_FEE = 500_000L
        const val MAX_SWEEP_PASSES = 4
        const val TRANSFER_VALIDITY_SLOTS = 3_600L
        val UNRESOLVED_STATES = setOf(
            L1OperationState.PREPARED,
            L1OperationState.SUBMITTING,
            L1OperationState.PENDING,
        )
    }
}
