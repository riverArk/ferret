package io.riverark.ferret.feature.wallet

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.requireMatches
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
    val destinationWalletId: WalletId,
    val amount: Lovelace,
    val fee: Lovelace,
    val createdAtEpochMillis: Long,
    val state: L1OperationState,
)

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
) : L1WalletRepository {
    constructor(
        wallets: WalletRepository,
        vault: SecureVault,
        connector: (WalletProfile) -> ConnectorClient,
        engine: CardanoTransactionEngine,
        newOperationId: () -> String,
        nowEpochMillis: () -> Long,
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
    )
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun balance(walletId: WalletId): WalletBalance {
        val profile = profile(walletId)
        val ledger = loadLedger(profile)
        val spendable = ledger.utxos
            .filter { it.address == profile.paymentAddress && it.assets.isEmpty() && it.datumHex == null && it.scriptRefHex == null }
            .fold(Lovelace(0)) { total, utxo -> total + utxo.lovelace }
        return WalletBalance(spendable, Lovelace(0))
    }
    override suspend fun history(walletId: WalletId): List<TransactionRecord> {
        val profile = profile(walletId)
        val remote = loadTransactions(profile)
        val local = operation(walletId) ?: return remote
        val id = local.expectedTransactionId ?: local.operationId
        if (remote.any { it.id == id }) return remote
        return (remote + TransactionRecord(
            id,
            local.createdAtEpochMillis,
            local.amount,
            local.fee,
            io.riverark.ferret.core.model.Realm.L1,
            when (local.state) {
                L1OperationState.CONFIRMED -> io.riverark.ferret.core.model.TransactionState.CONFIRMED
                L1OperationState.SETTLED -> io.riverark.ferret.core.model.TransactionState.SETTLED
                L1OperationState.REJECTED -> io.riverark.ferret.core.model.TransactionState.FAILED
                else -> io.riverark.ferret.core.model.TransactionState.PENDING
            },
        )).sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
    }

    override suspend fun previewTransfer(walletId: WalletId, destination: WalletProfile, amount: Lovelace): TransferPreview =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(destination.id != walletId && destination.network == profile.network)
            require(amount.value > 0)
            val ledger = loadLedger(profile)
            val spendable = ledger.utxos
                .filter { it.address == profile.paymentAddress && it.assets.isEmpty() && it.datumHex == null && it.scriptRefHex == null }
                .fold(Lovelace(0)) { total, utxo -> total + utxo.lovelace }
            require(ledger.currentSlot <= Long.MAX_VALUE - TRANSFER_VALIDITY_SLOTS)
            val intent = CardanoIntent.Transfer(
                profile.paymentAddress,
                destination.paymentAddress,
                amount,
                newOperationId(),
                ledger.currentSlot,
                ledger.currentSlot + TRANSFER_VALIDITY_SLOTS,
            )
            val unsigned = engine.build(intent, ledger)
            engine.inspect(unsigned.cbor).requireMatches(intent, profile.network, unsigned.feeBound)
            val change = spendable - amount - unsigned.feeBound
            TransferPreview(destination, amount, unsigned.feeBound, change, intent, unsigned)
        }

    override suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview): String =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            val intent = requireNotNull(preview.intent)
            val unsigned = requireNotNull(preview.unsigned)
            require(intent.operationId == unsigned.operationId)
            require(intent.destinationAddress == preview.destination.paymentAddress)
            require(intent.amount == preview.amount && preview.destination.network == profile.network)
            val existing = operation(walletId)
            require(existing == null || existing.state !in UNRESOLVED_STATES) { "another wallet operation is unresolved" }
            val prepared = L1OperationRecord(
                intent.operationId,
                destinationWalletId = preview.destination.id,
                amount = preview.amount,
                fee = preview.feeBound,
                createdAtEpochMillis = nowEpochMillis(),
                state = L1OperationState.PREPARED,
            )
            writeOperation(walletId, prepared)
            val signed = vault.withWalletSeed(walletId) { engine.sign(unsigned, it) }
            try {
                engine.inspect(signed.cbor).requireMatches(intent, profile.network, preview.feeBound)
                val transactionId = engine.transactionId(signed.cbor)
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

    suspend fun reconcilePending(walletId: WalletId): L1OperationRecord? = wallets.withWalletLock(walletId) {
        val local = operation(walletId) ?: return@withWalletLock null
        if (local.state == L1OperationState.PREPARED) {
            return@withWalletLock local.copy(state = L1OperationState.REJECTED).also { writeOperation(walletId, it) }
        }
        if (local.state == L1OperationState.SETTLED || local.state == L1OperationState.REJECTED) return@withWalletLock local
        val remote = lookupOperation(profile(walletId), local.operationId)
        local.withRemote(remote).also {
            writeOperation(walletId, it)
        }
    }

    suspend fun operation(walletId: WalletId): L1OperationRecord? {
        val bytes = vault.walletState(walletId).operationJournal
        if (bytes.isEmpty()) return null
        val journal = try {
            json.decodeFromString<WalletOperationJournalV1>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
        if (journal.l1.isEmpty()) {
            journal.channel.fill(0)
            journal.payment.fill(0)
            return null
        }
        return try {
            json.decodeFromString<L1OperationRecord>(journal.l1.decodeToString())
        } finally {
            journal.l1.fill(0)
            journal.channel.fill(0)
            journal.payment.fill(0)
        }
    }

    private suspend fun writeOperation(walletId: WalletId, operation: L1OperationRecord) {
        val current = vault.walletState(walletId)
        val journal = if (current.operationJournal.isEmpty()) WalletOperationJournalV1() else
            json.decodeFromString<WalletOperationJournalV1>(current.operationJournal.decodeToString())
        val l1 = json.encodeToString(operation).encodeToByteArray()
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

    private companion object {
        const val TRANSFER_VALIDITY_SLOTS = 3_600L
        val UNRESOLVED_STATES = setOf(
            L1OperationState.PREPARED,
            L1OperationState.SUBMITTING,
            L1OperationState.PENDING,
        )
    }
}
