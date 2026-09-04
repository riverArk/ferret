package io.riverark.ferret.core.channel

import io.riverark.ferret.core.network.AdaptorClient
import io.riverark.ferret.core.network.SessionClaimRequest
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
    private val adaptor: AdaptorClient,
    private val adaptorVerificationKeyHex: String,
    private val signer: ProtocolSigner,
) {
    private val mutex = Mutex()
    private var lease: WriterLease? = null

    suspend fun claim(
        generation: Long,
        backupHashHex: String,
        devicePublicKeyHex: String,
        nowEpochMillis: Long,
    ): WriterLease = mutex.withLock {
        require(generation >= 1)
        require(Regex("[0-9a-f]{64}").matches(adaptorVerificationKeyHex))
        require(Regex("[0-9a-f]{64}").matches(backupHashHex))
        require(Regex("[0-9a-f]{64}").matches(devicePublicKeyHex))
        val walletVerificationKeyHex = signer.verificationKeyHex().also { require(Regex("[0-9a-f]{64}").matches(it)) }
        val message = sessionClaimMessage(adaptorVerificationKeyHex, generation, backupHashHex, devicePublicKeyHex, nowEpochMillis)
        val signatureHex = signer.sign(message).also { require(it.size == 64) }.toHex()
        val response = adaptor.claim(SessionClaimRequest(
            walletVerificationKeyHex,
            adaptorVerificationKeyHex,
            generation,
            backupHashHex,
            devicePublicKeyHex,
            nowEpochMillis,
            signatureHex,
        ))
        require(response.expiresAtEpochMillis > nowEpochMillis) { "writer lease already expired" }
        WriterLease(response.lease, generation, backupHashHex, devicePublicKeyHex, response.expiresAtEpochMillis)
            .also { lease = it }
    }

    fun requireValid(nowEpochMillis: Long): WriterLease {
        val current = lease ?: error("writer lease not claimed")
        require(current.expiresAtEpochMillis > nowEpochMillis) { "writer lease expired" }
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
