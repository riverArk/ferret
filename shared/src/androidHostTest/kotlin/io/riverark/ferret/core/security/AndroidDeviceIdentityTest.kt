package io.riverark.ferret.core.security

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidDeviceIdentityTest {
    @Test fun persistsOnePublicIdentityAndFailsClosedOnCorruption() {
        val directory = File(System.getProperty("java.io.tmpdir"), "ferret-device-${System.nanoTime()}").also { it.mkdirs() }
        val file = directory.resolve("identity")
        try {
            val first = AndroidDeviceIdentity(file) { bytes -> bytes.fill(0x5a) }.publicKeyHex()
            val second = AndroidDeviceIdentity(file) { error("must not regenerate") }.publicKeyHex()
            assertEquals("5a".repeat(32), first)
            assertEquals(first, second)

            file.writeBytes(byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> {
                AndroidDeviceIdentity(file) { error("must not regenerate") }.publicKeyHex()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
