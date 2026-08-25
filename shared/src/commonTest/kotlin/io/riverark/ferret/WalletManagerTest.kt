package io.riverark.ferret

import io.riverark.ferret.core.cardano.DerivedWallet
import io.riverark.ferret.core.model.AppState
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.RecoveryPhraseCodec
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.security.SecureRandomSource
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletSecretV1
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WalletManagerTest {
    @Test
    fun createConfirmRestoreAndRejectDuplicate() = runBlocking {
        val vault = FakeVault()
        val repository = WalletRepository()
        val derivedSeeds = mutableListOf<ByteArray>()
        val manager = WalletManager(
            vault,
            object : SecureRandomSource {
                override fun bytes(size: Int) = ByteArray(size) { 1 }
            },
            FakePhrases,
            { entropy, network ->
                derivedSeeds += entropy
                val credential = entropy.first().toUByte().toString(16).padStart(2, '0').repeat(28)
                DerivedWallet(
                    if (network == CardanoNetwork.MAINNET) "addr1$credential" else "addr_test1$credential",
                    "stake1$credential",
                    credential,
                )
            },
            repository,
        )

        val created = manager.create("Alice", CardanoNetwork.PREPROD)
        assertFalse(created.profile.recoveryPhraseConfirmed)
        assertEquals(created.profile, vault.profiles().single())
        assertEquals(created.profile.id, assertIs<AppState.Ready>(repository.state.value).activeWalletId)
        assertTrue(derivedSeeds.single().all { it == 0.toByte() })

        assertEquals(created.recoveryWords, manager.recoveryWords(created.profile.id))
        assertTrue(vault.lastSeedCallback!!.all { it == 0.toByte() })

        manager.confirmRecoveryPhrase(created.profile.id)
        assertTrue(vault.profiles().single().recoveryPhraseConfirmed)
        assertEquals(created.profile.id, assertIs<AppState.Ready>(repository.state.value).activeWalletId)

        val restorePhrase = List(24) { "restore${it + 1}" }.joinToString(" ")
        val restored = manager.restore("Recovered", CardanoNetwork.PREPROD, restorePhrase)
        assertTrue(restored.recoveryPhraseConfirmed)
        assertEquals(restored.id, assertIs<AppState.Ready>(repository.state.value).activeWalletId)
        assertFailsWith<IllegalArgumentException> {
            manager.restore("Duplicate", CardanoNetwork.PREPROD, restorePhrase)
        }
        assertTrue(derivedSeeds.drop(1).all { seed -> seed.all { it == 0.toByte() } })
    }
}

private object FakePhrases : RecoveryPhraseCodec {
    override fun words(entropy: ByteArray) = List(24) { if (entropy.first() == 1.toByte()) "create${it + 1}" else "restore${it + 1}" }
    override fun entropy(words: List<String>) = ByteArray(32) { if (words.first().startsWith("restore")) 2 else 1 }
}

private class FakeVault : SecureVault {
    private val storedProfiles = mutableListOf<WalletProfile>()
    private val seeds = mutableMapOf<WalletId, ByteArray>()
    private val states = mutableMapOf<WalletId, WalletEncryptedStateV1>()
    var lastSeedCallback: ByteArray? = null
    override val isUnlocked = true
    override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
    override fun lock() = Unit
    override suspend fun profiles() = storedProfiles.toList()
    override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) {
        require(storedProfiles.none { it.id == profile.id })
        storedProfiles += profile
        seeds[profile.id] = secret.entropy.copyOf()
        states[profile.id] = WalletEncryptedStateV1(
            secret.channelRecovery.copyOf(),
            secret.operationJournal.copyOf(),
            secret.backupGeneration,
        )
    }
    override suspend fun updateProfile(profile: WalletProfile) {
        val index = storedProfiles.indexOfFirst { it.id == profile.id }
        require(index >= 0)
        storedProfiles[index] = profile
    }
    override suspend fun renameWallet(walletId: WalletId, name: String) {
        updateProfile(storedProfiles.single { it.id == walletId }.copy(name = name))
    }
    override suspend fun deleteWallet(walletId: WalletId) {
        storedProfiles.removeAll { it.id == walletId }
        seeds.remove(walletId)?.fill(0)
        states.remove(walletId)
    }
    override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
        val seed = seeds.getValue(walletId).copyOf()
        lastSeedCallback = seed
        return try { action(seed) } finally { seed.fill(0) }
    }
    override suspend fun walletState(walletId: WalletId) = states.getValue(walletId)
    override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
        states[walletId] = state
    }
}
