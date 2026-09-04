package io.riverark.ferret.core.backup

import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.VaultChannelJournal
import io.riverark.ferret.core.model.ChannelState
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        val stale = assertFailsWith<StaleBackupWriterException> { second.verify(walletId) }
        assertEquals(1L, stale.remoteGeneration)
        assertEquals(2L, stale.remoteSequence)

        val takeover = second.takeover(walletId)
        assertEquals(2L, takeover.generation)
        assertEquals(1L, takeover.sequence)
        assertContentEquals("latest".encodeToByteArray(), takeover.channelSnapshot)
        assertEquals(2L, second.verify(walletId).generation)
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
    ) : SecureVault {
        private var state = WalletEncryptedStateV1()
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles(): List<WalletProfile> = emptyList()
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
            require(walletId == this.walletId)
            this.state = state.copy(
                channelRecovery = state.channelRecovery.copyOf(),
                operationJournal = state.operationJournal.copyOf(),
            )
        }
    }
}
