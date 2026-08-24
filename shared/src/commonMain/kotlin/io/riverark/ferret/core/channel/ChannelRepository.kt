package io.riverark.ferret.core.channel

import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.PendingOperation
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ChannelAction {
    data class Open(val amount: Long) : ChannelAction
    data class Add(val amount: Long) : ChannelAction
    data class Pay(val quoteId: String, val invoiceHash: String) : ChannelAction
    data object Close : ChannelAction
    data object Squash : ChannelAction
}

data class ChannelSnapshot(val state: ChannelState, val pending: PendingOperation? = null)

data class MutationResult(val remoteId: String, val state: ChannelState)

interface ChannelJournal {
    suspend fun load(walletId: WalletId): ChannelSnapshot
    suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot)
}

interface ChannelBackupProtocol {
    suspend fun requireVerifiedWriter(walletId: WalletId)
    suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot)
    suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot)
}

interface ChannelRemote {
    suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction): MutationResult
    suspend fun reconcile(walletId: WalletId, operation: PendingOperation): MutationResult?
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
            val proposed = current.copy(pending = PendingOperation(operationId, intentHash, OperationState.PROPOSED))
            persist(walletId, proposed)
            backup.writeAhead(walletId, proposed)
            val armed = proposed.copy(pending = proposed.pending!!.copy(state = OperationState.WRITE_AHEAD_VERIFIED))
            persist(walletId, armed)

            val result = try {
                remote.mutate(walletId, operationId, action)
            } catch (cancelled: Throwable) {
                val pending = armed.copy(pending = armed.pending!!.copy(state = OperationState.PENDING_RECONCILIATION))
                persist(walletId, pending)
                throw cancelled
            }
            val terminal = ChannelSnapshot(result.state, null)
            persist(walletId, terminal)
            backup.commit(walletId, terminal)
            result
        }

    suspend fun reconcile(walletId: WalletId): MutationResult? = wallets.withWalletLock(walletId) {
        val current = mutableSnapshots.value[walletId] ?: journal.load(walletId)
        val pending = current.pending ?: return@withWalletLock null
        backup.requireVerifiedWriter(walletId)
        val result = remote.reconcile(walletId, pending) ?: return@withWalletLock null
        val terminal = ChannelSnapshot(result.state, null)
        persist(walletId, terminal)
        backup.commit(walletId, terminal)
        result
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
