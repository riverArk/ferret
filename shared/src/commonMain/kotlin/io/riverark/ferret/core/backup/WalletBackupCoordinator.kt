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
    val pending: Boolean = false,
    val previousHash: ByteArray = byteArrayOf(),
    val snapshotDigest: ByteArray = byteArrayOf(),
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
        return if (current.pending) {
            resumePending(walletId, current)
        } else {
            write(walletId, current.generation, current.sequence + 1, current.ciphertextHash, channelSnapshot)
        }
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
        if (local.pending) return resumePending(walletId, local)
        val remote = vault.withWalletSeed(walletId) { seed ->
            val latest = backups.verifyChain(backups.discover(walletId, seed))
            val plaintext = backups.decrypt(seed, latest)
            try {
                BackupCheckpointV1(
                    generation = latest.generation,
                    sequence = latest.sequence,
                    ciphertextHash = crypto.sha256(latest.ciphertext),
                    channelSnapshot = plaintext.copyOf(),
                )
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
            local.previousHash.fill(0)
            local.snapshotDigest.fill(0)
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

    suspend fun delete(walletId: WalletId) {
        vault.withWalletSeed(walletId) { seed -> backups.deleteAll(walletId, seed) }
        val current = vault.walletState(walletId)
        try {
            vault.updateWalletState(walletId, current.copy(
                channelRecovery = byteArrayOf(),
                backupGeneration = 0,
            ))
        } finally {
            current.channelRecovery.fill(0)
            current.operationJournal.fill(0)
        }
    }

    private suspend fun write(
        walletId: WalletId,
        generation: Long,
        sequence: Long,
        previousHash: ByteArray,
        channelSnapshot: ByteArray,
    ): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val candidate = BackupCheckpointV1(
            generation = generation,
            sequence = sequence,
            ciphertextHash = ByteArray(32),
            channelSnapshot = channelSnapshot.copyOf(),
            pending = true,
            previousHash = previousHash.copyOf(),
            snapshotDigest = crypto.sha256(channelSnapshot),
        )
        save(walletId, candidate)
        try {
            val backup = backups.write(walletId, seed, generation, sequence, previousHash, nowEpochMillis(), channelSnapshot)
            save(walletId, BackupCheckpointV1(
                generation = generation,
                sequence = sequence,
                ciphertextHash = crypto.sha256(backup.ciphertext),
                channelSnapshot = channelSnapshot,
            ))
        } finally {
            candidate.ciphertextHash.fill(0)
            candidate.channelSnapshot.fill(0)
            candidate.previousHash.fill(0)
            candidate.snapshotDigest.fill(0)
        }
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
                previousHash = checkpoint.previousHash.copyOf(),
                snapshotDigest = checkpoint.snapshotDigest.copyOf(),
            )
        } finally {
            current.channelRecovery.fill(0)
            current.operationJournal.fill(0)
            encoded.fill(0)
        }
    }

    private suspend fun resumePending(walletId: WalletId, candidate: BackupCheckpointV1): BackupCheckpointV1 =
        vault.withWalletSeed(walletId) { seed ->
            require(candidate.pending)
            require(candidate.snapshotDigest.contentEquals(crypto.sha256(candidate.channelSnapshot)))
            val discovered = backups.discover(walletId, seed)
            val matching = discovered.singleOrNull {
                it.generation == candidate.generation && it.sequence == candidate.sequence
            }
            if (matching != null) {
                require(matching.previousCiphertextHash.contentEquals(candidate.previousHash))
                val plaintext = backups.decrypt(seed, matching)
                try {
                    require(crypto.sha256(plaintext).contentEquals(candidate.snapshotDigest))
                    save(walletId, BackupCheckpointV1(
                        generation = matching.generation,
                        sequence = matching.sequence,
                        ciphertextHash = crypto.sha256(matching.ciphertext),
                        channelSnapshot = plaintext,
                    ))
                } finally {
                    plaintext.fill(0)
                }
            } else {
                val backup = backups.write(
                    walletId,
                    seed,
                    candidate.generation,
                    candidate.sequence,
                    candidate.previousHash,
                    nowEpochMillis(),
                    candidate.channelSnapshot,
                )
                save(walletId, BackupCheckpointV1(
                    generation = candidate.generation,
                    sequence = candidate.sequence,
                    ciphertextHash = crypto.sha256(backup.ciphertext),
                    channelSnapshot = candidate.channelSnapshot,
                ))
            }
        }

    private fun validate(checkpoint: BackupCheckpointV1) {
        require(checkpoint.schema == 1 && checkpoint.generation >= 1 && checkpoint.sequence >= 1)
        require(checkpoint.ciphertextHash.size == 32 && checkpoint.channelSnapshot.size in 1..1_048_576)
        if (checkpoint.pending) {
            require(checkpoint.previousHash.size == 32 && checkpoint.snapshotDigest.size == 32)
        } else {
            require(checkpoint.previousHash.isEmpty() && checkpoint.snapshotDigest.isEmpty())
        }
    }
}
