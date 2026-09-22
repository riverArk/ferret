package io.riverark.ferret.core.backup

import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.VaultChannelJournal
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.security.AndroidBackupCrypto
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletOperationJournalV2
import io.riverark.ferret.core.security.WalletSecretV1
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveBackupRepositoryTest {
    @Test fun roundTripsAndVerifiesGenerationsBeforeDeletion() = runBlocking {
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val repository = DriveBackupRepository(drive, crypto)
        val seed = ByteArray(32) { it.toByte() }
        val first = repository.write(WALLET, seed, 1, 1, ByteArray(32), 1, "write-ahead".encodeToByteArray())
        val second = repository.write(WALLET, seed, 1, 2, crypto.sha256(first.ciphertext), 2, "terminal".encodeToByteArray())
        val takeover = repository.write(WALLET, seed, 2, 1, ByteArray(32), 3, "takeover".encodeToByteArray())

        assertContentEquals("takeover".encodeToByteArray(), repository.decrypt(seed, repository.verifyChain(listOf(first, second, takeover))))
        assertFailsWith<IllegalArgumentException> { repository.verifyChain(listOf(first, second, second.copy(ciphertext = second.ciphertext + 1))) }
        repository.deleteAll(WALLET, seed)
        assertTrue(drive.list("ferret-").isEmpty())
        seed.fill(0)
    }

    @Test fun restoreInstallsCheckpointAndCompleteCollectionInOneWalletWrite() = runBlocking {
        val drive = FakeDrive()
        val sourceVault = FakeVault(SEED)
        val sourceJournal = VaultChannelJournal(sourceVault, CATALOG)
        val source = coordinator(sourceVault, drive, sourceJournal, 1)
        source.initialize(WALLET, sourceJournal.normalizeBackup(WALLET, Json.encodeToString(COLLECTION).encodeToByteArray()))

        val restoredVault = FakeVault(SEED, l1 = byteArrayOf(7, 8))
        val restoredJournal = VaultChannelJournal(restoredVault, CATALOG)
        val restored = coordinator(restoredVault, drive, restoredJournal, 2)
        restored.restore(WALLET)

        assertEquals(1, restoredVault.updates)
        assertCollectionEquals(COLLECTION, restoredJournal.load(WALLET))
        assertFalse(restored.checkpoint(WALLET)!!.pending)
        assertContentEquals(byteArrayOf(7, 8), restoredVault.l1())
    }

    @Test fun interruptedTakeoverResumesWithInstallOnCommitAndNeverCommitsOldCollection() = runBlocking {
        val drive = FakeDrive()
        val firstVault = FakeVault(SEED)
        val firstJournal = VaultChannelJournal(firstVault, CATALOG)
        val first = coordinator(firstVault, drive, firstJournal, 1)
        first.initialize(WALLET, firstJournal.normalizeBackup(WALLET, Json.encodeToString(COLLECTION).encodeToByteArray()))

        val secondVault = FakeVault(SEED)
        val secondJournal = VaultChannelJournal(secondVault, CATALOG)
        val second = coordinator(secondVault, drive, secondJournal, 2)
        second.restore(WALLET)
        val changed = COLLECTION.copy(paidHashes = setOf("22".repeat(32)))
        first.writeNext(WALLET, firstJournal.normalizeBackup(WALLET, Json.encodeToString(changed).encodeToByteArray()))

        secondVault.failNextInstall = true
        assertFailsWith<IllegalStateException> { second.takeover(WALLET) }
        val pending = second.checkpoint(WALLET)!!
        assertTrue(pending.pending && pending.installOnCommit)
        assertCollectionEquals(COLLECTION, secondJournal.load(WALLET))
        pending.clear()

        secondVault.failNextInstall = false
        val resumed = second.verify(WALLET)
        assertFalse(resumed.pending || resumed.installOnCommit)
        assertCollectionEquals(changed, secondJournal.load(WALLET))
        resumed.clear()
    }

    private fun assertCollectionEquals(expected: ChannelCollectionV4, actual: ChannelCollectionV4) {
        assertEquals(expected.schema, actual.schema)
        assertEquals(expected.walletId, actual.walletId)
        assertEquals(expected.catalogDigest, actual.catalogDigest)
        assertEquals(expected.channels, actual.channels)
        assertEquals(expected.paidHashes, actual.paidHashes)
        assertContentEquals(expected.unresolvedLegacy, actual.unresolvedLegacy)
    }

    private fun coordinator(vault: FakeVault, drive: FakeDrive, journal: VaultChannelJournal, now: Long) =
        WalletBackupCoordinator(
            vault,
            DriveBackupRepository(drive, AndroidBackupCrypto()),
            AndroidBackupCrypto(),
            { now },
            journal::normalizeBackup,
            { walletId, bytes, checkpoint -> journal.installBackup(walletId, bytes, checkpoint) },
        )

    private class FakeDrive : DriveAppDataClient {
        private val files = mutableMapOf<String, ByteArray>()
        override suspend fun list(prefix: String) = files.keys.filter { it.startsWith(prefix) }.map { DriveObject(it, 0) }
        override suspend fun put(name: String, bytes: ByteArray) { files[name] = bytes.copyOf() }
        override suspend fun get(name: String) = files.getValue(name).copyOf()
        override suspend fun delete(name: String) { files.remove(name)?.fill(0) }
    }

    private class FakeVault(
        private val seed: ByteArray,
        l1: ByteArray = byteArrayOf(),
    ) : SecureVault {
        private var state = WalletEncryptedStateV1(
            operationJournal = Json.encodeToString(WalletOperationJournalV2(l1 = l1)).encodeToByteArray(),
        )
        var updates = 0
            private set
        var failNextInstall = false
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = listOf(WalletProfile(WALLET, "Wallet", CardanoNetwork.MAINNET, "addr1wallet", "stake1wallet"))
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
            val copy = seed.copyOf()
            return try { action(copy) } finally { copy.fill(0) }
        }
        override suspend fun walletState(walletId: WalletId) = state.copy(
            channelRecovery = state.channelRecovery.copyOf(), operationJournal = state.operationJournal.copyOf(),
        )
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            if (failNextInstall && state.channelRecovery.isNotEmpty() && state.operationJournal.size > this.state.operationJournal.size) {
                error("simulated atomic install failure")
            }
            updates++
            this.state = state.copy(
                channelRecovery = state.channelRecovery.copyOf(), operationJournal = state.operationJournal.copyOf(),
            )
        }
        fun l1(): ByteArray = Json.decodeFromString<WalletOperationJournalV2>(state.operationJournal.decodeToString()).l1
    }

    private fun BackupCheckpointV1.clear() {
        ciphertextHash.fill(0); channelSnapshot.fill(0); previousHash.fill(0); snapshotDigest.fill(0)
    }

    private companion object {
        const val DIGEST = "09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae"
        val WALLET = WalletId("mainnet-" + "00".repeat(28))
        val SEED = ByteArray(32) { it.toByte() }
        val ADA = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, DIGEST)
        val CATALOG = AssetCatalog(
            listOf(
                ADA,
                ChannelAsset("usda", "01".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST),
                ChannelAsset("usdcx", "02".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST),
                ChannelAsset("usdm", "03".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST),
            ),
            DIGEST,
            emptyMap(),
        )
        val COLLECTION = ChannelCollectionV4(walletId = WALLET, catalogDigest = DIGEST)
    }
}
