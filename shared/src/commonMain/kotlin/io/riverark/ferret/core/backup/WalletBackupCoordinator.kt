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
    val installOnCommit: Boolean = false,
)

class StaleBackupWriterException(
    val remoteGeneration: Long,
    val remoteSequence: Long,
) : IllegalStateException("a newer encrypted backup exists")

class MissingBackupException(val replacementAllowed: Boolean) : IllegalStateException("encrypted backup is missing")

class WalletBackupCoordinator(
    private val vault: SecureVault,
    private val backups: DriveBackupRepository,
    private val crypto: BackupCrypto,
    private val nowEpochMillis: () -> Long,
    private val normalizeSnapshot: (WalletId, ByteArray) -> ByteArray,
    private val installSnapshot: suspend (WalletId, ByteArray, BackupCheckpointV1) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    suspend fun initializeOrVerify(walletId: WalletId, snapshot: ByteArray): BackupCheckpointV1 =
        if (checkpoint(walletId) == null) initialize(walletId, snapshot) else verify(walletId)

    suspend fun initialize(walletId: WalletId, snapshot: ByteArray): BackupCheckpointV1 {
        require(checkpoint(walletId) == null) { "backup is already initialized" }
        val normalized = normalizeSnapshot(walletId, snapshot)
        return try { write(walletId, 1, 1, ByteArray(32), normalized, false) } finally { normalized.fill(0) }
    }

    suspend fun writeNext(walletId: WalletId, snapshot: ByteArray): BackupCheckpointV1 {
        val current = requireNotNull(checkpoint(walletId)) { "verified backup is required" }
        return try {
            if (current.pending) resumePending(walletId, current) else {
                val normalized = normalizeSnapshot(walletId, snapshot)
                try { write(walletId, current.generation, current.sequence + 1, current.ciphertextHash, normalized, false) }
                finally { normalized.fill(0) }
            }
        } finally { current.clear() }
    }

    suspend fun restore(walletId: WalletId): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val latest = backups.verifyChain(backups.discover(walletId, seed))
        val plaintext = backups.decrypt(seed, latest)
        val normalized = normalizeSnapshot(walletId, plaintext)
        val checkpoint = BackupCheckpointV1(
            generation = latest.generation,
            sequence = latest.sequence,
            ciphertextHash = crypto.sha256(latest.ciphertext),
            channelSnapshot = plaintext.copyOf(),
        )
        try {
            validate(checkpoint)
            installSnapshot(walletId, normalized, checkpoint)
            checkpoint.copyArrays()
        } finally {
            plaintext.fill(0)
            normalized.fill(0)
            checkpoint.clear()
        }
    }

    suspend fun takeover(walletId: WalletId): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val latest = backups.verifyChain(backups.discover(walletId, seed))
        val plaintext = backups.decrypt(seed, latest)
        val normalized = try { normalizeSnapshot(walletId, plaintext) } finally { plaintext.fill(0) }
        try { write(walletId, latest.generation + 1, 1, ByteArray(32), normalized, true) }
        finally { normalized.fill(0) }
    }

    suspend fun replaceMissing(walletId: WalletId, snapshot: ByteArray): BackupCheckpointV1 {
        val local = requireNotNull(checkpoint(walletId))
        val expected = normalizeSnapshot(walletId, local.channelSnapshot)
        val replacement = normalizeSnapshot(walletId, snapshot)
        try {
            require(!local.pending && local.generation == 1L && local.sequence == 1L)
            require(expected.contentEquals(replacement)) { "local backup state changed" }
            vault.withWalletSeed(walletId) { seed ->
                require(backups.discover(walletId, seed).isEmpty()) { "encrypted backup is no longer missing" }
            }
            return write(walletId, 1, 1, ByteArray(32), replacement, false)
        } finally {
            local.clear(); expected.fill(0); replacement.fill(0)
        }
    }

    suspend fun verify(walletId: WalletId): BackupCheckpointV1 {
        val local = requireNotNull(checkpoint(walletId))
        if (local.pending) return try { resumePending(walletId, local) } finally { local.clear() }
        var remote: BackupCheckpointV1? = null
        var returned = false
        try {
            remote = vault.withWalletSeed(walletId) { seed ->
                val latest = backups.latest(walletId, seed)
                    ?: throw MissingBackupException(local.generation == 1L && local.sequence == 1L)
                val plaintext = backups.decrypt(seed, latest)
                try {
                    normalizeSnapshot(walletId, plaintext).let { normalized ->
                        normalized.fill(0)
                        BackupCheckpointV1(
                            generation = latest.generation,
                            sequence = latest.sequence,
                            ciphertextHash = crypto.sha256(latest.ciphertext),
                            channelSnapshot = plaintext.copyOf(),
                        )
                    }
                } finally { plaintext.fill(0) }
            }
            val stored = checkNotNull(remote)
            if (stored.generation > local.generation || stored.generation == local.generation && stored.sequence > local.sequence) {
                throw StaleBackupWriterException(stored.generation, stored.sequence)
            }
            require(stored.generation == local.generation && stored.sequence == local.sequence)
            val localNormalized = normalizeSnapshot(walletId, local.channelSnapshot)
            val remoteNormalized = normalizeSnapshot(walletId, stored.channelSnapshot)
            try {
                require(stored.ciphertextHash.contentEquals(local.ciphertextHash) && remoteNormalized.contentEquals(localNormalized))
            } finally { localNormalized.fill(0); remoteNormalized.fill(0) }
            returned = true
            return stored
        } finally {
            local.clear()
            if (!returned) remote?.clear()
        }
    }

    suspend fun checkpoint(walletId: WalletId): BackupCheckpointV1? {
        val state = vault.walletState(walletId)
        val bytes = state.channelRecovery
        if (bytes.isEmpty()) {
            state.operationJournal.fill(0)
            return null
        }
        return try { json.decodeFromString<BackupCheckpointV1>(bytes.decodeToString()).also(::validate) }
        finally { bytes.fill(0); state.operationJournal.fill(0) }
    }

    suspend fun delete(walletId: WalletId) {
        vault.withWalletSeed(walletId) { seed -> backups.deleteAll(walletId, seed) }
        val current = vault.walletState(walletId)
        try {
            vault.updateWalletState(walletId, current.copy(channelRecovery = byteArrayOf(), backupGeneration = 0))
        } finally { current.channelRecovery.fill(0); current.operationJournal.fill(0) }
    }

    private suspend fun write(
        walletId: WalletId,
        generation: Long,
        sequence: Long,
        previousHash: ByteArray,
        snapshot: ByteArray,
        installOnCommit: Boolean,
    ): BackupCheckpointV1 = vault.withWalletSeed(walletId) { seed ->
        val candidate = BackupCheckpointV1(
            generation = generation,
            sequence = sequence,
            ciphertextHash = ByteArray(32),
            channelSnapshot = snapshot.copyOf(),
            pending = true,
            previousHash = previousHash.copyOf(),
            snapshotDigest = crypto.sha256(snapshot),
            installOnCommit = installOnCommit,
        )
        save(walletId, candidate)
        try {
            val backup = backups.write(walletId, seed, generation, sequence, previousHash, nowEpochMillis(), snapshot)
            complete(walletId, BackupCheckpointV1(
                generation = generation,
                sequence = sequence,
                ciphertextHash = crypto.sha256(backup.ciphertext),
                channelSnapshot = snapshot.copyOf(),
            ), installOnCommit)
        } finally { candidate.clear() }
    }

    private suspend fun save(walletId: WalletId, checkpoint: BackupCheckpointV1): BackupCheckpointV1 {
        validate(checkpoint)
        val current = vault.walletState(walletId)
        val encoded = json.encodeToString(checkpoint).encodeToByteArray()
        try {
            vault.updateWalletState(walletId, current.copy(channelRecovery = encoded, backupGeneration = checkpoint.generation))
            return checkpoint.copyArrays()
        } finally { current.channelRecovery.fill(0); current.operationJournal.fill(0); encoded.fill(0) }
    }

    private suspend fun complete(
        walletId: WalletId,
        checkpoint: BackupCheckpointV1,
        install: Boolean,
    ): BackupCheckpointV1 = try {
        validate(checkpoint)
        if (install) installSnapshot(walletId, checkpoint.channelSnapshot, checkpoint) else save(walletId, checkpoint).clear()
        checkpoint.copyArrays()
    } finally { checkpoint.clear() }

    private suspend fun resumePending(walletId: WalletId, candidate: BackupCheckpointV1): BackupCheckpointV1 =
        vault.withWalletSeed(walletId) { seed ->
            require(candidate.pending && candidate.snapshotDigest.contentEquals(crypto.sha256(candidate.channelSnapshot)))
            val matching = backups.discover(walletId, seed).singleOrNull {
                it.generation == candidate.generation && it.sequence == candidate.sequence
            }
            if (matching != null) {
                require(matching.previousCiphertextHash.contentEquals(candidate.previousHash))
                val plaintext = backups.decrypt(seed, matching)
                val normalized = try { normalizeSnapshot(walletId, plaintext) } finally { plaintext.fill(0) }
                try {
                    require(crypto.sha256(normalized).contentEquals(candidate.snapshotDigest))
                    complete(walletId, BackupCheckpointV1(
                        generation = matching.generation,
                        sequence = matching.sequence,
                        ciphertextHash = crypto.sha256(matching.ciphertext),
                        channelSnapshot = normalized.copyOf(),
                    ), candidate.installOnCommit)
                } finally { normalized.fill(0) }
            } else {
                val backup = backups.write(
                    walletId, seed, candidate.generation, candidate.sequence, candidate.previousHash,
                    nowEpochMillis(), candidate.channelSnapshot,
                )
                complete(walletId, BackupCheckpointV1(
                    generation = candidate.generation,
                    sequence = candidate.sequence,
                    ciphertextHash = crypto.sha256(backup.ciphertext),
                    channelSnapshot = candidate.channelSnapshot.copyOf(),
                ), candidate.installOnCommit)
            }
        }

    private fun validate(checkpoint: BackupCheckpointV1) {
        require(checkpoint.schema == 1 && checkpoint.generation >= 1 && checkpoint.sequence >= 1)
        require(checkpoint.ciphertextHash.size == 32 && checkpoint.channelSnapshot.size in 1..1_048_576)
        if (checkpoint.pending) {
            require(checkpoint.previousHash.size == 32 && checkpoint.snapshotDigest.size == 32)
        } else {
            require(checkpoint.previousHash.isEmpty() && checkpoint.snapshotDigest.isEmpty() && !checkpoint.installOnCommit)
        }
    }

    private fun BackupCheckpointV1.copyArrays() = copy(
        ciphertextHash = ciphertextHash.copyOf(), channelSnapshot = channelSnapshot.copyOf(),
        previousHash = previousHash.copyOf(), snapshotDigest = snapshotDigest.copyOf(),
    )

    private fun BackupCheckpointV1.clear() {
        ciphertextHash.fill(0); channelSnapshot.fill(0); previousHash.fill(0); snapshotDigest.fill(0)
    }
}
