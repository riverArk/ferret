package io.riverark.ferret.core.backup

import io.riverark.ferret.core.model.WalletId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
    suspend fun discover(walletId: WalletId, seed: ByteArray): List<FerretChannelBackupV1> {
        val id = backupId(seed, walletId.value.substringBefore('-'))
        val prefix = "ferret-$id-"
        val objects = drive.list(prefix)
        require(objects.size <= 10_000) { "too many backup objects" }
        val backups = objects.map { objectInfo ->
            require(objectInfo.name.startsWith(prefix))
            val bytes = drive.get(objectInfo.name)
            require(bytes.size in 1..MAX_BACKUP_BYTES)
            try {
                json.decodeFromString<FerretChannelBackupV1>(bytes.decodeToString()).also {
                    require(it.backupId == id)
                    validate(it)
                }
            } finally {
                bytes.fill(0)
            }
        }
        if (backups.isNotEmpty()) verifyChain(backups)
        return backups
    }

    suspend fun deleteAll(walletId: WalletId, seed: ByteArray) {
        val prefix = "ferret-${backupId(seed, walletId.value.substringBefore('-'))}-"
        drive.list(prefix).forEach { drive.delete(it.name) }
        require(drive.list(prefix).isEmpty()) { "backup deletion could not be verified" }
    }

    suspend fun write(
        walletId: WalletId,
        seed: ByteArray,
        generation: Long,
        sequence: Long,
        previousHash: ByteArray,
        createdAtEpochMillis: Long,
        plaintext: ByteArray,
    ): FerretChannelBackupV1 {
        require(plaintext.size in 1..MAX_BACKUP_BYTES)
        require(sequence == 1L && previousHash.contentEquals(ZERO_HASH) || sequence > 1L && !previousHash.contentEquals(ZERO_HASH))
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
        require(bytes.size <= MAX_BACKUP_BYTES)
        drive.put(name, bytes)
        val storedBytes = drive.get(name)
        require(storedBytes.size <= MAX_BACKUP_BYTES && storedBytes.contentEquals(bytes)) { "backup read-back mismatch" }
        return try {
            val stored = json.decodeFromString<FerretChannelBackupV1>(storedBytes.decodeToString())
            validate(stored)
            val decrypted = decrypt(seed, stored)
            try {
                require(decrypted.contentEquals(plaintext)) { "backup decrypt verification failed" }
            } finally {
                decrypted.fill(0)
            }
            stored
        } finally {
            bytes.fill(0)
            storedBytes.fill(0)
        }
    }

    fun decrypt(seed: ByteArray, backup: FerretChannelBackupV1): ByteArray {
        validate(backup)
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
        require(backups.map(FerretChannelBackupV1::backupId).distinct().size == 1) { "mixed backup identities" }
        backups.forEach(::validate)
        val generations = backups.groupBy(FerretChannelBackupV1::generation).toList().sortedBy { it.first }
        generations.map { it.first }.zipWithNext().forEach { (before, after) ->
            require(after == before + 1) { "missing backup generation" }
        }
        generations.forEach { (_, generationBackups) ->
            val sorted = generationBackups.sortedBy(FerretChannelBackupV1::sequence)
            sorted.forEachIndexed { index, backup ->
                require(backup.sequence == index + 1L) { "missing or divergent backup sequence" }
                val expected = if (index == 0) ZERO_HASH else crypto.sha256(sorted[index - 1].ciphertext)
                require(backup.previousCiphertextHash.contentEquals(expected)) { "broken backup chain" }
            }
        }
        return generations.last().second.maxBy(FerretChannelBackupV1::sequence)
    }

    private fun validate(backup: FerretChannelBackupV1) {
        require(backup.schema == 1)
        require(backup.backupId.length in 20..64 && backup.backupId.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        require(backup.generation >= 1 && backup.sequence >= 1 && backup.createdAtEpochMillis >= 0)
        require(backup.previousCiphertextHash.size == 32 && backup.salt.size == 32 && backup.nonce.size == 12)
        require(backup.ciphertext.size in 17..MAX_BACKUP_BYTES)
    }

    private fun backupId(seed: ByteArray, network: String) = crypto.base64Url(
        crypto.hkdfSha256(seed, network.encodeToByteArray(), DISCOVERY_INFO, 32),
    )

    private fun header(id: String, generation: Long, sequence: Long, previous: ByteArray, created: Long, salt: ByteArray, nonce: ByteArray) =
        listOf("1", id, generation.toString(), sequence.toString(), crypto.base64Url(previous), created.toString(), crypto.base64Url(salt), crypto.base64Url(nonce)).joinToString("\n").encodeToByteArray()

    companion object {
        private val ZERO_HASH = ByteArray(32)
        private const val MAX_BACKUP_BYTES = 1_048_576
        private val DISCOVERY_INFO = "io.riverark.ferret/backup-discovery/v1".encodeToByteArray()
        private val CHANNEL_INFO = "io.riverark.ferret/channel-backup/v1".encodeToByteArray()
    }
}
