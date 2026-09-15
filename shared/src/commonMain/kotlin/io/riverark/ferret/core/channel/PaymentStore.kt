package io.riverark.ferret.core.channel

import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.WalletId
import kotlinx.serialization.Serializable

@Serializable
data class PendingPayment(
    val operationId: String,
    val paymentHash: String,
    val quote: PaymentQuote,
    val createdAtEpochMillis: Long,
)

@Serializable
data class StoredReceipt(val receipt: Receipt, val completedAtEpochMillis: Long)

@Serializable
data class PaymentJournalV2(
    val pending: PendingPayment? = null,
    val receipts: List<StoredReceipt> = emptyList(),
)

interface PaymentStore {
    suspend fun isPaid(walletId: WalletId, paymentHash: String): Boolean
    suspend fun isPending(walletId: WalletId, paymentHash: String): Boolean
    suspend fun pending(walletId: WalletId, keytag: ProtocolKeytag): PendingPayment?
    suspend fun receipt(walletId: WalletId, keytag: ProtocolKeytag, operationId: String): Receipt?
    suspend fun history(walletId: WalletId): List<TransactionRecord>
}

class VaultPaymentStore(private val channels: ChannelJournal) : PaymentStore {
    override suspend fun isPaid(walletId: WalletId, paymentHash: String) =
        channels.load(walletId).paidHashes.contains(paymentHash)

    override suspend fun isPending(walletId: WalletId, paymentHash: String) =
        channels.load(walletId).channels.values.any { it.payments.pending?.paymentHash == paymentHash }

    override suspend fun pending(walletId: WalletId, keytag: ProtocolKeytag) =
        channels.load(walletId).channels[keytag.value]?.payments?.pending

    override suspend fun receipt(walletId: WalletId, keytag: ProtocolKeytag, operationId: String) =
        channels.load(walletId).channels[keytag.value]?.payments?.receipts
            ?.singleOrNull { it.receipt.operationId == operationId }
            ?.receipt

    override suspend fun history(walletId: WalletId): List<TransactionRecord> =
        channels.load(walletId).channels.values.flatMap { entry ->
            val completed = entry.payments.receipts.map { stored ->
                TransactionRecord(
                    id = stored.receipt.operationId,
                    timestampEpochMillis = stored.completedAtEpochMillis,
                    amounts = listOf(stored.receipt.amount),
                    fee = stored.receipt.fee,
                    realm = Realm.L2,
                    state = if (stored.receipt.verified) TransactionState.SETTLED else TransactionState.FAILED,
                    channelKeytag = entry.keytag,
                )
            }
            completed + listOfNotNull(entry.payments.pending?.takeIf { pending ->
                completed.none { it.id == pending.operationId }
            }?.let {
                TransactionRecord(
                    id = it.operationId,
                    timestampEpochMillis = it.createdAtEpochMillis,
                    amounts = listOf(it.quote.amount),
                    fee = it.quote.routingFee + it.quote.adaptorFee,
                    realm = Realm.L2,
                    state = TransactionState.PENDING,
                    channelKeytag = entry.keytag,
                )
            })
        }.sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
}
