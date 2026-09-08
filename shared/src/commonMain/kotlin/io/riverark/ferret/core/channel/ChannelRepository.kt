package io.riverark.ferret.core.channel

import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@kotlinx.serialization.Serializable
sealed interface ChannelAction {
    @kotlinx.serialization.Serializable data class Open(val amount: Long) : ChannelAction
    @kotlinx.serialization.Serializable data class Add(val amount: Long) : ChannelAction
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
    data class Protocol(val cbor: ByteArray) : ChannelPayload
}

@kotlinx.serialization.Serializable
data class PreparedChannelOperation(
    val operationId: String,
    val intentHash: String,
    val action: ChannelAction,
    val priorChannelIdentity: String? = null,
    val preparedAtEpochMillis: Long,
    val payload: ChannelPayload,
    val keytag: String = "",
    val resultingSpendableBalance: Lovelace? = null,
    val state: OperationState = OperationState.PROPOSED,
)

@kotlinx.serialization.Serializable
data class ChannelRemoteResult(
    val operationId: String,
    val intentHash: String,
    val transactionId: String? = null,
    val state: ChannelState,
    val status: OperationState,
    val verifiedChannelData: String = "",
)

@kotlinx.serialization.Serializable
data class ChannelPreview(
    val operation: PreparedChannelOperation,
    val amount: Lovelace,
    val actualFee: Lovelace,
    val feeBound: Lovelace,
    val sourceChange: Lovelace,
    val ledgerMinAda: Lovelace,
    val protocolReserve: Lovelace,
    val resultingSpendableBalance: Lovelace,
    val network: io.riverark.ferret.core.model.CardanoNetwork,
)

@kotlinx.serialization.Serializable
data class ChannelSnapshot(
    val state: ChannelState,
    val pending: PreparedChannelOperation? = null,
    val verifiedChannelData: String = "",
    val history: List<ChannelRemoteResult> = emptyList(),
    val spendableBalance: Lovelace = Lovelace(0),
)

interface ChannelJournal {
    suspend fun load(walletId: WalletId): ChannelSnapshot
    suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot)
}

interface ChannelBackupProtocol {
    suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease
    suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot)
    suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot)
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
    private val payments: PaymentStore? = null,
    private val newOperationId: () -> String = { error("operation ID generator unavailable") },
) {
    private val mutableSnapshots = MutableStateFlow<Map<WalletId, ChannelSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<WalletId, ChannelSnapshot>> = mutableSnapshots.asStateFlow()

    suspend fun load(walletId: WalletId) = wallets.withWalletLock(walletId) {
        val snapshot = journal.load(walletId)
        ensurePendingPayment(walletId, snapshot)
        publish(walletId, snapshot)
    }

    suspend fun submitPayment(
        walletId: WalletId,
        invoice: String,
        quote: PaymentQuote,
        gateway: PaymentGateway,
        preparedAtEpochMillis: Long,
    ): String = wallets.withWalletLock(walletId) {
        if (walletId !in mutableSnapshots.value) publish(walletId, journal.load(walletId))
        payments?.requireCapacity(walletId)
        val operationId = newOperationId()
        submitLocked(
            walletId,
            gateway.prepare(
                walletId,
                operationId,
                quote.id,
                invoice,
                quote,
                preparedAtEpochMillis,
            ),
        )
    }

    suspend fun submit(walletId: WalletId, preview: ChannelPreview): String =
        wallets.withWalletLock(walletId) { submitLocked(walletId, preview) }

    private suspend fun submitLocked(walletId: WalletId, preview: ChannelPreview): String {
        val current = mutableSnapshots.value[walletId] ?: journal.load(walletId)
        require(current.pending == null) { "unresolved operation" }
        requireAllowed(current.state, preview.operation.action)
        val prepared = preview.operation.copy(
            priorChannelIdentity = preview.operation.priorChannelIdentity
                ?: (current.state as? ChannelState.Open)?.channelId,
            resultingSpendableBalance = preview.operation.resultingSpendableBalance ?: when (preview.operation.action) {
                is ChannelAction.Pay -> current.spendableBalance - preview.amount - preview.actualFee
                else -> preview.resultingSpendableBalance
            },
        )
        require(preview.operation.state == OperationState.PROPOSED)
        backup.requireVerifiedWriter(walletId)
        val proposed = current.copy(
            pending = prepared,
            state = when (preview.operation.action) {
                is ChannelAction.Open -> ChannelState.Opening(preview.operation.operationId)
                ChannelAction.Close -> ChannelState.Closing(preview.operation.operationId)
                else -> current.state
            },
        )
        persist(walletId, proposed)
        ensurePendingPayment(walletId, proposed)
        backup.writeAhead(walletId, proposed)
        val armed = proposed.copy(pending = prepared.copy(state = OperationState.WRITE_AHEAD_VERIFIED))
        persist(walletId, armed)
        val writer = backup.requireVerifiedWriter(walletId)
        val result = try {
            remote.mutate(walletId, armed.pending!!, writer)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                persist(walletId, armed.copy(pending = armed.pending!!.copy(state = OperationState.PENDING_RECONCILIATION)))
            }
            throw error
        }
        complete(walletId, armed, result)
        return preview.operation.operationId
    }

    suspend fun reconcile(walletId: WalletId): ChannelRemoteResult? = wallets.withWalletLock(walletId) {
        val current = mutableSnapshots.value[walletId] ?: journal.load(walletId)
        val pending = current.pending ?: return@withWalletLock null
        var replayBase = current
        var writer = backup.requireVerifiedWriter(walletId)
        val reconciled = remote.reconcile(walletId, pending, writer)
        val result = reconciled ?: run {
            if (pending.state in setOf(OperationState.SUBMITTED, OperationState.COMPLETED, OperationState.FAILED)) {
                return@withWalletLock null
            }
            if (pending.state == OperationState.PROPOSED) {
                backup.writeAhead(walletId, current)
                replayBase = current.copy(pending = pending.copy(state = OperationState.WRITE_AHEAD_VERIFIED))
                persist(walletId, replayBase)
                writer = backup.requireVerifiedWriter(walletId)
            }
            try {
                remote.mutate(walletId, replayBase.pending!!, writer)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    persist(walletId, replayBase.copy(
                        pending = replayBase.pending!!.copy(state = OperationState.PENDING_RECONCILIATION),
                    ))
                }
                throw error
            }
        }
        complete(walletId, replayBase, result)
    }

    private suspend fun complete(
        walletId: WalletId,
        current: ChannelSnapshot,
        result: ChannelRemoteResult,
    ): ChannelRemoteResult {
        val pending = requireNotNull(current.pending)
        require(result.operationId == pending.operationId && result.intentHash == pending.intentHash)
        val persisted = current.copy(
            pending = pending.copy(state = result.status),
            verifiedChannelData = result.verifiedChannelData,
            history = current.history.filterNot { it.operationId == result.operationId } + result,
        )
        persist(walletId, persisted)
        try {
            backup.commit(walletId, persisted)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                persist(walletId, persisted.copy(
                    pending = persisted.pending!!.copy(state = OperationState.PENDING_RECONCILIATION),
                ))
            }
            throw error
        }
        if (result.status == OperationState.COMPLETED && pending.payload is ChannelPayload.Payment) {
            val payment = pending.payload
            payments?.complete(
                walletId,
                io.riverark.ferret.core.model.Receipt(
                    pending.operationId,
                    payment.invoiceHash,
                    payment.quote.amount,
                    payment.quote.routingFee + payment.quote.adaptorFee,
                    true,
                ),
                pending.preparedAtEpochMillis,
            )
        }
        val terminal = if (result.status in setOf(OperationState.COMPLETED, OperationState.FAILED)) {
            persisted.copy(
                state = result.state,
                pending = null,
                spendableBalance = if (result.status == OperationState.COMPLETED) {
                    pending.resultingSpendableBalance ?: persisted.spendableBalance
                } else {
                    persisted.spendableBalance
                },
            )
        } else {
            persisted
        }
        persist(walletId, terminal)
        return result
    }


    private suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) {
        journal.persist(walletId, snapshot)
        publish(walletId, snapshot)
    }

    private suspend fun ensurePendingPayment(walletId: WalletId, snapshot: ChannelSnapshot) {
        val pending = snapshot.pending ?: return
        val payload = pending.payload as? ChannelPayload.Payment ?: return
        val store = payments ?: return
        if (!store.isPaid(walletId, payload.invoiceHash) && store.pending(walletId) == null) {
            store.recordPending(
                walletId,
                PendingPaymentV1(
                    pending.operationId,
                    payload.invoiceHash,
                    payload.quote,
                    pending.preparedAtEpochMillis,
                ),
            )
        }
    }

    private fun publish(walletId: WalletId, snapshot: ChannelSnapshot) {
        mutableSnapshots.value = mutableSnapshots.value + (walletId to snapshot)
    }

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
    ): ChannelRemoteResult {
        return when (val payload = operation.payload) {
            is ChannelPayload.Transaction -> {
                require(payload.signedTransaction.isNotEmpty()) { "signed channel transaction is unavailable" }
                result(
                    operation,
                    adaptor(walletId).submitChannelOperation(
                        ProtocolKeytag(operation.keytag),
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
                adaptor(walletId).pay(ProtocolKeytag(operation.keytag), writer.token, payload.request)
                reconcile(walletId, operation, writer) ?: paymentResult(operation, OperationState.SUBMITTED)
            }
            is ChannelPayload.Protocol -> error("unsupported channel protocol payload")
        }
    }

    override suspend fun reconcile(
        walletId: WalletId,
        operation: PreparedChannelOperation,
        writer: WriterLease,
    ): ChannelRemoteResult? = when (val payload = operation.payload) {
        is ChannelPayload.Transaction -> result(
            operation,
            adaptor(walletId).channelOperation(
                ProtocolKeytag(operation.keytag),
                writer,
                operation.operationId,
            ),
        )
        is ChannelPayload.Payment -> {
            val receipt = adaptor(walletId).receipt(ProtocolKeytag(operation.keytag)) ?: return null
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
                        is ProtocolCheque.Unlocked ->
                            crypto.sha256(signed.body.latch.value.hexBytes()).hex() == payload.invoiceHash
                    }
            } ?: return null
            paymentResult(
                operation,
                if (matching is ProtocolCheque.Unlocked) OperationState.COMPLETED else OperationState.SUBMITTED,
            )
        }
        is ChannelPayload.Protocol -> error("unsupported channel protocol payload")
    }

    private fun result(
        operation: PreparedChannelOperation,
        response: io.riverark.ferret.core.network.L1OperationDto,
    ): ChannelRemoteResult {
        val payload = operation.payload as? ChannelPayload.Transaction
            ?: error("channel transaction payload is required")
        require(
            response.operationId == operation.operationId &&
                response.expectedTransactionId == payload.expectedTransactionId,
        ) { "channel operation identity mismatch" }
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
            response.transactionId,
            state,
            status,
            verifiedChannelData = operation.keytag.ifEmpty { remoteId },
        )
    }

    private fun paymentResult(
        operation: PreparedChannelOperation,
        status: OperationState,
    ) = ChannelRemoteResult(
        operation.operationId,
        operation.intentHash,
        state = ChannelState.Open(requireNotNull(operation.priorChannelIdentity)),
        status = status,
        verifiedChannelData = operation.priorChannelIdentity,
    )

    private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0 && all { it in "0123456789abcdef" })
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
