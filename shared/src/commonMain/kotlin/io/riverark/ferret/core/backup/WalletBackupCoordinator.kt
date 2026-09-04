package io.riverark.ferret.core.backup

import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.SecureVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupCheckpointV1(
    val schema: Int = 1,
    val generation: Long,
    val sequence: Long,
    val ciphertextHash: ByteArray,
    val channelSnapshot: ByteArray,
)

class StaleBackupWriterException(
    val remoteGeneration: Long,
    val remoteSequence: Long,
) : IllegalStateException("a newer encrypted backup exists")

class WalletBackupCoordinator(
    private val vault: SecureVault,
    private val backups: DriveBackupRepository,
    private val crypto: BackupCrypto,
    private val nowEpochMillis: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun initializeOrVerify(walletId: WalletId, channelSnapshot: ByteArray): BackupCheckpointV1 =
        if (checkpoint(walletId) == null) initialize(walletId, channelSnapshot) else verify(walletId)

    suspend fun initialize(walletId: WalletId, channelSnapshot: ByteArray): BackupCheckpointV1 {
        require(checkpoint(walletId) == null) { "backup is already initialized" }
        return write(walletId, 1, 1, ByteArray(32), channelSnapshot)
    }

    suspend fun writeNext(walletId: WalletId, channelSnapshot: ByteArray): BackupCheckpointV1 {
        val current = requireNotNull(checkpoint(walletId)) { "verified backup is required" }
        return write(walletId, current.generation, current.sequence + 1, current.ciphertextHash, channelSnapshot)
    }

    suspend fun restore(walletId: WalletId): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val latest = backups.verifyChain(backups.discover(walletId, seed))
        val plaintext = backups.decrypt(seed, latest)
        try {
            save(walletId, BackupCheckpointV1(
                generation = latest.generation,
                sequence = latest.sequence,
                ciphertextHash = crypto.sha256(latest.ciphertext),
                channelSnapshot = plaintext,
            ))
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun takeover(walletId: WalletId): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val latest = backups.verifyChain(backups.discover(walletId, seed))
        val plaintext = backups.decrypt(seed, latest)
        try {
            write(walletId, latest.generation + 1, 1, ByteArray(32), plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun verify(walletId: WalletId): BackupCheckpointV1 {
        val local = requireNotNull(checkpoint(walletId))
        val remote = vault.withWalletSeed(walletId) { seed ->
            val latest = backups.verifyChain(backups.discover(walletId, seed))
            val plaintext = backups.decrypt(seed, latest)
            try {
                BackupCheckpointV1(1, latest.generation, latest.sequence, crypto.sha256(latest.ciphertext), plaintext.copyOf())
            } finally {
                plaintext.fill(0)
            }
        }
        var verified = false
        try {
            if (
                remote.generation > local.generation ||
                remote.generation == local.generation && remote.sequence > local.sequence
            ) {
                throw StaleBackupWriterException(remote.generation, remote.sequence)
            }
            require(remote.generation == local.generation && remote.sequence == local.sequence)
            require(remote.ciphertextHash.contentEquals(local.ciphertextHash) && remote.channelSnapshot.contentEquals(local.channelSnapshot))
            verified = true
            return remote
        } finally {
            local.ciphertextHash.fill(0)
            local.channelSnapshot.fill(0)
            if (!verified) {
                remote.ciphertextHash.fill(0)
                remote.channelSnapshot.fill(0)
            }
        }
    }

    suspend fun checkpoint(walletId: WalletId): BackupCheckpointV1? {
        val bytes = vault.walletState(walletId).channelRecovery
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString<BackupCheckpointV1>(bytes.decodeToString()).also(::validate)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun write(
        walletId: WalletId,
        generation: Long,
        sequence: Long,
        previousHash: ByteArray,
        channelSnapshot: ByteArray,
    ): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val backup = backups.write(walletId, seed, generation, sequence, previousHash, nowEpochMillis(), channelSnapshot)
        save(walletId, BackupCheckpointV1(
            generation = generation,
            sequence = sequence,
            ciphertextHash = crypto.sha256(backup.ciphertext),
            channelSnapshot = channelSnapshot,
        ))
    }

    private suspend fun save(walletId: WalletId, checkpoint: BackupCheckpointV1): BackupCheckpointV1 {
        validate(checkpoint)
        val current = vault.walletState(walletId)
        val encoded = json.encodeToString(checkpoint).encodeToByteArray()
        try {
            vault.updateWalletState(walletId, current.copy(
                channelRecovery = encoded,
                backupGeneration = checkpoint.generation,
            ))
            return checkpoint.copy(
                ciphertextHash = checkpoint.ciphertextHash.copyOf(),
                channelSnapshot = checkpoint.channelSnapshot.copyOf(),
            )
        } finally {
            current.channelRecovery.fill(0)
            current.operationJournal.fill(0)
            encoded.fill(0)
        }
    }

    private fun validate(checkpoint: BackupCheckpointV1) {
        require(checkpoint.schema == 1 && checkpoint.generation >= 1 && checkpoint.sequence >= 1)
        require(checkpoint.ciphertextHash.size == 32 && checkpoint.channelSnapshot.size in 1..1_048_576)
    }
}
