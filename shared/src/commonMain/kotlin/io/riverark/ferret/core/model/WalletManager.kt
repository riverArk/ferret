package io.riverark.ferret.core.model

import io.riverark.ferret.core.cardano.DerivedWallet
import io.riverark.ferret.core.security.SecureRandomSource
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletSecretV1

interface RecoveryPhraseCodec {
    fun words(entropy: ByteArray): List<String>
    fun entropy(words: List<String>): ByteArray
}

class InvalidRecoveryPhraseException : Exception()

data class CreatedWallet(val profile: WalletProfile, val recoveryWords: List<String>)

class WalletManager(
    private val vault: SecureVault,
    private val random: SecureRandomSource,
    private val phrases: RecoveryPhraseCodec,
    private val deriveWallet: suspend (ByteArray, CardanoNetwork) -> DerivedWallet,
    private val repository: WalletRepository,
) {
    suspend fun load(activeWalletId: WalletId? = null): List<WalletProfile> {
        val profiles = vault.profiles()
        val active = (activeWalletId ?: repository.selectedWalletId())?.takeIf { id -> profiles.any { it.id == id } }
        repository.publish(active ?: profiles.firstOrNull()?.id, profiles)
        return profiles
    }

    suspend fun create(name: String, network: CardanoNetwork): CreatedWallet {
        require(name.isNotBlank())
        val entropy = random.bytes(32)
        return try {
            val words = phrases.words(entropy)
            require(words.size == 24)
            val derived = deriveWallet(entropy, network)
            val profile = profile(name.trim(), network, derived, recoveryPhraseConfirmed = false)
            requireUnique(profile)
            vault.createWallet(profile, WalletSecretV1(entropy = entropy.copyOf()))
            load(profile.id)
            CreatedWallet(profile, words)
        } finally { entropy.fill(0) }
    }

    suspend fun restore(name: String, network: CardanoNetwork, phrase: String): WalletProfile {
        require(name.isNotBlank())
        val words = phrase.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        require(words.size == 24) { "a recovery phrase must contain exactly 24 words" }
        val entropy = phrases.entropy(words)
        require(entropy.size == 32)
        return try {
            val derived = deriveWallet(entropy, network)
            val profile = profile(name.trim(), network, derived)
            requireUnique(profile)
            vault.createWallet(profile, WalletSecretV1(entropy = entropy.copyOf()))
            load(profile.id)
            profile
        } finally { entropy.fill(0) }
    }
    suspend fun recoveryWords(walletId: WalletId): List<String> =
        vault.withWalletSeed(walletId) { entropy ->
            phrases.words(entropy).also { require(it.size == 24) }
        }

    suspend fun confirmRecoveryPhrase(walletId: WalletId) {
        val profile = vault.profiles().single { it.id == walletId }.copy(recoveryPhraseConfirmed = true)
        vault.updateProfile(profile)
        load(walletId)
    }


    suspend fun rename(walletId: WalletId, name: String) {
        vault.renameWallet(walletId, name)
        load(walletId)
    }

    private suspend fun requireUnique(profile: WalletProfile) {
        val paymentCredential = profile.id.value.substringAfter('-')
        require(vault.profiles().none { it.id.value.substringAfter('-') == paymentCredential }) { "wallet already restored" }
    }

    private fun profile(
        name: String,
        network: CardanoNetwork,
        derived: DerivedWallet,
        recoveryPhraseConfirmed: Boolean = true,
    ): WalletProfile {
        require(Regex("[0-9a-f]{56}").matches(derived.paymentCredentialHex))
        return WalletProfile(
            WalletId("${network.name.lowercase()}-${derived.paymentCredentialHex}"),
            name,
            network,
            derived.paymentAddress,
            derived.stakeAddress,
            recoveryPhraseConfirmed = recoveryPhraseConfirmed,
        )
    }
}
