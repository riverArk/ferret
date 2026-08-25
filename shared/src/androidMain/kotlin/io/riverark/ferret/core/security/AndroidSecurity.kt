package io.riverark.ferret.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidSecureRandomSource : SecureRandomSource {
    private val random = SecureRandom()
    override fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
}

class AndroidSecureVault(private val context: Context) : SecureVault {
    private val json = Json { ignoreUnknownKeys = false }
    private var dataKey: ByteArray? = null
    override val isUnlocked get() = dataKey != null

    override suspend fun unlock(wrappedDataKey: ByteArray) {
        require(wrappedDataKey.size == 32)
        lock()
        dataKey = wrappedDataKey.copyOf()
    }

    override fun lock() { dataKey?.fill(0); dataKey = null }

    override suspend fun profiles(): List<WalletProfile> {
        val file = indexFile()
        if (!file.baseFile.exists()) return emptyList()
        val plaintext = decrypt(key(), file.readFully())
        return try { json.decodeFromString<List<WalletProfile>>(plaintext.decodeToString()) } finally { plaintext.fill(0) }
    }

    override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) {
        require(profiles().none { it.id == profile.id })
        val plaintext = json.encodeToString(secret).encodeToByteArray()
        try { writeAtomic(file(profile.id), encrypt(key(), plaintext)) } finally { plaintext.fill(0) }
        writeProfiles(profiles() + profile)
    }
    override suspend fun updateProfile(profile: WalletProfile) {
        val profiles = profiles()
        require(profiles.any { it.id == profile.id })
        writeProfiles(profiles.map { if (it.id == profile.id) profile else it })
    }


    override suspend fun renameWallet(walletId: WalletId, name: String) {
        require(name.isNotBlank())
        writeProfiles(profiles().map { if (it.id == walletId) it.copy(name = name.trim()) else it })
    }

    override suspend fun deleteWallet(walletId: WalletId) {
        writeProfiles(profiles().filterNot { it.id == walletId })
        check(file(walletId).baseFile.delete() || !file(walletId).baseFile.exists())
        check(!file(walletId).baseFile.exists() && profiles().none { it.id == walletId })
    }

    override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
        val plaintext = decrypt(key(), file(walletId).readFully())
        val secret = try { json.decodeFromString<WalletSecretV1>(plaintext.decodeToString()) } finally { plaintext.fill(0) }
        val entropy = secret.entropy.copyOf()
        secret.entropy.fill(0)
        return try { action(entropy) } finally { entropy.fill(0) }
    }

    override suspend fun walletState(walletId: WalletId): WalletEncryptedStateV1 {
        val secret = readSecret(walletId)
        return try {
            WalletEncryptedStateV1(
                channelRecovery = secret.channelRecovery.copyOf(),
                operationJournal = secret.operationJournal.copyOf(),
                backupGeneration = secret.backupGeneration,
            )
        } finally {
            secret.clear()
        }
    }

    override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
        require(state.backupGeneration >= 0)
        val secret = readSecret(walletId)
        val updated = secret.copy(
            channelRecovery = state.channelRecovery,
            operationJournal = state.operationJournal,
            backupGeneration = state.backupGeneration,
        )
        val plaintext = json.encodeToString(updated).encodeToByteArray()
        try {
            writeAtomic(file(walletId), encrypt(key(), plaintext))
        } finally {
            plaintext.fill(0)
            secret.clear()
        }
    }

    private fun readSecret(walletId: WalletId): WalletSecretV1 {
        val plaintext = decrypt(key(), file(walletId).readFully())
        return try {
            json.decodeFromString<WalletSecretV1>(plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    private fun WalletSecretV1.clear() {
        entropy.fill(0)
        channelRecovery.fill(0)
        operationJournal.fill(0)
    }

    private fun key() = dataKey ?: error("vault locked")
    private fun file(walletId: WalletId) = AtomicFile(context.filesDir.resolve("wallet-${walletId.value}.v1"))
    private fun indexFile() = AtomicFile(context.filesDir.resolve("wallet-index.v1"))
    private fun writeProfiles(profiles: List<WalletProfile>) {
        val plaintext = json.encodeToString(profiles).encodeToByteArray()
        try { writeAtomic(indexFile(), encrypt(key(), plaintext)) } finally { plaintext.fill(0) }
    }
    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
    private fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        return cipher.iv + cipher.doFinal(plaintext)
    }
    private fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

class AndroidUserAuthenticator(private val activity: FragmentActivity) : UserAuthenticator {
    override suspend fun authenticate(reason: String): ByteArray {
        val keyFile = AtomicFile(activity.filesDir.resolve(VAULT_KEY_FILE))
        val wrappedVaultKey = if (keyFile.baseFile.exists()) keyFile.readFully() else null
        if (wrappedVaultKey != null) {
            require(wrappedVaultKey.size > 12)
            val cipher = unwrapCipher(wrappedVaultKey.copyOfRange(0, 12))
            return authenticate(cipher, reason) { it.doFinal(wrappedVaultKey, 12, wrappedVaultKey.size - 12) }
        }

        val vaultKey = SecureRandom().generateSeed(32)
        return try {
            val cipher = wrapCipher()
            authenticate(cipher, reason) {
                val output = keyFile.startWrite()
                try {
                    output.write(it.iv + it.doFinal(vaultKey))
                    output.fd.sync()
                    keyFile.finishWrite(output)
                    vaultKey.copyOf()
                } catch (error: Throwable) {
                    keyFile.failWrite(output)
                    throw error
                }
            }
        } finally {
            vaultKey.fill(0)
        }
    }

    private suspend fun authenticate(cipher: Cipher, reason: String, result: (Cipher) -> ByteArray): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                    try { continuation.resume(result(authentication.cryptoObject!!.cipher!!)) }
                    catch (error: Throwable) { continuation.resumeWithException(error) }
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    continuation.resumeWithException(SecurityException("authentication failed: $code"))
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Ferret")
                    .setSubtitle(reason)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build(),
                BiometricPrompt.CryptoObject(cipher),
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    private fun wrapCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, keystoreKey())
    }

    private fun unwrapCipher(iv: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        fun generate(strongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setIsStrongBoxBacked(strongBox)
                .build()
            generator.init(spec)
            return generator.generateKey()
        }
        return try { generate(true) } catch (_: Exception) { generate(false) }
    }

    companion object {
        private const val KEY_ALIAS = "ferret-vault-kek-v1"
        private const val VAULT_KEY_FILE = "vault-key.v1"
    }
}
