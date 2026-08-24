package io.riverark.ferret

import io.riverark.ferret.core.channel.ChequeBodyWire
import io.riverark.ferret.core.channel.Hex32
import io.riverark.ferret.core.channel.ProtocolTag
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolWireTest {
    @Test fun chequeSignaturePayloadMatchesMinicborIndefiniteArrays() {
        val actual = ChequeBodyWire(1, 2, 3, Hex32("00".repeat(32))).taggedCbor(ProtocolTag("aa"))
        assertEquals("9f41aa9f0102035820${"00".repeat(32)}ffff", actual.toHex())
    }
}

private fun ByteArray.toHex() = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
