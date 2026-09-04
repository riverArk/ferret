package io.riverark.ferret.core.channel

import io.riverark.ferret.core.backup.BackupCheckpointV1
import io.riverark.ferret.core.network.SessionClaimRequest
import io.riverark.ferret.core.network.SessionClaimResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WriterLease(
    val token: String,
    val generation: Long,
    val backupHashHex: String,
    val devicePublicKeyHex: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(token))
        require(generation >= 1)
        require(Regex("[0-9a-f]{64}").matches(backupHashHex))
        require(Regex("[0-9a-f]{64}").matches(devicePublicKeyHex))
        require(expiresAtEpochMillis >= 0)
    }
}

class SessionLeaseRepository(
    private val claimSession: suspend (SessionClaimRequest) -> SessionClaimResponse,
    private val adaptorVerificationKeyHex: String,
    private val signer: ProtocolSigner,
    private val devicePublicKeyHex: String,
    private val nowEpochMillis: () -> Long,
) {
    private val mutex = Mutex()
    private var lease: WriterLease? = null
    private var lastClaimTimestamp = -1L

    suspend fun claim(checkpoint: BackupCheckpointV1): WriterLease = mutex.withLock {
        require(checkpoint.generation >= 1)
        require(checkpoint.ciphertextHash.size == 32)
        require(Regex("[0-9a-f]{64}").matches(adaptorVerificationKeyHex))
        require(Regex("[0-9a-f]{64}").matches(devicePublicKeyHex))
        val backupHashHex = checkpoint.ciphertextHash.toHex()
        val now = nowEpochMillis()
        lease?.takeIf {
            it.generation == checkpoint.generation &&
                it.backupHashHex == backupHashHex &&
                it.devicePublicKeyHex == devicePublicKeyHex &&
                it.expiresAtEpochMillis > now
        }?.let { return@withLock it }

        val timestamp = maxOf(now, lastClaimTimestamp + 1)
        val walletVerificationKeyHex = signer.verificationKeyHex().also { require(Regex("[0-9a-f]{64}").matches(it)) }
        val message = sessionClaimMessage(adaptorVerificationKeyHex, checkpoint.generation, backupHashHex, devicePublicKeyHex, timestamp)
        val signatureHex = signer.sign(message).also { require(it.size == 64) }.toHex()
        val response = claimSession(SessionClaimRequest(
            walletVerificationKeyHex,
            adaptorVerificationKeyHex,
            checkpoint.generation,
            backupHashHex,
            devicePublicKeyHex,
            timestamp,
            signatureHex,
        ))
        require(response.expiresAtEpochMillis > now) { "writer lease already expired" }
        lastClaimTimestamp = timestamp
        WriterLease(response.lease, checkpoint.generation, backupHashHex, devicePublicKeyHex, response.expiresAtEpochMillis)
            .also { lease = it }
    }

    fun requireValid(): WriterLease {
        val current = lease ?: error("writer lease not claimed")
        require(current.expiresAtEpochMillis > nowEpochMillis()) { "writer lease expired" }
        return current
    }

    fun clear() { lease = null }
}

internal fun sessionClaimMessage(
    adaptorVerificationKeyHex: String,
    generation: Long,
    backupHashHex: String,
    devicePublicKeyHex: String,
    timestamp: Long,
) = "$adaptorVerificationKeyHex\n$generation\n$backupHashHex\n$devicePublicKeyHex\n$timestamp".encodeToByteArray()

private fun ByteArray.toHex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
