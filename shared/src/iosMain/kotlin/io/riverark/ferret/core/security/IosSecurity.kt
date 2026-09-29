@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.riverark.ferret.core.security

import io.riverark.ferret.core.backup.BackupCrypto
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.LocalAuthentication.*
import platform.Security.*
import platform.posix.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val VAULT_SERVICE = "io.riverark.ferret.vault.v1"
private const val VAULT_ACCOUNT = "vault-key.v1"

internal fun applicationSupport(): String {
    val manager = NSFileManager.defaultManager
    val root = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path!! + "/Ferret"
    if (!manager.createDirectoryAtPath(root, true, null, null)) error("Cannot create protected wallet storage.")
    check(manager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), root, null)) {
        "Cannot protect wallet storage."
    }
    val url = NSURL.fileURLWithPath(root)
    if (!url.setResourceValue(true, NSURLIsExcludedFromBackupKey, null)) error("Cannot exclude wallet storage from backup.")
    return root
}

internal fun protectedAtomicWrite(path: String, bytes: ByteArray) {
    val temporary = "$path.${NSUUID().UUIDString}.tmp"
    val descriptor = open(temporary, O_WRONLY or O_CREAT or O_EXCL, 0x180) // 0600
    check(descriptor >= 0) { "Cannot create protected wallet file." }
    var isOpen = true
    try {
        check(NSFileManager.defaultManager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), temporary, null)) {
            "Cannot protect wallet file."
        }
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                check(count > 0) { "Cannot write wallet file." }
                offset += count
            }
        }
        check(fsync(descriptor) == 0) { "Cannot flush wallet file." }
        check(close(descriptor) == 0) { "Cannot close wallet file." }
        isOpen = false
        // Rename is atomic on the same filesystem. The directory sync commits its name.
        check(rename(temporary, path) == 0) { "Cannot commit wallet file." }
        val directory = open(path.substringBeforeLast('/'), O_RDONLY)
        check(directory >= 0) { "Cannot open wallet directory." }
        try { check(fsync(directory) == 0) { "Cannot flush wallet directory." } } finally { close(directory) }
    } catch (failure: Throwable) {
        if (isOpen) close(descriptor)
        unlink(temporary)
        throw failure
    }
}

private fun readProtected(path: String): ByteArray {
    val data = NSFileManager.defaultManager.contentsAtPath(path) ?: error("Wallet record is missing or unreadable.")
    check(data.length <= Int.MAX_VALUE.toULong()) { "Wallet record is too large." }
    return ByteArray(data.length.toInt()).also { output ->
        output.usePinned { pinned -> if (output.isNotEmpty()) platform.posix.memcpy(pinned.addressOf(0), data.bytes, output.size.convert()) }
    }
}

class IosSecureVault(
    private val crypto: BackupCrypto,
    private val random: SecureRandomSource,
) : SecureVault {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false }
    private val directory = applicationSupport()
    private var dataKey: ByteArray? = null
    override val isUnlocked: Boolean get() = dataKey != null

    override suspend fun unlock(wrappedDataKey: ByteArray) = mutex.withLock {
        require(wrappedDataKey.size == 32)
        lock()
        dataKey = wrappedDataKey.copyOf()
    }

    override fun lock() { dataKey?.fill(0); dataKey = null }
    private fun key(): ByteArray = dataKey ?: error("Vault locked.")
    private fun record(id: WalletId) = "$directory/wallet-${id.value}.v1"
    private val index get() = "$directory/wallet-index.v1"
    private fun exists(path: String) = NSFileManager.defaultManager.fileExistsAtPath(path)

    private fun profilesLocked(): List<WalletProfile> {
        key()
        if (!exists(index)) {
            check(!hasWalletRecords(directory)) { "Wallet index is missing; wallet records were preserved." }
            return emptyList()
        }
        val plaintext = decrypt(readProtected(index))
        return try {
            val profiles = json.decodeFromString<List<WalletProfile>>(plaintext.decodeToString())
            require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate wallet records." }
            profiles.forEach { check(exists(record(it.id))) { "Wallet seed record is missing." } }
            profiles
        } finally { plaintext.fill(0) }
    }

    override suspend fun profiles(): List<WalletProfile> = mutex.withLock { profilesLocked() }

    override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = mutex.withLock {
        require(secret.schema == 1 && secret.entropy.size == 32 && secret.backupGeneration >= 0)
        val current = profilesLocked()
        require(current.none { it.id == profile.id })
        check(!exists(record(profile.id))) { "Unindexed wallet record was preserved." }
        writeSecret(record(profile.id), secret)
        writeProfiles(current + profile)
    }

    override suspend fun updateProfile(profile: WalletProfile) = mutex.withLock {
        val current = profilesLocked()
        require(current.any { it.id == profile.id })
        writeProfiles(current.map { if (it.id == profile.id) profile else it })
    }

    override suspend fun renameWallet(walletId: WalletId, name: String) = mutex.withLock {
        require(name.isNotBlank())
        val current = profilesLocked()
        require(current.any { it.id == walletId })
        writeProfiles(current.map { if (it.id == walletId) it.copy(name = name.trim()) else it })
    }

    override suspend fun deleteWallet(walletId: WalletId) = mutex.withLock {
        val current = profilesLocked()
        require(current.any { it.id == walletId })
        writeProfiles(current.filterNot { it.id == walletId })
        check(unlink(record(walletId)) == 0) { "Wallet record deletion failed." }
        val directoryFd = open(directory, O_RDONLY)
        check(directoryFd >= 0)
        try { check(fsync(directoryFd) == 0) } finally { close(directoryFd) }
    }

    override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
        val seed = mutex.withLock {
            val secret = readSecret(walletId)
            try { secret.entropy.copyOf() } finally { secret.clear() }
        }
        return try { action(seed) } finally { seed.fill(0) }
    }

    override suspend fun walletState(walletId: WalletId): WalletEncryptedStateV1 = mutex.withLock {
        val secret = readSecret(walletId)
        try {
            WalletEncryptedStateV1(secret.channelRecovery.copyOf(), secret.operationJournal.copyOf(),
                secret.backupGeneration, secret.removalState)
        } finally { secret.clear() }
    }

    override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) = mutex.withLock {
        require(state.backupGeneration >= 0)
        val secret = readSecret(walletId)
        try {
            writeSecret(record(walletId), secret.copy(channelRecovery = state.channelRecovery,
                operationJournal = state.operationJournal, backupGeneration = state.backupGeneration,
                removalState = state.removalState))
        } finally { secret.clear() }
    }

    private fun readSecret(walletId: WalletId): WalletSecretV1 {
        require(profilesLocked().any { it.id == walletId })
        val plaintext = decrypt(readProtected(record(walletId)))
        return try {
            val secret = json.decodeFromString<WalletSecretV1>(plaintext.decodeToString())
            try {
                require(secret.schema == 1 && secret.entropy.size == 32 && secret.backupGeneration >= 0)
                secret
            } catch (failure: Throwable) {
                secret.clear()
                throw failure
            }
        } finally { plaintext.fill(0) }
    }

    private fun WalletSecretV1.clear() {
        entropy.fill(0); channelRecovery.fill(0); operationJournal.fill(0)
    }

    private fun writeProfiles(profiles: List<WalletProfile>) {
        val plaintext = json.encodeToString(profiles).encodeToByteArray()
        try { writeEncrypted(index, plaintext) } finally { plaintext.fill(0) }
    }
    private fun writeSecret(path: String, secret: WalletSecretV1) {
        val plaintext = json.encodeToString(secret).encodeToByteArray()
        try { writeEncrypted(path, plaintext) } finally { plaintext.fill(0) }
    }
    private fun writeEncrypted(path: String, plaintext: ByteArray) {
        val nonce = random.bytes(12)
        if (nonce.size != 12) { nonce.fill(0); error("Invalid vault nonce.") }
        try {
            val encrypted = crypto.encryptAesGcm(key(), nonce, plaintext, byteArrayOf())
            try {
                require(encrypted.size >= 16)
                val blob = nonce + encrypted
                try { protectedAtomicWrite(path, blob) } finally { blob.fill(0) }
            } finally { encrypted.fill(0) }
        } finally { nonce.fill(0) }
    }
    private fun decrypt(blob: ByteArray): ByteArray {
        try {
            require(blob.size >= 28) { "Corrupt wallet record." }
            val nonce = blob.copyOfRange(0, 12)
            val encrypted = blob.copyOfRange(12, blob.size)
            return try { crypto.decryptAesGcm(key(), nonce, encrypted, byteArrayOf()) }
            finally { nonce.fill(0); encrypted.fill(0) }
        } finally { blob.fill(0) }
    }
}

internal fun hasWalletRecords(directory: String): Boolean =
    (NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) ?: emptyList<Any>())
        .any { (it as String).startsWith("wallet-") }

class IosUserAuthenticator : UserAuthenticator {
    private var pending: LAContext? = null
    fun cancel() { pending?.invalidate(); pending = null }

    override suspend fun authenticate(reason: String): ByteArray {
        cancel()
        val context = LAContext().apply { touchIDAuthenticationAllowableReuseDuration = 0.0 }
        pending = context
        try {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr))
                    throw IllegalStateException("Set up biometrics or a device screen lock to unlock Ferret.")
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { context.invalidate() }
                context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, "Unlock your Ferret wallets.") { success, _ ->
                    if (continuation.isActive) {
                        if (success) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException("Wallet authentication failed or was cancelled."))
                    }
                }
            }
            if (pending !== context) throw CancellationException("Authentication cancelled.")
            val bytes = keychainKey(context)
            if (pending !== context) {
                bytes.fill(0)
                throw CancellationException("Authentication cancelled.")
            }
            return bytes
        } finally {
            context.invalidate()
            if (pending === context) pending = null
        }
    }

    private fun keychainKey(context: LAContext): ByteArray = memScoped {
        val service = CFBridgingRetain(VAULT_SERVICE)
        val account = CFBridgingRetain(VAULT_ACCOUNT)
        val authentication = CFBridgingRetain(context)
        try {
            val query = dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
                kSecUseAuthenticationContext to authentication,
            )
            val result = alloc<CFTypeRefVar>()
            val status = try { SecItemCopyMatching(query, result.ptr) } finally { CFRelease(query) }
            if (status == errSecSuccess) {
                val data = CFBridgingRelease(result.value) as? NSData ?: throw IllegalStateException("Vault key is corrupt.")
                if (data.length != 32uL) throw IllegalStateException("Vault key recovery required; wallet data was preserved.")
                return@memScoped ByteArray(32).also { key ->
                    key.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, 32.convert()) }
                }
            }
            if (status != errSecItemNotFound) throw IllegalStateException("Protected vault key is unavailable.")
            if (hasWalletRecords(applicationSupport()))
                throw IllegalStateException("Vault key recovery required; wallet data was preserved.")
            val key = ByteArray(32)
            try {
                key.usePinned { pinned -> check(SecRandomCopyBytes(kSecRandomDefault, 32.convert(), pinned.addressOf(0)) == errSecSuccess) }
                val control = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                    kSecAccessControlUserPresence, null) ?: error("Cannot protect vault key.")
                try {
                    val data = key.usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), 32.convert()) }
                    val value = CFBridgingRetain(data)
                    try {
                        val add = dictionary(
                            kSecClass to kSecClassGenericPassword,
                            kSecAttrService to service,
                            kSecAttrAccount to account,
                            kSecAttrAccessControl to control,
                            kSecUseAuthenticationContext to authentication,
                            kSecValueData to value,
                        )
                        try { check(SecItemAdd(add, null) == errSecSuccess) { "Cannot store protected vault key." } }
                        finally { CFRelease(add) }
                    } finally { CFBridgingRelease(value) }
                } finally { CFRelease(control) }
                key.copyOf()
            } finally { key.fill(0) }
        } finally {
            CFBridgingRelease(service)
            CFBridgingRelease(account)
            CFBridgingRelease(authentication)
        }
    }
}

private fun MemScope.dictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val keys = allocArrayOf(*entries.map { it.first }.toTypedArray())
    val values = allocArrayOf(*entries.map { it.second }.toTypedArray())
    return CFDictionaryCreate(kCFAllocatorDefault, keys.reinterpret(), values.reinterpret(),
        entries.size.convert(), null, null)
}
