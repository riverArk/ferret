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
import io.riverark.ferret.core.cardano.ChannelDatum
import io.riverark.ferret.core.cardano.CloseChannelStep
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.ChannelDatumStage
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
    @kotlinx.serialization.Serializable data class ReturnFunds(val step: CloseChannelStep) : ChannelAction {
        init { require(step != CloseChannelStep.CLOSE) }
    }
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
    val priorChannelState: ChannelState? = null,
    val priorSpendableBalance: AssetAmount? = null,
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
    val closeStep: CloseChannelStep? = null,
    val confirmationDepth: Long? = null,
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
    val collateral: AssetAmount? = null,
)

@kotlinx.serialization.Serializable
data class ChannelChainObservation(
    val output: LedgerUtxo,
    val datum: ChannelDatum,
    val confirmationDepth: Long,
    val returnAfterEpochMillis: Long?,
    val canReturn: Boolean,
)

@kotlinx.serialization.Serializable
data class ChannelReturnProof(
    val transactionId: String,
    val channelInput: LedgerUtxo,
    val datum: ChannelDatum,
    val sourceAddress: String,
    val returnedAmount: AssetAmount,
    val releasedAda: Lovelace,
    val fee: Lovelace,
    val confirmationDepth: Long,
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
    val chainObservation: ChannelChainObservation? = null,
    val returnProof: ChannelReturnProof? = null,
    val confirmedReturnOperation: PreparedChannelOperation? = null,
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
    private val transactions: ChannelTransactions? = null,
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
        authorizer.previewOpen(walletId, amount, newOperationId()).also {
            require(it.operation.keytag.value !in collection.channels) { "channel keytag already exists" }
        }
    }

    suspend fun previewAdd(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        amount: AssetAmount,
    ): ChannelPreview = wallets.withWalletLock(walletId) {
        val collection = current(walletId)
        requireMutable(collection)
        require(collection.channels.values.none { it.pending?.payload is ChannelPayload.Transaction }) {
            "Another channel transaction is unresolved."
        }
        val channel = requireNotNull(collection.channels[keytag.value]) { "channel unavailable" }
        require(channel.keytag == keytag && channel.asset == amount.asset)
        require(channel.state is ChannelState.Open && channel.pending == null && channel.payments.pending == null)
        val authorizer = requireNotNull(transactions) { "Channel transactions are unavailable" }
        authorizer.requireAvailable(walletId)
        backup.requireVerifiedWriter(walletId)
        authorizer.previewAdd(walletId, channel, amount, newOperationId())
    }

    suspend fun previewClose(walletId: WalletId, keytag: ProtocolKeytag): ChannelPreview =
        previewExit(walletId, keytag, closing = true)

    suspend fun previewReturnFunds(walletId: WalletId, keytag: ProtocolKeytag): ChannelPreview =
        previewExit(walletId, keytag, closing = false)

    private suspend fun previewExit(walletId: WalletId, keytag: ProtocolKeytag, closing: Boolean): ChannelPreview =
        wallets.withWalletLock(walletId) {
            val authorizer = closeAuthorizer()
            val collection = current(walletId)
            val channel = requireNotNull(collection.channels[keytag.value]) { "channel unavailable" }
            require(channel.keytag == keytag)
            requireExitMutable(collection, channel)
            authorizer.requireAvailable(walletId)
            backup.requireVerifiedWriter(walletId)
            if (closing) authorizer.previewClose(walletId, channel, newOperationId())
            else authorizer.previewReturnFunds(walletId, channel, newOperationId())
        }

    private fun closeAuthorizer(): ChannelTransactions {
        require(transactions?.closeAvailable == true) { "Channel closing is unavailable on this platform." }
        return requireNotNull(transactions)
    }

    private fun PreparedChannelOperation.isExit() =
        action == ChannelAction.Close || action is ChannelAction.ReturnFunds

    private fun requireExitMutable(collection: ChannelCollectionV4, channel: ChannelSnapshot) {
        requireMutable(collection)
        require(channel.pending == null && channel.payments.pending == null) { "unresolved operation" }
        require(collection.channels.values.none { it.pending?.payload is ChannelPayload.Transaction }) {
            "Another channel transaction is unresolved."
        }
        require(channel.confirmedReturnOperation == null && channel.returnProof == null)
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
        if (operation.isExit()) closeAuthorizer()
        require(operation.asset == preview.amount.asset)
        val existing = original.channels[operation.keytag.value]
        val current = if (operation.action is ChannelAction.Open) {
            require(existing == null) { "channel keytag already exists" }
            ChannelSnapshot(operation.keytag, operation.asset, ChannelState.Absent)
        } else requireNotNull(existing) { "channel unavailable" }
        require(current.asset == operation.asset && current.keytag == operation.keytag)
        require(current.pending == null) { "unresolved operation" }
        requireAllowed(current.state, operation.action)
        if (operation.action is ChannelAction.Pay || operation.action == ChannelAction.Squash) {
            if (transactions?.closeAvailable == true) {
                val observation = closeAuthorizer().observe(walletId, listOf(current))[current.keytag]
                require(observation != null && observation.confirmationDepth >= 5 &&
                    observation.datum.stage is ChannelDatumStage.Opened) { "Channel status is unavailable or payments have stopped." }
            }
        }
        if (operation.isExit()) {
            requireExitMutable(original, current)
            require(operation.priorChannelState == current.state &&
                operation.priorSpendableBalance == current.spendableBalance)
            val intent = (operation.payload as? ChannelPayload.Transaction)?.intent as? CardanoIntent.CloseChannel
                ?: error("close transaction required")
            current.chainObservation?.let {
                require(it.output == intent.channelInput && it.datum == intent.currentDatum) {
                    "This channel changed. Review the updated transaction before confirming."
                }
            }
        }
        if (operation.action is ChannelAction.Add) {
            val action = operation.action
            val open = current.state as? ChannelState.Open ?: error("open channel required")
            require(current.payments.pending == null)
            require(original.channels.values.none {
                it.keytag != current.keytag && it.pending?.payload is ChannelPayload.Transaction
            }) { "Another channel transaction is unresolved." }
            require(action.amount == preview.amount && action.amount.asset == current.asset)
            require(operation.priorChannelIdentity == open.channelId)
            require(operation.resultingSpendableBalance == current.spendableBalance + action.amount)
        }
        val transactionPayload = operation.payload as? ChannelPayload.Transaction
        val adoptedPayload = transactionPayload?.copy(
            unsignedBody = transactionPayload.unsignedBody.copyOf(),
            signedTransaction = transactionPayload.signedTransaction.copyOf(),
        ) ?: operation.payload
        val adoptedPreview = preview.copy(operation = operation.copy(payload = adoptedPayload))
        val authorizer = (adoptedPayload as? ChannelPayload.Transaction)?.let {
            require(
                operation.action is ChannelAction.Open && it.intent is CardanoIntent.OpenChannel ||
                    operation.action is ChannelAction.Add && it.intent is CardanoIntent.AddChannelFunds ||
                    operation.isExit() && it.intent is CardanoIntent.CloseChannel,
            )
            requireNotNull(transactions).also { configured -> configured.validatePreview(walletId, adoptedPreview) }
        }
        require(operation.action !is ChannelAction.Open && operation.action !is ChannelAction.Add && !operation.isExit() || authorizer != null)
        val prepared = adoptedPreview.operation.copy(
            priorChannelIdentity = operation.priorChannelIdentity ?: (current.state as? ChannelState.Open)?.channelId,
            priorChannelState = if (operation.action == ChannelAction.Close || operation.action is ChannelAction.ReturnFunds) {
                current.state
            } else operation.priorChannelState,
            priorSpendableBalance = if (operation.action == ChannelAction.Close || operation.action is ChannelAction.ReturnFunds) {
                current.spendableBalance.also { require(it.asset == operation.asset) }
            } else operation.priorSpendableBalance,
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
                is ChannelAction.ReturnFunds -> ChannelState.Ending
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
        return if (prepared.isExit()) completeExit(walletId, armed, result) else complete(walletId, armed, result)
    }

    suspend fun reconcile(walletId: WalletId, keytag: ProtocolKeytag): ChannelRemoteResult? =
        wallets.withWalletLock(walletId) { reconcileLocked(walletId, current(walletId), keytag) }

    suspend fun reconcileAll(walletId: WalletId): List<ChannelRemoteResult> = wallets.withWalletLock(walletId) {
        val results = mutableListOf<ChannelRemoteResult>()
        val initial = current(walletId)
        for (entry in initial.channels.values.sortedBy { it.keytag.value }) {
            if (entry.pending?.isExit() == true || entry.confirmedReturnOperation != null) {
                if (transactions?.closeAvailable != true) continue
            }
            if (entry.pending != null || entry.confirmedReturnOperation != null) {
                reconcileLocked(walletId, current(walletId), entry.keytag)?.let(results::add)
            }
        }
        if (transactions?.closeAvailable == true) observeLocked(walletId)
        results
    }

    private suspend fun reconcileLocked(walletId: WalletId, initial: ChannelCollectionV4, keytag: ProtocolKeytag): ChannelRemoteResult? {
        val current = requireNotNull(initial.channels[keytag.value])
        if (current.pending?.isExit() == true || current.confirmedReturnOperation != null) {
            return reconcileExit(walletId, initial, current)
        }
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
            val failedState = when (pending.action) {
                is ChannelAction.Open -> ChannelState.Absent
                is ChannelAction.Add -> ChannelState.Open(requireNotNull(pending.priorChannelIdentity))
                else -> error("unsupported unsigned channel transaction")
            }
            return complete(walletId, initial, ChannelRemoteResult(
                pending.operationId, pending.intentHash, pending.keytag, pending.asset,
                state = failedState, status = OperationState.FAILED,
            ))
        }
        val reconciled = remote.reconcile(walletId, pending, writer)
        val result = if (reconciled?.status == OperationState.SUBMITTED && pending.payload is ChannelPayload.Payment) {
            remote.mutate(walletId, pending, writer)
        } else reconciled ?: run {
            if (pending.state in setOf(OperationState.SUBMITTED, OperationState.COMPLETED, OperationState.FAILED)) return null
            val authorizer = transaction?.let {
                require(
                    pending.action is ChannelAction.Open && it.intent is CardanoIntent.OpenChannel ||
                        pending.action is ChannelAction.Add && it.intent is CardanoIntent.AddChannelFunds,
                )
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

    private suspend fun reconcileExit(
        walletId: WalletId,
        initial: ChannelCollectionV4,
        entry: ChannelSnapshot,
    ): ChannelRemoteResult? {
        val authorizer = closeAuthorizer()
        val pending = entry.pending ?: requireNotNull(entry.confirmedReturnOperation)
        val transaction = pending.payload as ChannelPayload.Transaction
        if (transaction.signedTransaction.isEmpty()) {
            backup.requireVerifiedWriter(walletId)
            val result = failedExit(pending)
            completeExit(walletId, initial, result)
            observeLocked(walletId)
            return result
        }
        // A previously proved return is observation-only recovery: never replay its financial bytes.
        if (entry.confirmedReturnOperation != null) {
            val confirmation = authorizer.confirmOperation(walletId, pending)
            if (confirmation == null) {
                demoteReturn(walletId, initial, entry, pending)
                return null
            }
            val (result, proof) = confirmation
            if (entry.state is ChannelState.FundsReturned && result.status == OperationState.COMPLETED) {
                requireNotNull(proof)
                if (proof.confirmationDepth < 2_160) return result
                val terminal = initial.withEntry(entry.copy(
                    returnProof = proof,
                    confirmedReturnOperation = null,
                    history = entry.history.map { if (it.operationId == pending.operationId) result else it },
                ))
                backup.requireVerifiedWriter(walletId)
                backup.commit(walletId, terminal)
                persist(walletId, terminal)
                return result
            }
            backup.requireVerifiedWriter(walletId)
            return finishExit(walletId, initial, entry, pending, result, proof)
        }
        val writer = backup.requireVerifiedWriter(walletId)
        val remoteResult = try {
            remote.reconcile(walletId, pending, writer)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val proof = authorizer.confirmOperation(walletId, pending)
            if (proof != null) return finishExit(walletId, initial, entry, pending, proof.first, proof.second)
            throw error
        }
        authorizer.confirmOperation(walletId, pending)?.let {
            return finishExit(walletId, initial, entry, pending, it.first, it.second)
        }
        if (remoteResult != null) {
            if (remoteResult.status != OperationState.FAILED && entry.history.any {
                it.operationId == pending.operationId && it.status == OperationState.COMPLETED
            }) return null
            if (remoteResult.status == OperationState.FAILED) return completeExit(walletId, initial, remoteResult)
            return saveExitProgress(walletId, initial, entry, pending, remoteResult)
        }
        if (authorizer.expiredAndUnspent(walletId, pending)) {
            return completeExit(walletId, initial, failedExit(pending).copy(
                failureMessage = "Transaction expired without submission. Review a new transaction.",
            ))
        }
        // A saved terminal result must be re-proved, never submitted again.
        if (pending.state in setOf(OperationState.COMPLETED, OperationState.FAILED) ||
            entry.history.any { it.operationId == pending.operationId && it.status == OperationState.COMPLETED }) return null
        authorizer.validateReplay(walletId, pending)
        backup.writeAhead(walletId, initial)
        authorizer.requireAvailable(walletId)
        val result = try {
            remote.mutate(walletId, pending, writer)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                persist(walletId, initial.withEntry(entry.copy(
                    pending = pending.copy(state = OperationState.PENDING_RECONCILIATION),
                )))
            }
            throw error
        }
        return completeExit(walletId, initial, result)
    }

    private fun failedExit(operation: PreparedChannelOperation) = ChannelRemoteResult(
        operation.operationId, operation.intentHash, operation.keytag, operation.asset,
        state = requireNotNull(operation.priorChannelState), status = OperationState.FAILED,
        closeStep = (operation.payload as ChannelPayload.Transaction).let { (it.intent as CardanoIntent.CloseChannel).step },
    )

    private suspend fun completeExit(
        walletId: WalletId,
        collection: ChannelCollectionV4,
        response: ChannelRemoteResult,
    ): ChannelRemoteResult {
        val entry = requireNotNull(collection.channels[response.keytag.value])
        val pending = requireNotNull(entry.pending)
        require(response.operationId == pending.operationId && response.intentHash == pending.intentHash &&
            response.keytag == pending.keytag && response.asset == pending.asset)
        if ((pending.payload as ChannelPayload.Transaction).signedTransaction.isNotEmpty()) {
            closeAuthorizer().confirmOperation(walletId, pending)?.let { confirmation ->
                return finishExit(walletId, collection, entry, pending, confirmation.first, confirmation.second)
            }
        }
        if (response.status == OperationState.FAILED) {
            return finishExit(walletId, collection, entry, pending, response.copy(state = requireNotNull(pending.priorChannelState)), null)
        }
        return saveExitProgress(walletId, collection, entry, pending, response)
    }

    private suspend fun saveExitProgress(
        walletId: WalletId, collection: ChannelCollectionV4, entry: ChannelSnapshot,
        pending: PreparedChannelOperation, response: ChannelRemoteResult,
    ): ChannelRemoteResult {
        val result = response.copy(
            state = if (pending.action == ChannelAction.Close) ChannelState.Closing(pending.operationId) else ChannelState.Ending,
            status = if (response.status == OperationState.COMPLETED) OperationState.SUBMITTED else response.status,
            closeStep = (pending.payload as ChannelPayload.Transaction).let { (it.intent as CardanoIntent.CloseChannel).step },
            confirmationDepth = null,
        )
        val next = collection.withEntry(entry.copy(
            pending = pending.copy(state = result.status),
            history = entry.history.filterNot { it.operationId == result.operationId } + result,
        ))
        if (next != collection) persist(walletId, next)
        return result
    }

    private suspend fun finishExit(
        walletId: WalletId, collection: ChannelCollectionV4, entry: ChannelSnapshot,
        operation: PreparedChannelOperation, result: ChannelRemoteResult, proof: ChannelReturnProof?,
    ): ChannelRemoteResult {
        require(result.operationId == operation.operationId && result.intentHash == operation.intentHash &&
            result.keytag == operation.keytag && result.asset == operation.asset)
        val returned = result.status == OperationState.COMPLETED && operation.action is ChannelAction.ReturnFunds
        if (returned) requireNotNull(proof)
        val intermediate = entry.copy(
            state = if (returned || entry.confirmedReturnOperation != null) ChannelState.Ending else entry.state,
            pending = operation.copy(state = result.status),
            history = entry.history.filterNot { it.operationId == operation.operationId } + result,
            returnProof = if (returned) proof else null,
            confirmedReturnOperation = if (returned || entry.confirmedReturnOperation != null) operation else null,
        )
        val saved = collection.withEntry(intermediate)
        persist(walletId, saved)
        var terminal = intermediate.copy(
            pending = null,
            state = if (result.status == OperationState.FAILED) requireNotNull(operation.priorChannelState) else result.state,
            spendableBalance = if (result.status == OperationState.FAILED) requireNotNull(operation.priorSpendableBalance)
                else requireNotNull(operation.resultingSpendableBalance),
            chainObservation = if (returned) null else intermediate.chainObservation,
            confirmedReturnOperation = if (returned && requireNotNull(proof).confirmationDepth < 2_160) operation else null,
        )
        if (!returned) {
            val observed = closeAuthorizer().observe(walletId, listOf(terminal))[terminal.keytag]
            terminal = applyObservation(terminal, observed)
        }
        val completed = saved.withEntry(terminal)
        backup.commit(walletId, completed)
        persist(walletId, completed)
        return result
    }

    private suspend fun demoteReturn(
        walletId: WalletId, collection: ChannelCollectionV4, entry: ChannelSnapshot, operation: PreparedChannelOperation,
    ) {
        val next = collection.withEntry(entry.copy(
            state = ChannelState.Ending,
            pending = operation.copy(state = OperationState.PENDING_RECONCILIATION),
            spendableBalance = requireNotNull(operation.priorSpendableBalance),
            returnProof = entry.returnProof?.copy(confirmationDepth = 0),
            chainObservation = null,
            history = entry.history.map { if (it.operationId == operation.operationId) it.copy(
                status = OperationState.PENDING_RECONCILIATION, state = ChannelState.Ending, confirmationDepth = 0,
            ) else it },
        ))
        if (next != collection) persist(walletId, next)
    }

    private suspend fun observeLocked(walletId: WalletId) {
        val collection = current(walletId)
        val active = collection.channels.values.filter { it.state != ChannelState.Absent && it.state !is ChannelState.FundsReturned }
        if (active.isEmpty()) return
        val observations = closeAuthorizer().observe(walletId, active)
        var next = collection
        for (entry in active) next = next.withEntry(applyObservation(entry, observations[entry.keytag]))
        if (next != collection) persist(walletId, next)
    }

    private fun applyObservation(entry: ChannelSnapshot, observation: ChannelChainObservation?): ChannelSnapshot {
        if (entry.returnProof != null || entry.confirmedReturnOperation != null) return entry
        if (observation == null) return entry.copy(chainObservation = null)
        val old = entry.chainObservation
        val stable = if (old != null && old.output == observation.output && old.datum == observation.datum &&
            old.canReturn == observation.canReturn && old.returnAfterEpochMillis == observation.returnAfterEpochMillis &&
            depthMilestone(old.confirmationDepth) == depthMilestone(observation.confirmationDepth)) old else observation
        val state = if (entry.pending != null || observation.confirmationDepth < 5) entry.state else when (observation.datum.stage) {
            is ChannelDatumStage.Opened -> if (entry.state is ChannelState.Open) entry.state else ChannelState.Open(observation.output.transactionId)
            is ChannelDatumStage.Closed -> ChannelState.Closed
            is ChannelDatumStage.Responded -> ChannelState.Responded
        }
        return entry.copy(state = state, chainObservation = stable)
    }

    private fun depthMilestone(depth: Long) = when {
        depth >= 2_160 -> 2
        depth >= 5 -> 1
        else -> 0
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
            is ChannelAction.Open -> state == ChannelState.Absent
            is ChannelAction.ReturnFunds -> when (action.step) {
                CloseChannelStep.ELAPSE -> state == ChannelState.Closed
                CloseChannelStep.END -> state == ChannelState.Responded
                CloseChannelStep.CLOSE -> false
            }
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
        require(response.transactionId == null || response.transactionId == payload.expectedTransactionId)
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
                ChannelAction.Close, is ChannelAction.ReturnFunds -> requireNotNull(operation.priorChannelState)
                else -> operation.priorChannelIdentity?.let(ChannelState::Open) ?: ChannelState.Absent
            }
            status != OperationState.COMPLETED -> when (operation.action) {
                is ChannelAction.Open -> ChannelState.Opening(operation.operationId)
                ChannelAction.Close -> ChannelState.Closing(operation.operationId)
                is ChannelAction.ReturnFunds -> ChannelState.Ending
                else -> operation.priorChannelIdentity?.let(ChannelState::Open) ?: ChannelState.Absent
            }
            operation.action is ChannelAction.Open -> ChannelState.Open(remoteId)
            operation.action == ChannelAction.Close -> ChannelState.Closed
            operation.action is ChannelAction.ReturnFunds -> ChannelState.Ending
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
            closeStep = (payload.intent as? CardanoIntent.CloseChannel)?.step,
            confirmationDepth = response.depth,
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
