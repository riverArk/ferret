package io.riverark.ferret.core.channel

import io.riverark.ferret.core.network.ConnectorClient
import io.riverark.ferret.core.network.SessionClaimRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WriterLease(
    val token: String,
    val generation: Long,
    val backupHashHex: String,
    val devicePublicKeyHex: String,
    val expiresAtEpochMillis: Long,
)

class SessionLeaseRepository(
    private val connector: ConnectorClient,
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
        require(Regex("[0-9a-f]{64}").matches(backupHashHex))
        require(Regex("[0-9a-f]{64}").matches(devicePublicKeyHex))
        val walletVerificationKeyHex = signer.verificationKeyHex().also { require(Regex("[0-9a-f]{64}").matches(it)) }
        val message = "$generation\n$backupHashHex\n$devicePublicKeyHex\n$nowEpochMillis".encodeToByteArray()
        val signatureHex = signer.sign(message).also { require(it.size == 64) }.toHex()
        val response = connector.claim(SessionClaimRequest(
            walletVerificationKeyHex,
            generation,
            backupHashHex,
            devicePublicKeyHex,
            nowEpochMillis,
            signatureHex,
        ))
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

private fun ByteArray.toHex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
