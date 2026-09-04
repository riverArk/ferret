package io.riverark.ferret.core.channel

import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.PendingOperation
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
data class ChannelSnapshot(
    val state: ChannelState,
    val pending: PendingOperation? = null,
    val action: ChannelAction? = null,
)

data class MutationResult(val remoteId: String, val state: ChannelState)

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
    suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction, writer: WriterLease): MutationResult
    suspend fun reconcile(walletId: WalletId, operation: PendingOperation, writer: WriterLease): MutationResult?
}

class ChannelRepository(
    private val wallets: WalletRepository,
    private val journal: ChannelJournal,
    private val backup: ChannelBackupProtocol,
    private val remote: ChannelRemote,
) {
    private val mutableSnapshots = MutableStateFlow<Map<WalletId, ChannelSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<WalletId, ChannelSnapshot>> = mutableSnapshots.asStateFlow()

    suspend fun load(walletId: WalletId) = wallets.withWalletLock(walletId) {
        publish(walletId, journal.load(walletId))
    }

    suspend fun mutate(walletId: WalletId, operationId: String, intentHash: String, action: ChannelAction): MutationResult =
        wallets.withWalletLock(walletId) {
            val current = mutableSnapshots.value[walletId] ?: journal.load(walletId)
            require(current.pending == null) { "unresolved operation" }
            requireAllowed(current.state, action)
            backup.requireVerifiedWriter(walletId)
            val proposed = current.copy(
                pending = PendingOperation(operationId, intentHash, OperationState.PROPOSED),
                action = action,
                state = when (action) {
                    is ChannelAction.Open -> ChannelState.Opening(operationId)
                    ChannelAction.Close -> ChannelState.Closing(operationId)
                    else -> current.state
                },
            )
            persist(walletId, proposed)
            backup.writeAhead(walletId, proposed)
            val armed = proposed.copy(pending = proposed.pending!!.copy(state = OperationState.WRITE_AHEAD_VERIFIED))
            persist(walletId, armed)
            val writer = backup.requireVerifiedWriter(walletId)

            val result = try {
                remote.mutate(walletId, operationId, action, writer)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    persist(walletId, armed.copy(pending = armed.pending!!.copy(state = OperationState.PENDING_RECONCILIATION)))
                }
                throw error
            }
            complete(walletId, armed, result)
        }

    suspend fun reconcile(walletId: WalletId): MutationResult? = wallets.withWalletLock(walletId) {
        val current = mutableSnapshots.value[walletId] ?: journal.load(walletId)
        val pending = current.pending ?: return@withWalletLock null
        var replayBase = current
        var writer = backup.requireVerifiedWriter(walletId)
        val reconciled = remote.reconcile(walletId, pending, writer)
        val result = reconciled ?: run {
            if (pending.state == OperationState.SUBMITTED || pending.state == OperationState.COMPLETED || pending.state == OperationState.FAILED) {
                return@withWalletLock null
            }
            if (pending.state == OperationState.PROPOSED) {
                backup.writeAhead(walletId, current)
                replayBase = current.copy(pending = pending.copy(state = OperationState.WRITE_AHEAD_VERIFIED))
                persist(walletId, replayBase)
                writer = backup.requireVerifiedWriter(walletId)
            }
            val action = requireNotNull(replayBase.action) { "pending channel action is unavailable" }
            try {
                remote.mutate(walletId, pending.id, action, writer)
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

    private suspend fun complete(walletId: WalletId, current: ChannelSnapshot, result: MutationResult): MutationResult {
        val submitted = current.copy(pending = current.pending!!.copy(
            state = OperationState.SUBMITTED,
            remoteId = result.remoteId,
        ))
        persist(walletId, submitted)
        val terminal = ChannelSnapshot(result.state)
        try {
            backup.commit(walletId, terminal)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                persist(walletId, submitted.copy(
                    pending = submitted.pending!!.copy(state = OperationState.PENDING_RECONCILIATION),
                ))
            }
            throw error
        }
        persist(walletId, terminal)
        return result
    }

    private suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) {
        journal.persist(walletId, snapshot)
        publish(walletId, snapshot)
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
