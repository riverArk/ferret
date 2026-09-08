package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelBackupProtocol
import io.riverark.ferret.core.channel.ChannelJournal
import io.riverark.ferret.core.channel.ChannelPayload
import io.riverark.ferret.core.channel.ChannelPreview
import io.riverark.ferret.core.channel.ChannelRemote
import io.riverark.ferret.core.channel.ChannelRemoteResult
import io.riverark.ferret.core.channel.ChannelRepository
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.PreparedChannelOperation
import io.riverark.ferret.core.channel.WriterLease
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ChannelRepositoryTest {
    private val walletId = WalletId("preprod-${"0".repeat(56)}")
    private val writer = WriterLease("a".repeat(64), 1, "b".repeat(64), "c".repeat(64), 1_000)

    @Test fun repositoryRejectsIllegalPaymentWithoutUi() = runBlocking {
        var stored = ChannelSnapshot(ChannelState.Absent)
        val repository = repository(stored = { stored }, save = { stored = it })
        repository.load(walletId)

        assertFailsWith<IllegalArgumentException> {
            repository.submit(walletId, preview(ChannelAction.Pay("quote", "invoice")))
        }
        assertEquals(ChannelState.Absent, stored.state)
    }

    @Test fun missingWriterLeasePreventsJournalAndRemoteMutation() = runBlocking {
        val initial = ChannelSnapshot(ChannelState.Absent)
        var stored = initial
        var remoteCalled = false
        val repository = repository(
            stored = { stored },
            save = { stored = it },
            backup = object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease = error("writer lease unavailable")
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            },
            remote = object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease): ChannelRemoteResult {
                    remoteCalled = true
                    error("remote mutation must not run")
                }
                override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) = null
            },
        )
        repository.load(walletId)

        assertFailsWith<IllegalStateException> { repository.submit(walletId, preview(ChannelAction.Open(3_000_000))) }
        assertEquals(initial, stored)
        assertEquals(false, remoteCalled)
    }

    @Test fun terminalBackupFailureKeepsExactOperationUntilReconciled() = runBlocking {
        var stored = ChannelSnapshot(ChannelState.Open("channel"))
        var failCommit = true
        val remote = object : ChannelRemote {
            override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                result(operation, ChannelState.Closed)
            override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                result(operation, ChannelState.Closed)
        }
        val repository = repository(
            stored = { stored },
            save = { stored = it },
            backup = object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) {
                    if (failCommit) error("Drive unavailable")
                }
            },
            remote = remote,
        )
        repository.load(walletId)
        val preview = preview(ChannelAction.Close)

        assertFailsWith<IllegalStateException> { repository.submit(walletId, preview) }
        assertEquals(ChannelState.Closing("operation"), stored.state)
        assertEquals(OperationState.PENDING_RECONCILIATION, assertNotNull(stored.pending).state)
        assertContentEquals(byteArrayOf(1, 2, 3), (stored.pending!!.payload as ChannelPayload.Protocol).cbor)

        failCommit = false
        repository.reconcile(walletId)
        assertEquals(ChannelState.Closed, stored.state)
        assertEquals(null, stored.pending)
        assertEquals(1, stored.history.size)
    }

    @Test fun reclaimsWriteAheadLeaseAndReplaysOneStablePayload() = runBlocking {
        val action = ChannelAction.Open(3_000_000)
        val updatedWriter = writer.copy(token = "d".repeat(64), backupHashHex = "e".repeat(64))
        var stored = ChannelSnapshot(ChannelState.Absent)
        var writeAhead: ChannelSnapshot? = null
        var claims = 0
        val operations = mutableListOf<PreparedChannelOperation>()
        val mutationWriters = mutableListOf<WriterLease>()
        val repository = repository(
            stored = { stored },
            save = { stored = it },
            backup = object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = if (++claims == 1) writer else updatedWriter
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) { writeAhead = snapshot }
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            },
            remote = object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease): ChannelRemoteResult {
                    operations += operation
                    mutationWriters += writer
                    if (operations.size == 1) error("response lost")
                    return result(operation, ChannelState.Open("channel"))
                }
                override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) = null
            },
        )
        repository.load(walletId)
        val preview = preview(action)

        assertFailsWith<IllegalStateException> { repository.submit(walletId, preview) }
        assertEquals(action, assertNotNull(writeAhead).pending!!.action)
        assertEquals(ChannelState.Opening("operation"), assertNotNull(writeAhead).state)
        assertEquals(OperationState.PENDING_RECONCILIATION, assertNotNull(stored.pending).state)

        repository.reconcile(walletId)

        assertEquals(listOf("operation", "operation"), operations.map { it.operationId })
        operations.forEach { assertContentEquals(byteArrayOf(1, 2, 3), (it.payload as ChannelPayload.Protocol).cbor) }
        assertEquals(listOf(updatedWriter, updatedWriter), mutationWriters)
        assertEquals(ChannelState.Open("channel"), stored.state)
        assertEquals(null, stored.pending)
    }

    private fun preview(action: ChannelAction): ChannelPreview {
        val operation = PreparedChannelOperation(
            operationId = "operation",
            intentHash = "intent",
            action = action,
            preparedAtEpochMillis = 123,
            payload = ChannelPayload.Protocol(byteArrayOf(1, 2, 3)),
        )
        return ChannelPreview(
            operation,
            Lovelace(3_000_000),
            Lovelace(100_000),
            Lovelace(200_000),
            Lovelace(1_000_000),
            Lovelace(2_000_000),
            Lovelace(500_000),
            Lovelace(2_500_000),
            CardanoNetwork.PREPROD,
        )
    }

    private fun result(operation: PreparedChannelOperation, state: ChannelState) = ChannelRemoteResult(
        operation.operationId,
        operation.intentHash,
        state = state,
        status = OperationState.COMPLETED,
        verifiedChannelData = "09",
    )

    private fun repository(
        stored: () -> ChannelSnapshot,
        save: (ChannelSnapshot) -> Unit,
        backup: ChannelBackupProtocol = object : ChannelBackupProtocol {
            override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
            override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
        },
        remote: ChannelRemote = object : ChannelRemote {
            override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                result(operation, ChannelState.Absent)
            override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) = null
        },
    ) = ChannelRepository(
        WalletRepository(),
        object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = stored()
            override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) = save(snapshot)
        },
        backup,
        remote,
    )
}
