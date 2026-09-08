package io.riverark.ferret.core.channel

import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletOperationJournalV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingPaymentV1(
    val operationId: String,
    val paymentHash: String,
    val quote: PaymentQuote,
    val createdAtEpochMillis: Long,
)

@Serializable
data class StoredReceiptV1(val receipt: Receipt, val completedAtEpochMillis: Long)

@Serializable
data class PaymentJournalV1(
    val schema: Int = 1,
    val paidHashes: Set<String> = emptySet(),
    val pending: PendingPaymentV1? = null,
    val receipts: List<StoredReceiptV1> = emptyList(),
)

interface PaymentStore {
    suspend fun requireCapacity(walletId: WalletId) = Unit
    suspend fun isPaid(walletId: WalletId, paymentHash: String): Boolean
    suspend fun pending(walletId: WalletId): PendingPaymentV1?
    suspend fun recordPending(walletId: WalletId, payment: PendingPaymentV1)
    suspend fun complete(walletId: WalletId, receipt: Receipt, completedAtEpochMillis: Long)
    suspend fun receipt(walletId: WalletId, operationId: String): Receipt?
    suspend fun history(walletId: WalletId): List<TransactionRecord>
    suspend fun recovery(walletId: WalletId): PaymentJournalV1
    suspend fun installRecovery(walletId: WalletId, recovery: PaymentJournalV1)
}

class VaultPaymentStore(
    private val vault: SecureVault,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : PaymentStore {
    override suspend fun isPaid(walletId: WalletId, paymentHash: String) = load(walletId).paidHashes.contains(paymentHash)

    override suspend fun pending(walletId: WalletId) = load(walletId).pending
    override suspend fun recovery(walletId: WalletId) = load(walletId)
    override suspend fun installRecovery(walletId: WalletId, recovery: PaymentJournalV1) = save(walletId, recovery)
    override suspend fun requireCapacity(walletId: WalletId) {
        // ponytail: bounded encrypted hash set; use an authenticated paged store if 10,000 payments becomes insufficient.
        require(load(walletId).paidHashes.size < MAX_RECEIPTS) { "Payment history storage is full." }
    }

    override suspend fun recordPending(walletId: WalletId, payment: PendingPaymentV1) {
        validateHash(payment.paymentHash)
        require(payment.createdAtEpochMillis >= 0)
        val journal = load(walletId)
        if (journal.pending == payment) return
        require(journal.pending == null && payment.paymentHash !in journal.paidHashes) { "payment is already pending or paid" }
        save(walletId, journal.copy(pending = payment))
    }

    override suspend fun complete(walletId: WalletId, receipt: Receipt, completedAtEpochMillis: Long) {
        require(completedAtEpochMillis >= 0)
        val journal = load(walletId)
        journal.receipts.singleOrNull { it.receipt.operationId == receipt.operationId }?.let {
            require(it.receipt == receipt)
            return
        }
        val pending = requireNotNull(journal.pending)
        require(receipt.operationId == pending.operationId && receipt.paymentHash == pending.paymentHash)
        save(walletId, journal.copy(
            paidHashes = journal.paidHashes + receipt.paymentHash,
            pending = null,
            receipts = (journal.receipts + StoredReceiptV1(receipt, completedAtEpochMillis)).takeLast(MAX_RECEIPTS),
        ))
    }

    override suspend fun receipt(walletId: WalletId, operationId: String) =
        load(walletId).receipts.singleOrNull { it.receipt.operationId == operationId }?.receipt

    override suspend fun history(walletId: WalletId): List<TransactionRecord> {
        val journal = load(walletId)
        val completed = journal.receipts.map { stored ->
            TransactionRecord(
                stored.receipt.operationId,
                stored.completedAtEpochMillis,
                stored.receipt.amount,
                stored.receipt.fee,
                Realm.L2,
                if (stored.receipt.verified) TransactionState.SETTLED else TransactionState.PENDING,
            )
        }
        val pending = journal.pending?.let {
            TransactionRecord(
                it.operationId,
                it.createdAtEpochMillis,
                it.quote.amount,
                it.quote.routingFee + it.quote.adaptorFee,
                Realm.L2,
                TransactionState.PENDING,
            )
        }
        return (completed + listOfNotNull(pending))
            .sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
    }

    private suspend fun load(walletId: WalletId): PaymentJournalV1 {
        val state = vault.walletState(walletId)
        if (state.operationJournal.isEmpty()) return PaymentJournalV1()
        val envelope = try {
            json.decodeFromString<WalletOperationJournalV1>(state.operationJournal.decodeToString())
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
        }
        if (envelope.payment.isEmpty()) {
            envelope.l1.fill(0)
            envelope.channel.fill(0)
            return PaymentJournalV1()
        }
        return try {
            json.decodeFromString<PaymentJournalV1>(envelope.payment.decodeToString()).also(::validate)
        } finally {
            envelope.l1.fill(0)
            envelope.channel.fill(0)
            envelope.payment.fill(0)
        }
    }

    private suspend fun save(walletId: WalletId, payment: PaymentJournalV1) {
        validate(payment)
        val state = vault.walletState(walletId)
        val envelope = if (state.operationJournal.isEmpty()) WalletOperationJournalV1() else
            json.decodeFromString<WalletOperationJournalV1>(state.operationJournal.decodeToString())
        val paymentBytes = json.encodeToString(payment).encodeToByteArray()
        val encoded = json.encodeToString(envelope.copy(payment = paymentBytes)).encodeToByteArray()
        require(encoded.size <= MAX_JOURNAL_BYTES)
        try {
            vault.updateWalletState(walletId, state.copy(operationJournal = encoded))
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
            envelope.l1.fill(0)
            envelope.channel.fill(0)
            envelope.payment.fill(0)
            paymentBytes.fill(0)
            encoded.fill(0)
        }
    }

    private fun validate(journal: PaymentJournalV1) {
        require(journal.schema == 1 && journal.paidHashes.size <= MAX_RECEIPTS && journal.receipts.size <= MAX_RECEIPTS)
        journal.paidHashes.forEach(::validateHash)
        journal.pending?.let { validateHash(it.paymentHash); require(it.createdAtEpochMillis >= 0) }
        journal.receipts.forEach { validateHash(it.receipt.paymentHash); require(it.completedAtEpochMillis >= 0) }
    }

    private fun validateHash(value: String) {
        require(Regex("[0-9a-f]{64}").matches(value))
    }

    private companion object {
        const val MAX_RECEIPTS = 10_000
        const val MAX_JOURNAL_BYTES = 1_048_576
    }
}
