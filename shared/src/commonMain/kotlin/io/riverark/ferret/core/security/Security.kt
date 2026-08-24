package io.riverark.ferret.core.security

import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import kotlinx.serialization.Serializable

@Serializable
data class WalletSecretV1(
    val schema: Int = 1,
    val entropy: ByteArray,
    val channelRecovery: ByteArray = byteArrayOf(),
    val operationJournal: ByteArray = byteArrayOf(),
    val backupGeneration: Long = 0,
)

interface SecureVault {
    val isUnlocked: Boolean
    suspend fun unlock(wrappedDataKey: ByteArray)
    fun lock()
    suspend fun profiles(): List<WalletProfile>
    suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1)
    suspend fun updateProfile(profile: WalletProfile)
    suspend fun renameWallet(walletId: WalletId, name: String)
    suspend fun deleteWallet(walletId: WalletId)
    suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T
}

interface UserAuthenticator {
    suspend fun authenticate(reason: String): ByteArray
}

interface AppLifecycle {
    fun onForeground()
    fun onBackground(atEpochMillis: Long)
    fun shouldLock(nowEpochMillis: Long): Boolean
}

interface SecureRandomSource { fun bytes(size: Int): ByteArray }

class FiveMinuteAppLifecycle : AppLifecycle {
    private var backgroundedAt: Long? = null
    override fun onForeground() { backgroundedAt = null }
    override fun onBackground(atEpochMillis: Long) { backgroundedAt = atEpochMillis }
    override fun shouldLock(nowEpochMillis: Long) = backgroundedAt?.let { nowEpochMillis - it >= 300_000 } ?: false
}
