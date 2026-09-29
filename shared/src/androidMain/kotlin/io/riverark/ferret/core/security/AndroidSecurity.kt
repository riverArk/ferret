package io.riverark.ferret.core.security

import android.content.Context
import android.app.KeyguardManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.VaultChannelJournal
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.BackupStatus
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidSecureRandomSource : SecureRandomSource {
    private val random = SecureRandom()
    override fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
}

@Serializable
private data class LegacyOperationEnvelope(
    val schema: Int = 1,
    val l1: ByteArray = byteArrayOf(),
    val channel: ByteArray = byteArrayOf(),
    val payment: ByteArray = byteArrayOf(),
)

@Serializable
private data class LegacyWalletProfile(
    val id: WalletId,
    val name: String,
    val network: CardanoNetwork,
    val paymentAddress: String,
    val stakeAddress: String,
    val channelState: ChannelState = ChannelState.Absent,
    val backupStatus: BackupStatus = BackupStatus.DISCONNECTED,
    val recoveryPhraseConfirmed: Boolean = true,
) {
    fun current() = WalletProfile(
        id, name, network, paymentAddress, stakeAddress, backupStatus, recoveryPhraseConfirmed,
    )
}

class AndroidSecureVault(
    private val context: Context,
    private val catalog: AssetCatalog,
) : SecureVault {
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
        val objects = try { json.parseToJsonElement(plaintext.decodeToString()).jsonArray } finally { plaintext.fill(0) }
        if (objects.none { "channelState" in it.jsonObject }) {
            return objects.map { json.decodeFromJsonElement(WalletProfile.serializer(), it) }
        }
        val legacy = objects.map { json.decodeFromJsonElement(LegacyWalletProfile.serializer(), it) }
        val journal = VaultChannelJournal(this, catalog)
        legacy.forEach { profile ->
            val state = walletState(profile.id)
            val useProfileState = try {
                if (state.operationJournal.isEmpty()) {
                    true
                } else {
                    val root = json.parseToJsonElement(state.operationJournal.decodeToString()).jsonObject
                    if (root["schema"]?.jsonPrimitive?.content !in setOf(null, "1") || "channels" in root) {
                        false
                    } else {
                        val envelope = json.decodeFromString<LegacyOperationEnvelope>(state.operationJournal.decodeToString())
                        try {
                            envelope.channel.isEmpty() && (
                                envelope.payment.isEmpty() ||
                                    json.parseToJsonElement(envelope.payment.decodeToString()).jsonObject.let {
                                        it["pending"] == null && it["receipts"]?.jsonArray?.isEmpty() != false &&
                                            it["paidHashes"]?.jsonArray?.isEmpty() != false
                                    }
                                )
                        } finally {
                            envelope.l1.fill(0)
                            envelope.channel.fill(0)
                            envelope.payment.fill(0)
                        }
                    }
                }
            } finally {
                state.channelRecovery.fill(0)
                state.operationJournal.fill(0)
            }
            val collection = if (useProfileState) {
                if (profile.channelState == ChannelState.Absent) {
                    ChannelCollectionV4(walletId = profile.id, catalogDigest = catalog.digest)
                } else {
                    val evidence = json.encodeToString(LegacyWalletProfile.serializer(), profile).encodeToByteArray()
                    try {
                        ChannelCollectionV4(
                            walletId = profile.id,
                            catalogDigest = catalog.digest,
                            unresolvedLegacy = evidence.copyOf(),
                        )
                    } finally {
                        evidence.fill(0)
                    }
                }
            } else {
                journal.load(profile.id)
            }.also {
                if (useProfileState) journal.persist(profile.id, it)
            }
        }
        val migrated = legacy.map(LegacyWalletProfile::current)
        writeProfiles(migrated)
        return migrated
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
                removalState = secret.removalState,
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
            removalState = state.removalState,
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

class VaultKeyRecoveryException : SecurityException("Vault key recovery required; wallet data was preserved.")
class AuthenticationPrerequisiteException(message: String) : SecurityException(message)

class AndroidUserAuthenticator(private val activity: FragmentActivity) : UserAuthenticator {
    private val biometrics = BiometricManager.from(activity)
    private val credential = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        credentialResult?.let { continuation ->
            credentialResult = null
            if (continuation.isActive) {
                if (result.resultCode == android.app.Activity.RESULT_OK) continuation.resume(Unit)
                else continuation.cancel(CancellationException("Screen lock cancelled."))
            }
        }
    }
    private var credentialResult: CancellableContinuation<Unit>? = null
    private var pendingPrompt: BiometricPrompt? = null
    val isConfirmingScreenLock get() = credentialResult?.isActive == true

    fun cancel() {
        pendingPrompt?.cancelAuthentication()
        pendingPrompt = null
        credentialResult?.cancel(CancellationException("Authentication cancelled."))
        credentialResult = null
    }

    override suspend fun authenticate(reason: String): ByteArray {
        val keyFile = AtomicFile(activity.filesDir.resolve(VAULT_KEY_FILE))
        val existing = keyFile.baseFile.exists()
        if (!existing && activity.filesDir.listFiles()?.any {
                it.name.startsWith("wallet-") || it.name == DEBUG_VAULT_KEY_FILE ||
                    it.name.startsWith("vault-key.")
            } == true) {
            throw VaultKeyRecoveryException()
        }
        if (existing && !keyStore().containsAlias(KEY_ALIAS)) {
            throw VaultKeyRecoveryException()
        }
        val wrapped = if (existing) try { keyFile.readFully() } catch (_: IOException) {
            throw VaultKeyRecoveryException()
        } else null
        if (wrapped != null && wrapped.size < 29) {
            throw VaultKeyRecoveryException()
        }
        if (Build.VERSION.SDK_INT >= 30 && biometrics.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) != BiometricManager.BIOMETRIC_SUCCESS) {
            throw AuthenticationPrerequisiteException("Set up biometrics or a device screen lock to unlock Ferret.")
        }
        val vaultKey = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cipher = if (wrapped == null) wrapCipher() else unwrapCipher(wrapped.copyOfRange(0, 12))
                prompt(cipher, reason) { authorized ->
                    if (wrapped == null) newVaultKey(keyFile, authorized) else authorized.doFinal(wrapped, 12, wrapped.size - 12)
                }
            } else {
                legacyAuthenticate(reason)
                val cipher = if (wrapped == null) wrapCipher() else unwrapCipher(wrapped.copyOfRange(0, 12))
                if (wrapped == null) newVaultKey(keyFile, cipher) else cipher.doFinal(wrapped, 12, wrapped.size - 12)
            }
        } catch (error: GeneralSecurityException) {
            if (existing) throw VaultKeyRecoveryException()
            throw error
        }
        try {
            if (vaultKey.size != 32) throw VaultKeyRecoveryException()
            revokeDebugKey()
            return vaultKey
        } catch (error: Throwable) {
            vaultKey.fill(0)
            throw error
        }
    }

    private fun newVaultKey(file: AtomicFile, cipher: Cipher): ByteArray {
        val key = SecureRandom().generateSeed(32)
        try {
            val output = file.startWrite()
            try {
                output.write(cipher.iv + cipher.doFinal(key))
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
            return key.copyOf()
        } finally {
            key.fill(0)
        }
    }

    private fun revokeDebugKey() {
        val debug = AtomicFile(activity.filesDir.resolve(DEBUG_VAULT_KEY_FILE))
        debug.delete()
        check(!debug.baseFile.exists()) { "Legacy key revocation failed." }
        val store = keyStore()
        if (store.containsAlias(DEBUG_KEY_ALIAS)) store.deleteEntry(DEBUG_KEY_ALIAS)
        check(!store.containsAlias(DEBUG_KEY_ALIAS)) { "Legacy key revocation failed." }
    }

    private suspend fun prompt(cipher: Cipher, reason: String, result: (Cipher) -> ByteArray): ByteArray {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    try {
                        val key = result(checkNotNull(authentication.cryptoObject?.cipher))
                        if (continuation.isActive) continuation.resume(key) else key.fill(0)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    pendingPrompt = null
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (continuation.isActive) continuation.resumeWithException(SecurityException("Authentication failed: $code"))
                    pendingPrompt = null
                }
            })
            pendingPrompt = prompt
            continuation.invokeOnCancellation {
                prompt.cancelAuthentication()
                if (pendingPrompt === prompt) pendingPrompt = null
            }
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Ferret")
                    .setSubtitle(reason)
                    .setAllowedAuthenticators(allowed)
                    .build(),
                BiometricPrompt.CryptoObject(cipher),
            )
        }
    }

    private suspend fun legacyAuthenticate(reason: String) {
        val keyguard = checkNotNull(activity.getSystemService<KeyguardManager>())
        val biometricAvailable = biometrics.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!biometricAvailable) {
            if (!keyguard.isDeviceSecure) throw AuthenticationPrerequisiteException("Set up a device screen lock to unlock Ferret.")
            confirmCredential(keyguard)
            return
        }
        val useCredential = suspendCancellableCoroutine<Boolean> { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(false)
                    pendingPrompt = null
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (continuation.isActive) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_LOCKOUT ||
                            code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            continuation.resume(true)
                        } else continuation.resumeWithException(SecurityException("Authentication failed: $code"))
                    }
                    pendingPrompt = null
                }
            })
            pendingPrompt = prompt
            continuation.invokeOnCancellation {
                prompt.cancelAuthentication()
                if (pendingPrompt === prompt) pendingPrompt = null
            }
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Ferret")
                    .setSubtitle(reason)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Use screen lock")
                    .build(),
            )
        }
        if (useCredential) {
            if (!keyguard.isDeviceSecure) throw AuthenticationPrerequisiteException("Set up a device screen lock to unlock Ferret.")
            confirmCredential(keyguard)
        }
    }

    private suspend fun confirmCredential(keyguard: KeyguardManager) {
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Ferret", "Authenticate to access your wallets")
            ?: throw AuthenticationPrerequisiteException("Set up a device screen lock to unlock Ferret.")
        suspendCancellableCoroutine<Unit> { continuation ->
            credentialResult = continuation
            continuation.invokeOnCancellation { if (credentialResult === continuation) credentialResult = null }
            credential.launch(intent)
        }
    }

    private fun wrapCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, keystoreKey())
    }

    private fun unwrapCipher(iv: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun keystoreKey(): SecretKey {
        val store = keyStore()
        return (store.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: if (store.containsAlias(KEY_ALIAS)) throw VaultKeyRecoveryException()
            else generateKey()
    }

    private fun generateKey(): SecretKey {
        fun generate(strongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(30)
            }
            generator.init(builder.setIsStrongBoxBacked(strongBox).build())
            return generator.generateKey()
        }
        return try { generate(true) } catch (_: Exception) { generate(false) }
    }

    companion object {
        private const val KEY_ALIAS = "ferret-vault-kek-v1"
        private const val VAULT_KEY_FILE = "vault-key.v1"
        private const val DEBUG_KEY_ALIAS = "ferret-vault-debug-kek-v1"
        private const val DEBUG_VAULT_KEY_FILE = "vault-key.debug.v1"
    }
}
