package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelBackupProtocol
import io.riverark.ferret.core.channel.ChannelJournal
import io.riverark.ferret.core.channel.ChannelRemote
import io.riverark.ferret.core.channel.ChannelRepository
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.MutationResult
import io.riverark.ferret.core.channel.WriterLease
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
    private val writer = WriterLease("a".repeat(64), 1, "b".repeat(64), "c".repeat(64), 1_000)

    @Test fun repositoryRejectsIllegalPaymentWithoutUi() = runBlocking {
        var stored = ChannelSnapshot(ChannelState.Absent)
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) { stored = snapshot }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction, writer: WriterLease) = MutationResult(operationId, ChannelState.Absent)
                override suspend fun reconcile(walletId: WalletId, operation: PendingOperation, writer: WriterLease) = null
            },
        )
        repository.load(walletId)
        assertFailsWith<IllegalArgumentException> {
            repository.mutate(walletId, "operation", "intent", ChannelAction.Pay("quote", "invoice"))
        }
        Unit
    }
    @Test fun missingWriterLeasePreventsJournalAndRemoteMutation() = runBlocking {
        val initial = ChannelSnapshot(ChannelState.Absent)
        var stored = initial
        var remoteCalled = false
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) { stored = snapshot }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease = error("writer lease unavailable")
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction, writer: WriterLease): MutationResult {
                    remoteCalled = true
                    error("remote mutation must not run")
                }
                override suspend fun reconcile(walletId: WalletId, operation: PendingOperation, writer: WriterLease) = null
            },
        )
        repository.load(walletId)

        assertFailsWith<IllegalStateException> {
            repository.mutate(walletId, "operation", "intent", ChannelAction.Open(3_000_000))
        }
        assertEquals(initial, stored)
        assertEquals(false, remoteCalled)
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
                override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) {
                    if (failCommit) error("Drive unavailable")
                }
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction, writer: WriterLease): MutationResult {
                    assertEquals(this@ChannelRepositoryTest.writer, writer)
                    return MutationResult("remote", ChannelState.Closed)
                }
                override suspend fun reconcile(walletId: WalletId, operation: PendingOperation, writer: WriterLease): MutationResult {
                    assertEquals(this@ChannelRepositoryTest.writer, writer)
                    return MutationResult("remote", ChannelState.Closed)
                }
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
