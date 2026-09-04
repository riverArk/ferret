package io.riverark.ferret.core.channel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
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

@JvmInline
@Serializable
value class ProtocolKeytag(val value: String) {
    init {
        require(value.length in 66..320 && value.length % 2 == 0)
        require(value.all { it in "0123456789abcdef" })
    }

    companion object {
        fun from(walletVerificationKeyHex: String, tag: ProtocolTag, expectedTagBytes: Int): ProtocolKeytag {
            require(Regex("[0-9a-f]{64}").matches(walletVerificationKeyHex))
            require(tag.value.length == expectedTagBytes * 2)
            return ProtocolKeytag(walletVerificationKeyHex + tag.value)
        }
    }
}

@Serializable
data class ProtocolDurationWire(val secs: Long, val nanos: Int) {
    init { require(secs >= 0 && nanos in 0..999_999_999) }

    fun millis(): Long {
        require(secs <= Long.MAX_VALUE / 1_000)
        val whole = secs * 1_000
        val fractional = nanos / 1_000_000
        require(whole <= Long.MAX_VALUE - fractional)
        return whole + fractional
    }

    companion object {
        fun fromMillis(millis: Long): ProtocolDurationWire {
            require(millis >= 0)
            return ProtocolDurationWire(millis / 1_000, ((millis % 1_000) * 1_000_000).toInt())
        }
    }
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
data class Bolt11QuoteRequest(@SerialName("Bolt11") val invoice: String) {
    init { require(invoice.length in 1..10_000 && invoice.startsWith("ln", ignoreCase = true)) }
}

@Serializable
data class ChequeBodyWire(
    val index: Long,
    val amount: Long,
    val timeout: ProtocolDurationWire,
    val latch: Hex32,
) {
    init { require(index >= 0 && amount >= 0) }

    fun canonicalCbor(): ByteArray = CborWriter().apply {
        indefiniteArray()
        unsigned(index)
        unsigned(amount)
        unsigned(timeout.millis())
        bytes(latch.value.hexBytes())
        end()
    }.toByteArray()

    fun taggedCbor(tag: ProtocolTag): ByteArray = CborWriter().apply {
        indefiniteArray()
        bytes(tag.value.hexBytes())
        raw(canonicalCbor())
        end()
    }.toByteArray()
}

@Serializable
data class AdaptorPayRequest(
    @SerialName("cheque_body") val chequeBody: ChequeBodyWire,
    val signature: String,
    val invoice: String,
) {
    init {
        require(Regex("[0-9a-f]{128}").matches(signature))
        require(invoice.length in 1..10_000 && invoice.startsWith("ln", ignoreCase = true))
    }
}

@Serializable
data class SquashBodyWire(
    val amount: Long,
    val index: Long,
    val exclude: List<Long>,
) {
    init {
        require(amount >= 0 && index >= 0)
        require(exclude.size <= MAX_PROTOCOL_CHEQUES)
        require(exclude.zipWithNext().all { (before, after) -> before < after })
        require(exclude.all { it >= 0 && it < index })
    }
}

@Serializable
data class SignedSquashWire(
    val body: SquashBodyWire,
    val signature: String,
) {
    init { require(Regex("[0-9a-f]{128}").matches(signature)) }
}

@Serializable
data class SignedChequeWire(
    val body: ChequeBodyWire,
    val signature: String,
) {
    init { require(Regex("[0-9a-f]{128}").matches(signature)) }
}

@Serializable(with = ProtocolChequeSerializer::class)
sealed interface ProtocolCheque {
    data class Unlocked(val value: SignedChequeWire) : ProtocolCheque
    data class Locked(val value: SignedChequeWire) : ProtocolCheque
}

@Serializable
data class ProtocolSquashProposal(
    val proposal: SquashBodyWire,
    val current: SignedSquashWire,
    val unlockeds: List<SignedChequeWire>,
    val lockeds: List<SignedChequeWire>,
) {
    init {
        require(unlockeds.size <= MAX_PROTOCOL_CHEQUES)
        require(lockeds.size <= MAX_PROTOCOL_CHEQUES)
    }
}

@Serializable(with = ProtocolSquashStatusSerializer::class)
sealed interface ProtocolSquashStatus {
    data object Complete : ProtocolSquashStatus
    data class Incomplete(val proposal: ProtocolSquashProposal) : ProtocolSquashStatus
    data class Stale(val proposal: ProtocolSquashProposal) : ProtocolSquashStatus
}

@Serializable
data class ProtocolReceipt(
    val squash: SignedSquashWire,
    val cheques: List<ProtocolCheque>,
) {
    init { require(cheques.size <= MAX_PROTOCOL_CHEQUES) }
}

object ProtocolChequeSerializer : KSerializer<ProtocolCheque> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ProtocolCheque")

    override fun deserialize(decoder: Decoder): ProtocolCheque {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ProtocolCheque is JSON-only")
        val element = json.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("ProtocolCheque must be an object")
        if (element.size != 1) throw SerializationException("ProtocolCheque must contain one variant")
        val (name, value) = element.entries.single()
        val cheque = json.json.decodeFromJsonElement<SignedChequeWire>(value)
        return when (name) {
            "Unlocked" -> ProtocolCheque.Unlocked(cheque)
            "Locked" -> ProtocolCheque.Locked(cheque)
            else -> throw SerializationException("unknown ProtocolCheque variant")
        }
    }

    override fun serialize(encoder: Encoder, value: ProtocolCheque) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ProtocolCheque is JSON-only")
        val (name, cheque) = when (value) {
            is ProtocolCheque.Unlocked -> "Unlocked" to value.value
            is ProtocolCheque.Locked -> "Locked" to value.value
        }
        json.encodeJsonElement(JsonObject(mapOf(name to json.json.encodeToJsonElement(cheque))))
    }
}

object ProtocolSquashStatusSerializer : KSerializer<ProtocolSquashStatus> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ProtocolSquashStatus")

    override fun deserialize(decoder: Decoder): ProtocolSquashStatus {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ProtocolSquashStatus is JSON-only")
        val element = json.decodeJsonElement()
        if (element is JsonPrimitive && element.isString && element.content == "Complete") {
            return ProtocolSquashStatus.Complete
        }
        val objectValue = element as? JsonObject
            ?: throw SerializationException("invalid ProtocolSquashStatus")
        if (objectValue.size != 1) throw SerializationException("ProtocolSquashStatus must contain one variant")
        val (name, proposalJson) = objectValue.entries.single()
        val proposal = json.json.decodeFromJsonElement<ProtocolSquashProposal>(proposalJson)
        return when (name) {
            "Incomplete" -> ProtocolSquashStatus.Incomplete(proposal)
            "Stale" -> ProtocolSquashStatus.Stale(proposal)
            else -> throw SerializationException("unknown ProtocolSquashStatus variant")
        }
    }

    override fun serialize(encoder: Encoder, value: ProtocolSquashStatus) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ProtocolSquashStatus is JSON-only")
        val element = when (value) {
            ProtocolSquashStatus.Complete -> JsonPrimitive("Complete")
            is ProtocolSquashStatus.Incomplete ->
                JsonObject(mapOf("Incomplete" to json.json.encodeToJsonElement(value.proposal)))
            is ProtocolSquashStatus.Stale ->
                JsonObject(mapOf("Stale" to json.json.encodeToJsonElement(value.proposal)))
        }
        json.encodeJsonElement(element)
    }
}

private const val MAX_PROTOCOL_CHEQUES = 10

interface ProtocolSigner {
    suspend fun verificationKeyHex(): String
    suspend fun sign(message: ByteArray): ByteArray
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
