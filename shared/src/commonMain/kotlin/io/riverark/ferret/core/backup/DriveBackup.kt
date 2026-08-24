package io.riverark.ferret.core.backup

import io.riverark.ferret.core.model.WalletId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FerretChannelBackupV1(
    val schema: Int = 1,
    val backupId: String,
    val generation: Long,
    val sequence: Long,
    val previousCiphertextHash: ByteArray,
    val createdAtEpochMillis: Long,
    val salt: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

sealed interface CloudBackupState {
    data object Disconnected : CloudBackupState
    data object Verifying : CloudBackupState
    data class Verified(val generation: Long, val sequence: Long, val ciphertextHash: ByteArray) : CloudBackupState
    data object Required : CloudBackupState
    data object Conflict : CloudBackupState
}

interface OAuthTokenProvider { suspend fun accessToken(): String }

interface DriveAppDataClient {
    suspend fun list(prefix: String): List<DriveObject>
    suspend fun put(name: String, bytes: ByteArray)
    suspend fun get(name: String): ByteArray
    suspend fun delete(name: String)
}

data class DriveObject(val name: String, val modifiedAtEpochMillis: Long)

interface BackupCrypto {
    fun random(size: Int): ByteArray
    fun sha256(input: ByteArray): ByteArray
    fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray
    fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
    fun base64Url(input: ByteArray): String
}

class DriveBackupRepository(
    private val drive: DriveAppDataClient,
    private val crypto: BackupCrypto,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    suspend fun write(
        walletId: WalletId,
        seed: ByteArray,
        generation: Long,
        sequence: Long,
        previousHash: ByteArray,
        createdAtEpochMillis: Long,
        plaintext: ByteArray,
    ): FerretChannelBackupV1 {
        require(generation >= 1 && sequence >= 1)
        require(previousHash.size == 32)
        val backupId = backupId(seed, walletId.value.substringBefore('-'))
        val salt = crypto.random(32)
        val nonce = crypto.random(12)
        val key = crypto.hkdfSha256(seed, salt, CHANNEL_INFO, 32)
        val header = header(backupId, generation, sequence, previousHash, createdAtEpochMillis, salt, nonce)
        val backup = FerretChannelBackupV1(
            backupId = backupId,
            generation = generation,
            sequence = sequence,
            previousCiphertextHash = previousHash,
            createdAtEpochMillis = createdAtEpochMillis,
            salt = salt,
            nonce = nonce,
            ciphertext = crypto.encryptAesGcm(key, nonce, plaintext, header),
        )
        key.fill(0)
        val name = "ferret-$backupId-g$generation-s$sequence.bin"
        val bytes = json.encodeToString(backup).encodeToByteArray()
        drive.put(name, bytes)
        require(drive.get(name).contentEquals(bytes)) { "backup read-back mismatch" }
        return backup
    }

    fun decrypt(seed: ByteArray, backup: FerretChannelBackupV1): ByteArray {
        require(backup.schema == 1 && backup.previousCiphertextHash.size == 32 && backup.salt.size == 32 && backup.nonce.size == 12)
        val key = crypto.hkdfSha256(seed, backup.salt, CHANNEL_INFO, 32)
        return try {
            crypto.decryptAesGcm(key, backup.nonce, backup.ciphertext, header(
                backup.backupId, backup.generation, backup.sequence, backup.previousCiphertextHash,
                backup.createdAtEpochMillis, backup.salt, backup.nonce,
            ))
        } finally { key.fill(0) }
    }

    fun verifyChain(backups: List<FerretChannelBackupV1>): FerretChannelBackupV1 {
        require(backups.isNotEmpty())
        val sorted = backups.sortedWith(compareBy(FerretChannelBackupV1::generation, FerretChannelBackupV1::sequence))
        sorted.forEachIndexed { index, backup ->
            val expected = if (index == 0 || sorted[index - 1].generation != backup.generation) ZERO_HASH else crypto.sha256(sorted[index - 1].ciphertext)
            require(backup.previousCiphertextHash.contentEquals(expected)) { "broken backup chain" }
        }
        return sorted.last()
    }

    private fun backupId(seed: ByteArray, network: String) = crypto.base64Url(
        crypto.hkdfSha256(seed, network.encodeToByteArray(), DISCOVERY_INFO, 32),
    )

    private fun header(id: String, generation: Long, sequence: Long, previous: ByteArray, created: Long, salt: ByteArray, nonce: ByteArray) =
        listOf("1", id, generation.toString(), sequence.toString(), crypto.base64Url(previous), created.toString(), crypto.base64Url(salt), crypto.base64Url(nonce)).joinToString("\n").encodeToByteArray()

    companion object {
        private val ZERO_HASH = ByteArray(32)
        private val DISCOVERY_INFO = "io.riverark.ferret/backup-discovery/v1".encodeToByteArray()
        private val CHANNEL_INFO = "io.riverark.ferret/channel-backup/v1".encodeToByteArray()
    }
}
