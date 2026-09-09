package io.riverark.ferret.core.backup

import io.riverark.ferret.core.channel.AdaptorPayRequest
import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelPayload
import io.riverark.ferret.core.channel.ChannelPreview
import io.riverark.ferret.core.channel.ChannelRemote
import io.riverark.ferret.core.channel.ChannelRemoteResult
import io.riverark.ferret.core.channel.ChannelRepository
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.VaultChannelJournal
import io.riverark.ferret.core.channel.DriveChannelBackupProtocol
import io.riverark.ferret.core.channel.WriterLease
import io.riverark.ferret.core.channel.ChequeBodyWire
import io.riverark.ferret.core.channel.Hex32
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.channel.ProtocolDurationWire
import io.riverark.ferret.core.channel.PreparedChannelOperation
import io.riverark.ferret.core.channel.VaultPaymentStore
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.security.AndroidBackupCrypto
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class DriveBackupRepositoryTest {
    @Test fun roundTripsAndVerifiesGenerationsBeforeDeletion() = runBlocking {
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val repository = DriveBackupRepository(drive, crypto)
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val seed = ByteArray(32) { it.toByte() }
        val first = repository.write(walletId, seed, 1, 1, ByteArray(32), 1, "write-ahead".encodeToByteArray())
        val second = repository.write(walletId, seed, 1, 2, crypto.sha256(first.ciphertext), 2, "terminal".encodeToByteArray())
        val takeover = repository.write(walletId, seed, 2, 1, ByteArray(32), 3, "takeover".encodeToByteArray())

        assertContentEquals("takeover".encodeToByteArray(), repository.decrypt(seed, repository.verifyChain(repository.discover(walletId, seed))))
        assertTrue(repository.verifyChain(listOf(first, second, takeover)).ciphertext.contentEquals(takeover.ciphertext))
        assertFails { repository.verifyChain(listOf(first, second, second.copy(ciphertext = second.ciphertext + 1))) }
        assertFails { repository.decrypt(seed, takeover.copy(ciphertext = takeover.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })) }

        repository.deleteAll(walletId, seed)
        assertTrue(drive.list("ferret-").isEmpty())
        seed.fill(0)
    }
    @Test fun initializesOnceThenVerifiesTheStoredBackup() = runBlocking {
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val coordinator = WalletBackupCoordinator(
            FakeVault(walletId, ByteArray(32) { it.toByte() }),
            DriveBackupRepository(drive, crypto),
            crypto,
            { 1L },
        )

        assertEquals(1L, coordinator.initializeOrVerify(walletId, "absent".encodeToByteArray()).sequence)
        assertEquals(1L, coordinator.initializeOrVerify(walletId, "ignored".encodeToByteArray()).sequence)
        assertEquals(1, drive.list("ferret-").size)
    }

    @Test fun verifiedDeletionClearsRemoteBackupAndLocalCheckpoint() = runBlocking {
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val coordinator = WalletBackupCoordinator(
            FakeVault(walletId, ByteArray(32) { it.toByte() }),
            DriveBackupRepository(drive, crypto),
            crypto,
            { 1L },
        )
        coordinator.initialize(walletId, "absent".encodeToByteArray())

        coordinator.delete(walletId)

        assertTrue(drive.list("ferret-").isEmpty())
        assertEquals(null, coordinator.checkpoint(walletId))
    }

    @Test fun detectsAStaleWriterAndTakesOverFromTheLatestVerifiedState() = runBlocking {
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val seed = ByteArray(32) { it.toByte() }
        val firstVault = FakeVault(walletId, seed)
        val first = WalletBackupCoordinator(firstVault, DriveBackupRepository(drive, crypto), crypto) { 1L }
        first.initialize(walletId, "first".encodeToByteArray())

        val secondVault = FakeVault(walletId, seed)
        val second = WalletBackupCoordinator(secondVault, DriveBackupRepository(drive, crypto), crypto) { 2L }
        second.restore(walletId)
        first.writeNext(walletId, "latest".encodeToByteArray())

        var writerClaimed = false
        val protocol = DriveChannelBackupProtocol(second, requireWriter = { _, checkpoint ->
            writerClaimed = true
            WriterLease(
                "a".repeat(64),
                checkpoint.generation,
                checkpoint.ciphertextHash.toHex(),
                "b".repeat(64),
                1_000,
            )
        })
        val stale = assertFailsWith<StaleBackupWriterException> { protocol.requireVerifiedWriter(walletId) }
        assertEquals(1L, stale.remoteGeneration)
        assertEquals(2L, stale.remoteSequence)
        assertFalse(writerClaimed)

        val takeover = second.takeover(walletId)
        assertEquals(2L, takeover.generation)
        assertEquals(1L, takeover.sequence)
        assertContentEquals("latest".encodeToByteArray(), takeover.channelSnapshot)
        assertEquals(2L, second.verify(walletId).generation)
        assertEquals(2L, protocol.requireVerifiedWriter(walletId).generation)
        assertTrue(writerClaimed)
        seed.fill(0)
    }

    @Test fun restoresEncryptedChannelSnapshotIntoTheLocalJournal() = runBlocking {
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val snapshot = ChannelSnapshot(ChannelState.Open("channel-1"))
        WalletBackupCoordinator(
            FakeVault(walletId, ByteArray(32) { it.toByte() }),
            DriveBackupRepository(drive, crypto),
            crypto,
            { 1L },
        ).initialize(walletId, Json.encodeToString(snapshot).encodeToByteArray())
        val restoredVault = FakeVault(walletId, ByteArray(32) { it.toByte() })
        val checkpoint = WalletBackupCoordinator(
            restoredVault,
            DriveBackupRepository(drive, crypto),
            crypto,
            { 2L },
        ).restore(walletId)

        val journal = VaultChannelJournal(restoredVault)
        assertEquals(snapshot, journal.restoreFromBackup(walletId, checkpoint.channelSnapshot))
        assertEquals(snapshot, journal.load(walletId))
        checkpoint.ciphertextHash.fill(0)
        checkpoint.channelSnapshot.fill(0)
    }

    @Test fun adoptsAnExactlyMatchingInterruptedOwnWrite() = runBlocking {
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val vault = FakeVault(walletId, ByteArray(32) { it.toByte() }, failAtUpdate = 2)
        val coordinator = WalletBackupCoordinator(
            vault,
            DriveBackupRepository(FakeDrive(), AndroidBackupCrypto()),
            AndroidBackupCrypto(),
            { 1L },
        )
        assertFailsWith<IllegalStateException> {
            coordinator.initialize(walletId, "candidate".encodeToByteArray())
        }
        assertTrue(coordinator.checkpoint(walletId)!!.pending)

        vault.failAtUpdate = null
        val recovered = coordinator.verify(walletId)
        assertFalse(recovered.pending)
        assertContentEquals("candidate".encodeToByteArray(), recovered.channelSnapshot)
        recovered.ciphertextHash.fill(0)
        recovered.channelSnapshot.fill(0)
    }

    @Test fun terminalChannelAndPaymentStateRestoresFromLatestEncryptedBackup() = runBlocking {
        val walletId = WalletId("mainnet-" + "00".repeat(28))
        val seed = ByteArray(32) { it.toByte() }
        val drive = FakeDrive()
        val vault = FakeVault(walletId, seed)
        val payments = VaultPaymentStore(vault)
        val journal = VaultChannelJournal(vault, payments)
        journal.persist(walletId, ChannelSnapshot(ChannelState.Absent))
        val coordinator = WalletBackupCoordinator(vault, DriveBackupRepository(drive, AndroidBackupCrypto()), AndroidBackupCrypto()) { 1L }
        coordinator.initialize(walletId, journal.backupSnapshot(walletId))
        val backup = DriveChannelBackupProtocol(coordinator, { _, checkpoint ->
            WriterLease("a".repeat(64), checkpoint.generation, checkpoint.ciphertextHash.toHex(), "b".repeat(64), 1_000)
        }, payments)
        val repository = ChannelRepository(
            WalletRepository(),
            journal,
            backup,
            terminalRemote(OperationState.COMPLETED) { operation ->
                ChannelState.Open(operation.operationId)
            },
        )
        repository.load(walletId)
        val open = operation(ChannelAction.Open(3_000_000), resultingBalance = 2_500_000)
        repository.submit(walletId, preview(open))

        var restored = restore(walletId, seed, drive)
        assertEquals(ChannelState.Open(open.operationId), restored.journal.load(walletId).state)
        assertEquals(Lovelace(2_500_000), restored.journal.load(walletId).spendableBalance)
        assertEquals(null, restored.journal.load(walletId).pending)
        assertEquals(listOf(open.operationId), restored.journal.load(walletId).history.map { it.operationId })

        val paymentHash = "c".repeat(64)
        val quote = PaymentQuote("quote", Lovelace(500_000), 1_000, Lovelace(20_000), Lovelace(30_000), 9_999, paymentHash)
        val payment = operation(
            ChannelAction.Pay("quote", paymentHash),
            priorChannelIdentity = open.operationId,
            resultingBalance = 1_950_000,
            payload = ChannelPayload.Payment(
                byteArrayOf(7),
                "lnbc1fixture",
                paymentHash,
                "d".repeat(64),
                AdaptorPayRequest(
                    ChequeBodyWire(0, 550_000, ProtocolDurationWire.fromMillis(1), Hex32(paymentHash)),
                    "e".repeat(128),
                    "lnbc1fixture",
                ),
                quote,
            ),
        )
        val paymentRepository = ChannelRepository(
            WalletRepository(),
            journal,
            backup,
            terminalRemote(OperationState.COMPLETED) { ChannelState.Open(open.operationId) },
            payments,
        )
        paymentRepository.load(walletId)
        paymentRepository.submit(walletId, preview(payment))

        restored = restore(walletId, seed, drive)
        val paymentSnapshot = restored.journal.load(walletId)
        assertEquals(ChannelState.Open(open.operationId), paymentSnapshot.state)
        assertEquals(Lovelace(1_950_000), paymentSnapshot.spendableBalance)
        assertEquals(null, paymentSnapshot.pending)
        assertEquals(true, restored.payments.isPaid(walletId, paymentHash))
        assertEquals(null, restored.payments.pending(walletId))
        assertEquals(payment.operationId, assertNotNull(restored.payments.receipt(walletId, payment.operationId)).operationId)
        assertEquals(123L, restored.payments.history(walletId).single().timestampEpochMillis)
        assertFails { restored.payments.recordPending(walletId, io.riverark.ferret.core.channel.PendingPaymentV1("other", paymentHash, quote, 999)) }
        seed.fill(0)
    }

    @Test fun rejectedTerminalStateRestoresWithoutChangingBalanceOrInventingReceipt() = runBlocking {
        val walletId = WalletId("mainnet-" + "00".repeat(28))
        val seed = ByteArray(32) { it.toByte() }
        val drive = FakeDrive()
        val vault = FakeVault(walletId, seed)
        val payments = VaultPaymentStore(vault)
        val journal = VaultChannelJournal(vault, payments)
        journal.persist(walletId, ChannelSnapshot(ChannelState.Open("channel"), spendableBalance = Lovelace(2_500_000)))
        val coordinator = WalletBackupCoordinator(vault, DriveBackupRepository(drive, AndroidBackupCrypto()), AndroidBackupCrypto()) { 1L }
        coordinator.initialize(walletId, journal.backupSnapshot(walletId))
        val backup = DriveChannelBackupProtocol(coordinator, { _, checkpoint ->
            WriterLease("a".repeat(64), checkpoint.generation, checkpoint.ciphertextHash.toHex(), "b".repeat(64), 1_000)
        }, payments)
        val close = operation(ChannelAction.Close, priorChannelIdentity = "channel", resultingBalance = 0)
        val repository = ChannelRepository(
            WalletRepository(),
            journal,
            backup,
            terminalRemote(OperationState.FAILED) { ChannelState.Open("channel") },
            payments,
        )
        repository.load(walletId)
        repository.submit(walletId, preview(close))

        val restored = restore(walletId, seed, drive)
        assertEquals(ChannelState.Open("channel"), restored.journal.load(walletId).state)
        assertEquals(Lovelace(2_500_000), restored.journal.load(walletId).spendableBalance)
        assertEquals(null, restored.journal.load(walletId).pending)
        assertEquals(emptyList(), restored.payments.history(walletId))
        seed.fill(0)
    }

    private fun operation(
        action: ChannelAction,
        priorChannelIdentity: String? = null,
        resultingBalance: Long,
        payload: ChannelPayload = ChannelPayload.Protocol(byteArrayOf(1, 2, 3)),
    ) = PreparedChannelOperation(
        "00000000-0000-4000-8000-000000000000",
        "f".repeat(64),
        action,
        priorChannelIdentity,
        123,
        payload,
        resultingSpendableBalance = Lovelace(resultingBalance),
    )

    private fun preview(operation: PreparedChannelOperation) = ChannelPreview(
        operation,
        Lovelace(500_000),
        Lovelace(50_000),
        Lovelace(100_000),
        Lovelace(1_000_000),
        Lovelace(2_000_000),
        Lovelace(500_000),
        requireNotNull(operation.resultingSpendableBalance),
        CardanoNetwork.MAINNET,
    )

    private fun terminalRemote(
        status: OperationState,
        state: (PreparedChannelOperation) -> ChannelState,
    ) = object : ChannelRemote {
        override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
            ChannelRemoteResult(operation.operationId, operation.intentHash, state = state(operation), status = status)
        override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
            error("lookup not expected")
    }

    private suspend fun restore(walletId: WalletId, seed: ByteArray, drive: FakeDrive): Restored {
        val vault = FakeVault(walletId, seed)
        val payments = VaultPaymentStore(vault)
        val journal = VaultChannelJournal(vault, payments)
        val checkpoint = WalletBackupCoordinator(
            vault,
            DriveBackupRepository(drive, AndroidBackupCrypto()),
            AndroidBackupCrypto(),
            { 2L },
        ).restore(walletId)
        journal.restoreFromBackup(walletId, checkpoint.channelSnapshot)
        checkpoint.ciphertextHash.fill(0)
        checkpoint.channelSnapshot.fill(0)
        return Restored(journal, payments)
    }

    private data class Restored(val journal: VaultChannelJournal, val payments: VaultPaymentStore)


    private class FakeDrive : DriveAppDataClient {
        private val files = mutableMapOf<String, ByteArray>()
        override suspend fun list(prefix: String) = files.keys.filter { it.startsWith(prefix) }.map { DriveObject(it, 0) }
        override suspend fun put(name: String, bytes: ByteArray) { files[name] = bytes.copyOf() }
        override suspend fun get(name: String) = files.getValue(name).copyOf()
        override suspend fun delete(name: String) { files.remove(name)?.fill(0) }
    }
    private class FakeVault(
        private val walletId: WalletId,
        private val seed: ByteArray,
        var failAtUpdate: Int? = null,
    ) : SecureVault {
        private var state = WalletEncryptedStateV1()
        private var updates = 0
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles(): List<WalletProfile> = listOf(
            WalletProfile(
                walletId,
                "Wallet",
                if (walletId.value.startsWith("mainnet-")) CardanoNetwork.MAINNET else CardanoNetwork.PREPROD,
                if (walletId.value.startsWith("mainnet-")) "addr1wallet" else "addr_test1wallet",
                if (walletId.value.startsWith("mainnet-")) "stake1wallet" else "stake_test1wallet",
            ),
        )
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
            require(walletId == this.walletId)
            val copy = seed.copyOf()
            return try {
                action(copy)
            } finally {
                copy.fill(0)
            }
        }
        override suspend fun walletState(walletId: WalletId) = state.copy(
            channelRecovery = state.channelRecovery.copyOf(),
            operationJournal = state.operationJournal.copyOf(),
        )
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            updates++
            if (updates == failAtUpdate) error("simulated local checkpoint failure")
            require(walletId == this.walletId)
            this.state = state.copy(
                channelRecovery = state.channelRecovery.copyOf(),
                operationJournal = state.operationJournal.copyOf(),
            )
        }
    }
}

private fun ByteArray.toHex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
