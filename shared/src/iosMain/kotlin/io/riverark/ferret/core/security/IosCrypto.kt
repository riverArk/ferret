package io.riverark.ferret.core.security

/** CryptoKit operations supplied by the Swift application. A null result means failure. */
interface IosCrypto {
    fun sha256(input: ByteArray): ByteArray?
    fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray?
    fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray?
    fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?
}
