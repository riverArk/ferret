package io.riverark.ferret

import io.riverark.ferret.core.channel.AdaptorPayRequest
import io.riverark.ferret.core.channel.Bolt11QuoteRequest
import io.riverark.ferret.core.channel.ChequeBodyWire
import io.riverark.ferret.core.channel.Hex32
import io.riverark.ferret.core.channel.ProtocolDurationWire
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.ProtocolTag
import io.riverark.ferret.core.network.decodeBoundedJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolWireTest {
    @Test fun chequeSignaturePayloadMatchesPinnedKonduitCbor() {
        val actual = ChequeBodyWire(
            1,
            2,
            ProtocolDurationWire.fromMillis(3),
            Hex32("00".repeat(32)),
        ).taggedCbor(ProtocolTag("aa"))
        assertEquals("9f41aa9f0102035820${"00".repeat(32)}ffff", actual.toHex())
    }

    @Test fun adaptorRequestsMatchPinnedKonduitJson() {
        val invoice = "lnbc1fixture"
        val body = ChequeBodyWire(
            1,
            2,
            ProtocolDurationWire(3, 4),
            Hex32("ab".repeat(32)),
        )

        assertEquals("""{"Bolt11":"$invoice"}""", Json.encodeToString(Bolt11QuoteRequest(invoice)))
        assertEquals(
            """{"cheque_body":{"index":1,"amount":2,"timeout":{"secs":3,"nanos":4},"latch":"${"ab".repeat(32)}"},"signature":"${"cd".repeat(64)}","invoice":"$invoice"}""",
            Json.encodeToString(AdaptorPayRequest(body, "cd".repeat(64), invoice)),
        )

        val quote = decodeBoundedJson<io.riverark.ferret.core.channel.ProtocolQuote>(
            """{"index":7,"amount":8,"relative_timeout":9,"routing_fee":10}""".encodeToByteArray(),
        )
        assertEquals(7, quote.index)
        assertFailsWith<SerializationException> {
            decodeBoundedJson<io.riverark.ferret.core.channel.ProtocolQuote>(
                """{"index":7,"amount":8,"relative_timeout":9,"routing_fee":10,"extra":true}""".encodeToByteArray(),
            )
        }
    }

    @Test fun keytagBindsWalletKeyToConfiguredTagLength() {
        val walletKey = "11".repeat(32)
        val tag = ProtocolTag("22".repeat(32))

        assertEquals(walletKey + tag.value, ProtocolKeytag.from(walletKey, tag, 32).value)
        assertFailsWith<IllegalArgumentException> { ProtocolKeytag.from(walletKey, tag, 31) }
    }
}

private fun ByteArray.toHex() = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
