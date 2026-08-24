package io.riverark.ferret.core.channel

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class Hex32(val value: String) {
    init { require(Regex("[0-9a-f]{64}").matches(value)) }
}

@JvmInline
@Serializable
value class ProtocolTag(val value: String) {
    init { require(value.length >= 2 && value.length % 2 == 0 && value.all { it in "0123456789abcdef" }) }
}

@Serializable
data class ProtocolQuote(
    val index: Long,
    val amount: Long,
    val relative_timeout: Long,
    val routing_fee: Long,
) {
    init { require(index >= 0 && amount >= 0 && relative_timeout > 0 && routing_fee >= 0) }
}

@Serializable
data class ChequeBodyWire(val index: Long, val amount: Long, val timeoutMillis: Long, val lock: Hex32) {
    init { require(index >= 0 && amount >= 0 && timeoutMillis > 0) }

    fun canonicalCbor(): ByteArray = CborWriter().apply {
        indefiniteArray()
        unsigned(index)
        unsigned(amount)
        unsigned(timeoutMillis)
        bytes(lock.value.hexBytes())
        end()
    }.toByteArray()

    fun taggedCbor(tag: ProtocolTag): ByteArray = CborWriter().apply {
        indefiniteArray()
        bytes(tag.value.hexBytes())
        raw(canonicalCbor())
        end()
    }.toByteArray()
}

interface ProtocolSigner {
    suspend fun verificationKeyHex(): String
    suspend fun sign(message: ByteArray): ByteArray
}

@Serializable
data class SignedChequeWire(val body: ChequeBodyWire, val signature: String) {
    init { require(Regex("[0-9a-f]{128}").matches(signature)) }
}

private class CborWriter {
    private val bytes = mutableListOf<Byte>()
    fun indefiniteArray() { bytes += 0x9f.toByte() }
    fun end() { bytes += 0xff.toByte() }
    fun raw(value: ByteArray) { value.forEach(bytes::add) }
    fun unsigned(value: Long) {
        require(value >= 0)
        when {
            value < 24 -> bytes += value.toByte()
            value <= 0xff -> { bytes += 0x18; bytes += value.toByte() }
            value <= 0xffff -> { bytes += 0x19; append(value, 2) }
            value <= 0xffffffffL -> { bytes += 0x1a; append(value, 4) }
            else -> { bytes += 0x1b; append(value, 8) }
        }
    }
    fun bytes(value: ByteArray) {
        val size = value.size.toLong()
        when {
            size < 24 -> bytes += (0x40 + size).toByte()
            size <= 0xff -> { bytes += 0x58; bytes += size.toByte() }
            size <= 0xffff -> { bytes += 0x59; append(size, 2) }
            else -> error("protocol byte string too large")
        }
        raw(value)
    }
    private fun append(value: Long, count: Int) {
        for (shift in (count - 1) * 8 downTo 0 step 8) bytes += (value ushr shift).toByte()
    }
    fun toByteArray() = bytes.toByteArray()
}

private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
