package io.riverark.ferret.core.model

import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.security.SecureRandomSource
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletSecretV1

interface RecoveryPhraseCodec {
    fun words(entropy: ByteArray): List<String>
    fun entropy(words: List<String>): ByteArray
}

data class CreatedWallet(val profile: WalletProfile, val recoveryWords: List<String>)

class WalletManager(
    private val vault: SecureVault,
    private val random: SecureRandomSource,
    private val phrases: RecoveryPhraseCodec,
    private val engine: CardanoTransactionEngine,
    private val repository: WalletRepository,
) {
    suspend fun load(activeWalletId: WalletId? = null): List<WalletProfile> {
        val profiles = vault.profiles()
        repository.publish(activeWalletId ?: profiles.firstOrNull()?.id, profiles)
        return profiles
    }

    suspend fun create(name: String, network: CardanoNetwork): CreatedWallet {
        require(name.isNotBlank())
        val entropy = random.bytes(32)
        return try {
            val words = phrases.words(entropy)
            require(words.size == 24)
            val derived = engine.deriveWallet(entropy, network)
            val profile = profile(name.trim(), network, derived.paymentAddress, derived.stakeAddress, derived.paymentCredentialHex)
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
            val derived = engine.deriveWallet(entropy, network)
            val profile = profile(name.trim(), network, derived.paymentAddress, derived.stakeAddress, derived.paymentCredentialHex)
            requireUnique(profile)
            vault.createWallet(profile, WalletSecretV1(entropy = entropy.copyOf()))
            load(profile.id)
            profile
        } finally { entropy.fill(0) }
    }

    suspend fun rename(walletId: WalletId, name: String) {
        vault.renameWallet(walletId, name)
        load(walletId)
    }

    private suspend fun requireUnique(profile: WalletProfile) {
        val paymentCredential = profile.id.value.substringAfter('-')
        require(vault.profiles().none { it.id.value.substringAfter('-') == paymentCredential }) { "wallet already restored" }
    }

    private fun profile(name: String, network: CardanoNetwork, paymentAddress: String, stakeAddress: String, paymentCredential: String): WalletProfile {
        require(Regex("[0-9a-f]{56}").matches(paymentCredential))
        return WalletProfile(
            WalletId("${network.name.lowercase()}-$paymentCredential"),
            name,
            network,
            paymentAddress,
            stakeAddress,
        )
    }
}
