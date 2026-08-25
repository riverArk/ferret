package io.riverark.ferret

import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.RemovalReadiness
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRemovalManager
import io.riverark.ferret.core.model.WalletRemovalRepository
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WalletRemovalTest {
    @Test fun removalRequiresClosedSettledEmptyWalletAndDeletesBackupFirst() = runBlocking {
        val profile = WalletProfile(WalletId("preprod-" + "00".repeat(28)), "Wallet", CardanoNetwork.PREPROD, "addr_test1wallet", "stake_test1wallet")
        val events = mutableListOf<String>()
        var readiness = RemovalReadiness(profile.copy(channelState = ChannelState.Open("channel")), Lovelace(0), false, true, 2_160)
        val repository = object : WalletRemovalRepository {
            override suspend fun readiness(walletId: WalletId) = readiness
            override suspend fun sweep(walletId: WalletId, destinationAddress: String): String { events += "sweep"; return "transaction" }
            override suspend fun deleteDriveBackup(walletId: WalletId) { events += "drive" }
        }
        val vault = FakeVault(profile) { events += "vault" }
        val manager = WalletRemovalManager(repository, vault)

        assertFailsWith<IllegalArgumentException> { manager.remove(profile.id) }
        readiness = RemovalReadiness(profile, Lovelace(1), false, true, 2_160)
        assertFailsWith<IllegalArgumentException> { manager.sweep(profile.id, "addr1wrongnetwork") }
        assertTrue(events.isEmpty())
        readiness = RemovalReadiness(profile, Lovelace(0), false, true, 2_159)
        assertFailsWith<IllegalArgumentException> { manager.remove(profile.id) }

        readiness = RemovalReadiness(profile, Lovelace(0), false, true, 2_160)
        manager.remove(profile.id)
        assertEquals(listOf("drive", "vault"), events)
        assertTrue(vault.profiles().isEmpty())
    }

    private class FakeVault(profile: WalletProfile, private val onDelete: () -> Unit) : SecureVault {
        private val profiles = mutableListOf(profile)
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = profiles.toList()
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) { onDelete(); profiles.removeAll { it.id == walletId } }
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T = error("not used")
        override suspend fun walletState(walletId: WalletId) = WalletEncryptedStateV1()
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) = error("not used")
    }
}
