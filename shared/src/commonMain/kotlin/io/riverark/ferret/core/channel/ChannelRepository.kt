package io.riverark.ferret.core.channel

import io.ktor.client.plugins.ResponseException
import io.riverark.ferret.core.network.terminalPaymentFailureMessage
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.model.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@kotlinx.serialization.Serializable
sealed interface ChannelAction {
    @kotlinx.serialization.Serializable data class Open(val amount: AssetAmount) : ChannelAction
    @kotlinx.serialization.Serializable data class Add(val amount: AssetAmount) : ChannelAction
    @kotlinx.serialization.Serializable data class Pay(val quoteId: String, val invoiceHash: String) : ChannelAction
    @kotlinx.serialization.Serializable data object Close : ChannelAction
    @kotlinx.serialization.Serializable data object Squash : ChannelAction
}

@kotlinx.serialization.Serializable
sealed interface ChannelPayload {
    @kotlinx.serialization.Serializable
    data class Transaction(
        val unsignedBody: ByteArray,
        val expectedTransactionId: String,
        val signedTransaction: ByteArray = byteArrayOf(),
        val intent: CardanoIntent? = null,
        val feeBound: Lovelace? = null,
    ) : ChannelPayload

    @kotlinx.serialization.Serializable
    data class Payment(
        val authorizationCbor: ByteArray,
        val invoice: String,
        val invoiceHash: String,
        val quoteDigest: String,
        val request: AdaptorPayRequest,
        val quote: PaymentQuote,
    ) : ChannelPayload
    @kotlinx.serialization.Serializable
    data class Squash(val request: SignedSquashWire) : ChannelPayload

    @kotlinx.serialization.Serializable
    data class Protocol(val cbor: ByteArray) : ChannelPayload
}

@kotlinx.serialization.Serializable
data class PreparedChannelOperation(
    val operationId: String,
    val intentHash: String,
    val keytag: ProtocolKeytag,
    val asset: ChannelAsset,
    val action: ChannelAction,
    val priorChannelIdentity: String? = null,
    val preparedAtEpochMillis: Long,
    val payload: ChannelPayload,
    val resultingSpendableBalance: AssetAmount? = null,
    val state: OperationState = OperationState.PROPOSED,
)

@kotlinx.serialization.Serializable
data class ChannelRemoteResult(
    val operationId: String,
    val intentHash: String,
    val keytag: ProtocolKeytag,
    val asset: ChannelAsset,
    val transactionId: String? = null,
    val state: ChannelState,
    val status: OperationState,
    val protocolReceipt: ProtocolReceipt? = null,
    val failureMessage: String? = null,
)

@kotlinx.serialization.Serializable
data class ChannelPreview(
    val operation: PreparedChannelOperation,
    val amount: AssetAmount,
    val actualFee: AssetAmount,
    val feeBound: AssetAmount,
    val sourceChange: io.riverark.ferret.core.cardano.TransactionOutputSummary?,
    val ledgerMinAda: AssetAmount,
    val protocolReserve: AssetAmount,
    val resultingSpendableBalance: AssetAmount,
    val network: io.riverark.ferret.core.model.CardanoNetwork,
    val outputAda: AssetAmount,
)

@kotlinx.serialization.Serializable
data class ChannelSnapshot(
    val keytag: ProtocolKeytag,
    val asset: ChannelAsset,
    val state: ChannelState,
    val pending: PreparedChannelOperation? = null,
    val protocolReceipt: ProtocolReceipt? = null,
    val history: List<ChannelRemoteResult> = emptyList(),
    val spendableBalance: AssetAmount = AssetAmount(asset, 0),
    val payments: PaymentJournalV2 = PaymentJournalV2(),
)

class InactiveChannelCleanupRejected(message: String) : IllegalStateException(message)

interface ChannelJournal {
    suspend fun load(walletId: WalletId): ChannelCollectionV4
    suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4)
    fun cleanupInactive(collection: ChannelCollectionV4): ChannelCollectionV4 =
        error("Legacy channel cleanup is unavailable.")
}

interface ChannelBackupProtocol {
    suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease
    suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4)
    suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4)
}

interface ChannelRemote {
    suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease): ChannelRemoteResult
    suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease): ChannelRemoteResult?
}

class ChannelRepository(
    private val wallets: WalletRepository,
    private val journal: ChannelJournal,
    private val backup: ChannelBackupProtocol,
    private val remote: ChannelRemote,
    private val newOperationId: () -> String = { error("operation ID generator unavailable") },
    private val transactions: OpenChannelTransactions? = null,
) {
    private val mutableSnapshots = MutableStateFlow<Map<WalletId, ChannelCollectionV4>>(emptyMap())
    val snapshots: StateFlow<Map<WalletId, ChannelCollectionV4>> = mutableSnapshots.asStateFlow()

    suspend fun load(walletId: WalletId) = wallets.withWalletLock(walletId) { publish(walletId, journal.load(walletId)) }

    suspend fun cleanupInactive(walletId: WalletId): ChannelCollectionV4 = wallets.withWalletLock(walletId) {
        val current = current(walletId)
        if (current.channels.values.any { it.pending != null || it.payments.pending != null }) {
            throw InactiveChannelCleanupRejected("Pending channel work must finish before cleanup.")
        }
        val cleaned = try {
            journal.cleanupInactive(current)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw InactiveChannelCleanupRejected(
                (error as? IllegalArgumentException)?.message
                    ?: "Cleanup refused because the stored legacy records could not be validated.",
            )
        }
        try {
            backup.requireVerifiedWriter(walletId)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw InactiveChannelCleanupRejected(
                "Cleanup requires this device to hold the verified backup writer lease.",
            )
        }
        try {
            backup.writeAhead(walletId, cleaned)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw InactiveChannelCleanupRejected(
                "The encrypted Drive backup could not be updated. Local records were not changed.",
            )
        }
        try {
            persist(walletId, cleaned)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw InactiveChannelCleanupRejected(
                "The backup was updated, but local cleanup was interrupted. Retry cleanup.",
            )
        }
        cleaned
    }

    suspend fun previewOpen(walletId: WalletId, amount: AssetAmount): ChannelPreview = wallets.withWalletLock(walletId) {
        val collection = current(walletId)
        requireMutable(collection)
        val authorizer = requireNotNull(transactions) { "Open channel transactions are unavailable" }
        authorizer.requireAvailable(walletId)
        backup.requireVerifiedWriter(walletId)
        authorizer.preview(walletId, amount, newOperationId()).also {
            require(it.operation.keytag.value !in collection.channels) { "channel keytag already exists" }
        }
    }

    suspend fun submitPayment(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        invoice: String,
        quote: PaymentQuote,
        gateway: PaymentGateway,
        preparedAtEpochMillis: Long,
    ): ChannelRemoteResult = wallets.withWalletLock(walletId) {
        val collection = current(walletId)
        requireMutable(collection)
        val entry = requireNotNull(collection.channels[keytag.value]) { "payment channel unavailable" }
        require(entry.pending == null && entry.state is ChannelState.Open)
        require(quote.keytag == keytag && quote.amount.asset == entry.asset)
        require(quote.invoiceHash !in collection.paidHashes)
        require(collection.channels.values.none { it.payments.pending?.paymentHash == quote.invoiceHash })
        submitLocked(
            walletId,
            collection,
            gateway.prepare(walletId, keytag, newOperationId(), quote.id, invoice, quote, preparedAtEpochMillis),
        )
    }
    suspend fun initializePayment(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        gateway: PaymentGateway,
        preparedAtEpochMillis: Long,
    ): ChannelRemoteResult? = wallets.withWalletLock(walletId) {
        val collection = current(walletId)
        requireMutable(collection)
        val preview = gateway.prepareInitialization(walletId, keytag, newOperationId(), preparedAtEpochMillis)
            ?: return@withWalletLock null
        val result = submitLocked(walletId, collection, preview)
        if (result.status == OperationState.COMPLETED) result else {
            reconcileLocked(walletId, current(walletId), keytag)
        }
    }


    suspend fun submit(walletId: WalletId, preview: ChannelPreview): String =
        wallets.withWalletLock(walletId) { submitLocked(walletId, current(walletId), preview).operationId }

    private suspend fun submitLocked(walletId: WalletId, original: ChannelCollectionV4, preview: ChannelPreview): ChannelRemoteResult {
        requireMutable(original)
        val operation = preview.operation
        require(operation.asset == preview.amount.asset)
        val existing = original.channels[operation.keytag.value]
        val current = if (operation.action is ChannelAction.Open) {
            require(existing == null) { "channel keytag already exists" }
            ChannelSnapshot(operation.keytag, operation.asset, ChannelState.Absent)
        } else requireNotNull(existing) { "channel unavailable" }
        require(current.asset == operation.asset && current.keytag == operation.keytag)
        require(current.pending == null) { "unresolved operation" }
        requireAllowed(current.state, operation.action)
        val transactionPayload = operation.payload as? ChannelPayload.Transaction
        val adoptedPayload = transactionPayload?.copy(
            unsignedBody = transactionPayload.unsignedBody.copyOf(),
            signedTransaction = transactionPayload.signedTransaction.copyOf(),
        ) ?: operation.payload
        val adoptedPreview = preview.copy(operation = operation.copy(payload = adoptedPayload))
        val authorizer = (adoptedPayload as? ChannelPayload.Transaction)?.let {
            require(operation.action is ChannelAction.Open && it.intent is CardanoIntent.OpenChannel)
            requireNotNull(transactions).also { configured -> configured.validatePreview(walletId, adoptedPreview) }
        }
        require(operation.action !is ChannelAction.Open || authorizer != null)
        val prepared = adoptedPreview.operation.copy(
            priorChannelIdentity = operation.priorChannelIdentity ?: (current.state as? ChannelState.Open)?.channelId,
            resultingSpendableBalance = operation.resultingSpendableBalance ?: when (operation.action) {
                is ChannelAction.Pay -> current.spendableBalance - preview.amount - preview.actualFee
                else -> preview.resultingSpendableBalance
            },
        )
        require(operation.state == OperationState.PROPOSED)
        backup.requireVerifiedWriter(walletId)
        var proposedEntry = current.copy(
            pending = prepared,
            state = when (operation.action) {
                is ChannelAction.Open -> ChannelState.Opening(operation.operationId)
                ChannelAction.Close -> ChannelState.Closing(operation.operationId)
                else -> current.state
            },
            payments = if (prepared.payload is ChannelPayload.Payment) {
                val payment = prepared.payload
                require(current.payments.pending == null && payment.invoiceHash !in original.paidHashes)
                current.payments.copy(pending = PendingPayment(prepared.operationId, payment.invoiceHash, payment.quote, prepared.preparedAtEpochMillis))
            } else current.payments,
        )
        var proposed = original.withEntry(proposedEntry)
        persist(walletId, proposed)
        backup.writeAhead(walletId, proposed)
        if (authorizer != null) {
            val signed = authorizer.sign(walletId, prepared)
            proposedEntry = proposedEntry.copy(pending = signed)
            proposed = proposed.withEntry(proposedEntry)
            persist(walletId, proposed)
            backup.writeAhead(walletId, proposed)
        }
        val armedEntry = proposedEntry.copy(pending = requireNotNull(proposedEntry.pending).copy(state = OperationState.WRITE_AHEAD_VERIFIED))
        val armed = proposed.withEntry(armedEntry)
        persist(walletId, armed)
        val writer = backup.requireVerifiedWriter(walletId)
        currentCoroutineContext().ensureActive()
        authorizer?.requireAvailable(walletId)
        val result = try {
            remote.mutate(walletId, requireNotNull(armedEntry.pending), writer)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                persist(walletId, armed.withEntry(armedEntry.copy(
                    pending = requireNotNull(armedEntry.pending).copy(state = OperationState.PENDING_RECONCILIATION),
                )))
            }
            throw error
        }
        return complete(walletId, armed, result)
    }

    suspend fun reconcile(walletId: WalletId, keytag: ProtocolKeytag): ChannelRemoteResult? =
        wallets.withWalletLock(walletId) { reconcileLocked(walletId, current(walletId), keytag) }

    suspend fun reconcileAll(walletId: WalletId): List<ChannelRemoteResult> {
        val keys = journal.load(walletId).channels.values.filter { it.pending != null }.map { it.keytag }.sortedBy { it.value }
        return keys.mapNotNull { reconcile(walletId, it) }
    }

    private suspend fun reconcileLocked(walletId: WalletId, initial: ChannelCollectionV4, keytag: ProtocolKeytag): ChannelRemoteResult? {
        val current = requireNotNull(initial.channels[keytag.value])
        val pending = current.pending ?: return null
        var replayBase = initial
        var writer = backup.requireVerifiedWriter(walletId)
        val savedTerminal = current.history.filter {
            it.operationId == pending.operationId && it.status in setOf(OperationState.COMPLETED, OperationState.FAILED)
        }
        require(savedTerminal.size <= 1)
        savedTerminal.singleOrNull()?.let { return complete(walletId, initial, it) }
        val transaction = pending.payload as? ChannelPayload.Transaction
        if (transaction != null && transaction.signedTransaction.isEmpty()) {
            return complete(walletId, initial, ChannelRemoteResult(
                pending.operationId, pending.intentHash, pending.keytag, pending.asset,
                state = ChannelState.Absent, status = OperationState.FAILED,
            ))
        }
        val reconciled = remote.reconcile(walletId, pending, writer)
        val result = if (reconciled?.status == OperationState.SUBMITTED && pending.payload is ChannelPayload.Payment) {
            remote.mutate(walletId, pending, writer)
        } else reconciled ?: run {
            if (pending.state in setOf(OperationState.SUBMITTED, OperationState.COMPLETED, OperationState.FAILED)) return null
            val authorizer = transaction?.let {
                require(pending.action is ChannelAction.Open && it.intent is CardanoIntent.OpenChannel)
                requireNotNull(transactions).also { configured -> configured.validateReplay(walletId, pending) }
            }
            if (pending.state == OperationState.PROPOSED) {
                backup.writeAhead(walletId, initial)
                val entry = current.copy(pending = pending.copy(state = OperationState.WRITE_AHEAD_VERIFIED))
                replayBase = initial.withEntry(entry)
                persist(walletId, replayBase)
                writer = backup.requireVerifiedWriter(walletId)
            }
            currentCoroutineContext().ensureActive()
            authorizer?.requireAvailable(walletId)
            try {
                remote.mutate(walletId, requireNotNull(replayBase.channels[keytag.value]?.pending), writer)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    val entry = requireNotNull(replayBase.channels[keytag.value])
                    persist(walletId, replayBase.withEntry(entry.copy(
                        pending = requireNotNull(entry.pending).copy(state = OperationState.PENDING_RECONCILIATION),
                    )))
                }
                throw error
            }
        }
        return complete(walletId, replayBase, result)
    }

    private suspend fun complete(walletId: WalletId, current: ChannelCollectionV4, result: ChannelRemoteResult): ChannelRemoteResult {
        val entry = requireNotNull(current.channels[result.keytag.value])
        val pending = requireNotNull(entry.pending)
        require(result.operationId == pending.operationId && result.intentHash == pending.intentHash)
        require(result.keytag == pending.keytag && result.asset == pending.asset)
        var persistedEntry = entry.copy(
            pending = pending.copy(state = result.status),
            protocolReceipt = result.protocolReceipt ?: entry.protocolReceipt,
            history = entry.history.filterNot { it.operationId == result.operationId } + result,
        )
        var persisted = current.withEntry(persistedEntry)
        persist(walletId, persisted)
        if (result.status !in setOf(OperationState.COMPLETED, OperationState.FAILED)) return result
        var paidHashes = persisted.paidHashes
        var payments = persistedEntry.payments
        val payment = pending.payload as? ChannelPayload.Payment
        if (payment != null) {
            val receipt = io.riverark.ferret.core.model.Receipt(
                pending.operationId, payment.invoiceHash, pending.keytag, payment.quote.amount,
                payment.quote.routingFee + payment.quote.adaptorFee, result.status == OperationState.COMPLETED,
            )
            require(payments.pending?.operationId == pending.operationId)
            payments = payments.copy(pending = null, receipts = payments.receipts + StoredReceipt(receipt, pending.preparedAtEpochMillis))
            if (receipt.verified) paidHashes = paidHashes + receipt.paymentHash
        }
        val terminalEntry = persistedEntry.copy(
            state = result.state,
            pending = null,
            spendableBalance = if (result.status == OperationState.COMPLETED) {
                pending.resultingSpendableBalance ?: persistedEntry.spendableBalance
            } else persistedEntry.spendableBalance,
            payments = payments,
        )
        val terminal = persisted.copy(channels = persisted.channels + (result.keytag.value to terminalEntry), paidHashes = paidHashes)
        try {
            backup.commit(walletId, terminal)
            persist(walletId, terminal)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                persistedEntry = persistedEntry.copy(pending = requireNotNull(persistedEntry.pending).copy(state = OperationState.PENDING_RECONCILIATION))
                persist(walletId, persisted.withEntry(persistedEntry))
            }
            throw error
        }
        return result
    }

    private suspend fun current(walletId: WalletId) =
        mutableSnapshots.value[walletId] ?: journal.load(walletId).also { publish(walletId, it) }

    private suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) {
        journal.persist(walletId, collection)
        publish(walletId, collection)
    }

    private fun publish(walletId: WalletId, collection: ChannelCollectionV4) {
        require(collection.walletId == walletId)
        mutableSnapshots.value = mutableSnapshots.value + (walletId to collection)
    }

    private fun requireMutable(collection: ChannelCollectionV4) {
        require(collection.unresolvedLegacy.isEmpty()) { "Legacy channel recovery requires verified identity." }
    }

    private fun ChannelCollectionV4.withEntry(entry: ChannelSnapshot): ChannelCollectionV4 =
        copy(channels = channels + (entry.keytag.value to entry))

    private fun requireAllowed(state: ChannelState, action: ChannelAction) {
        val allowed = when (action) {
            is ChannelAction.Open -> state == ChannelState.Absent || state == ChannelState.Closed
            is ChannelAction.Add, is ChannelAction.Pay, ChannelAction.Close, ChannelAction.Squash -> state is ChannelState.Open
        }
        require(allowed) { "illegal channel transition: ${state::class.simpleName} -> ${action::class.simpleName}" }
    }
}
class AdaptorChannelRemote(
    private val adaptor: suspend (WalletId) -> io.riverark.ferret.core.network.AdaptorClient,
    private val crypto: ProtocolCrypto,
) : ChannelRemote {
    override suspend fun mutate(
        walletId: WalletId,
        operation: PreparedChannelOperation,
        writer: WriterLease,
    ): ChannelRemoteResult = when (val payload = operation.payload) {
        is ChannelPayload.Transaction -> {
            require(payload.signedTransaction.isNotEmpty()) { "signed channel transaction is unavailable" }
            result(
                operation,
                adaptor(walletId).submitChannelOperation(
                    operation.keytag,
                    writer,
                    io.riverark.ferret.core.network.L1SubmitRequest(
                        operation.operationId,
                        payload.expectedTransactionId,
                        payload.signedTransaction.hex(),
                    ),
                ),
            )
        }
        is ChannelPayload.Payment -> {
            try {
                adaptor(walletId).pay(operation.keytag, writer.token, payload.request)
            } catch (error: ResponseException) {
                val failure = error.terminalPaymentFailureMessage()
                if (failure != null) return paymentResult(operation, OperationState.FAILED, failure)
                throw error
            }
            reconcile(walletId, operation, writer) ?: paymentResult(operation, OperationState.SUBMITTED)
        }
        is ChannelPayload.Squash -> {
            adaptor(walletId).squash(operation.keytag, writer.token, payload.request)
            val receipt = adaptor(walletId).receipt(operation.keytag)
            paymentResult(
                operation,
                if (receipt == null) OperationState.SUBMITTED else OperationState.COMPLETED,
                receipt = receipt,
            )
        }
        is ChannelPayload.Protocol -> error("unsupported channel protocol payload")
    }

    override suspend fun reconcile(
        walletId: WalletId,
        operation: PreparedChannelOperation,
        writer: WriterLease,
    ): ChannelRemoteResult? = when (val payload = operation.payload) {
        is ChannelPayload.Transaction -> adaptor(walletId).channelOperation(
            operation.keytag,
            writer,
            operation.operationId,
        )?.let { result(operation, it) }
        is ChannelPayload.Payment -> {
            val receipt = adaptor(walletId).receipt(operation.keytag) ?: return null
            val matching = receipt.cheques.firstOrNull { cheque ->
                val signed = when (cheque) {
                    is ProtocolCheque.Locked -> cheque.value
                    is ProtocolCheque.Unlocked -> cheque.value
                }
                signed.body.index == payload.request.chequeBody.index &&
                    signed.body.amount == payload.request.chequeBody.amount &&
                    signed.body.timeout == payload.request.chequeBody.timeout &&
                    when (cheque) {
                        is ProtocolCheque.Locked -> signed.body.latch.value == payload.invoiceHash
                        is ProtocolCheque.Unlocked -> crypto.sha256(signed.body.latch.value.hexBytes()).hex() == payload.invoiceHash
                    }
            } ?: return if (operation.state in setOf(OperationState.SUBMITTED, OperationState.PENDING_RECONCILIATION)) {
                paymentResult(operation, OperationState.FAILED, receipt = receipt)
            } else null
            paymentResult(
                operation,
                if (matching is ProtocolCheque.Unlocked) OperationState.COMPLETED else OperationState.SUBMITTED,
                receipt = receipt,
            )
        }
        is ChannelPayload.Protocol -> error("unsupported channel protocol payload")
        is ChannelPayload.Squash -> adaptor(walletId).receipt(operation.keytag)?.let {
            paymentResult(operation, OperationState.COMPLETED, receipt = it)
        }
    }

    private fun result(
        operation: PreparedChannelOperation,
        response: io.riverark.ferret.core.network.L1OperationDto,
    ): ChannelRemoteResult {
        val payload = operation.payload as? ChannelPayload.Transaction ?: error("channel transaction payload is required")
        require(response.operationId == operation.operationId && response.expectedTransactionId == payload.expectedTransactionId)
        val status = when (response.status) {
            "confirmed", "settled" -> OperationState.COMPLETED
            "rejected" -> OperationState.FAILED
            "accepted" -> OperationState.SUBMITTED
            else -> OperationState.PENDING_RECONCILIATION
        }
        val remoteId = response.transactionId ?: payload.expectedTransactionId
        val state = when {
            status == OperationState.FAILED -> when (operation.action) {
                is ChannelAction.Open -> ChannelState.Absent
                else -> operation.priorChannelIdentity?.let(ChannelState::Open) ?: ChannelState.Absent
            }
            status != OperationState.COMPLETED -> when (operation.action) {
                is ChannelAction.Open -> ChannelState.Opening(operation.operationId)
                ChannelAction.Close -> ChannelState.Closing(operation.operationId)
                else -> operation.priorChannelIdentity?.let(ChannelState::Open) ?: ChannelState.Absent
            }
            operation.action is ChannelAction.Open -> ChannelState.Open(remoteId)
            operation.action == ChannelAction.Close -> ChannelState.Closed
            else -> ChannelState.Open(requireNotNull(operation.priorChannelIdentity))
        }
        return ChannelRemoteResult(
            operation.operationId,
            operation.intentHash,
            operation.keytag,
            operation.asset,
            response.transactionId,
            state,
            status,
        )
    }

    private fun paymentResult(
        operation: PreparedChannelOperation,
        status: OperationState,
        failureMessage: String? = null,
        receipt: ProtocolReceipt? = null,
    ) = ChannelRemoteResult(
        operation.operationId,
        operation.intentHash,
        operation.keytag,
        operation.asset,
        state = ChannelState.Open(requireNotNull(operation.priorChannelIdentity)),
        status = status,
        protocolReceipt = receipt,
        failureMessage = failureMessage,
    )

    private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0 && all { it in "0123456789abcdef" })
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
