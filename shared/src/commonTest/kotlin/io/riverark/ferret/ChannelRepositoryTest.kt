package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelBackupProtocol
import io.riverark.ferret.core.channel.ChannelJournal
import io.riverark.ferret.core.channel.ChannelRemote
import io.riverark.ferret.core.channel.ChannelRepository
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.MutationResult
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.PendingOperation
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class ChannelRepositoryTest {
    private val walletId = WalletId("preprod-${"0".repeat(56)}")

    @Test fun repositoryRejectsIllegalPaymentWithoutUi() = runBlocking {
        var stored = ChannelSnapshot(ChannelState.Absent)
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) { stored = snapshot }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = Unit
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction) = MutationResult(operationId, ChannelState.Absent)
                override suspend fun reconcile(walletId: WalletId, operation: PendingOperation) = null
            },
        )
        repository.load(walletId)
        assertFailsWith<IllegalArgumentException> {
            repository.mutate(walletId, "operation", "intent", ChannelAction.Pay("quote", "invoice"))
        }
        Unit
    }
    @Test fun terminalBackupFailureKeepsOperationPendingUntilReconciled() = runBlocking {
        var stored = ChannelSnapshot(ChannelState.Open("channel"))
        var failCommit = true
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) { stored = snapshot }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = Unit
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) {
                    if (failCommit) error("Drive unavailable")
                }
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction) =
                    MutationResult("remote", ChannelState.Closed)
                override suspend fun reconcile(walletId: WalletId, operation: PendingOperation) =
                    MutationResult("remote", ChannelState.Closed)
            },
        )
        repository.load(walletId)
        assertFailsWith<IllegalStateException> {
            repository.mutate(walletId, "operation", "intent", ChannelAction.Close)
        }
        assertEquals(ChannelState.Open("channel"), stored.state)
        assertEquals(OperationState.PENDING_RECONCILIATION, assertNotNull(stored.pending).state)

        failCommit = false
        repository.reconcile(walletId)
        assertEquals(ChannelSnapshot(ChannelState.Closed), stored)
    }
}
