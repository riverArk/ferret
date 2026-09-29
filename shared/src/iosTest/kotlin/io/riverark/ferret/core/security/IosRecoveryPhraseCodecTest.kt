package io.riverark.ferret.core.security

import io.riverark.ferret.core.model.InvalidRecoveryPhraseException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosRecoveryPhraseCodecTest {
    // Public BIP-39 all-zero entropy vector: SHA-256 begins with 0x66.
    private val codec = IosRecoveryPhraseCodec(object : IosCrypto {
        override fun sha256(input: ByteArray): ByteArray? {
            require(input.size == 32 && input.all { it == 0.toByte() })
            return ByteArray(32).also { it[0] = 0x66 }
        }
        override fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray? = error("unexpected HKDF")
        override fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray? = error("unexpected AES")
        override fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? = error("unexpected AES")
    })

    @Test fun publicZeroVectorAndMalformedRecovery() {
        val zero = ByteArray(32)
        val words = List(23) { "abandon" } + "art"
        try {
            assertTrue(codec.words(zero) == words)
            assertTrue(codec.entropy(words).contentEquals(zero))
            assertFailsWith<InvalidRecoveryPhraseException> { codec.entropy(words.dropLast(1)) }
            assertFailsWith<InvalidRecoveryPhraseException> { codec.entropy(words.toMutableList().also { it[0] = "notaword" }) }
            assertFailsWith<InvalidRecoveryPhraseException> { codec.entropy(words.dropLast(1) + "abandon") }
            assertFailsWith<IllegalArgumentException> { codec.words(ByteArray(16)) }
        } finally {
            zero.fill(0)
        }
    }
}
