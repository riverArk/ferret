package io.riverark.ferret.core.security

import android.util.Base64
import io.riverark.ferret.core.backup.BackupCrypto
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AndroidBackupCrypto : BackupCrypto {
    private val random = SecureRandom()
    override fun random(size: Int) = ByteArray(size).also(random::nextBytes)
    override fun sha256(input: ByteArray) = MessageDigest.getInstance("SHA-256").digest(input)

    override fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        require(size in 1..8160)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val pseudoRandomKey = mac.doFinal(input)
        val output = ByteArray(size)
        var previous = byteArrayOf()
        var offset = 0
        var counter = 1
        try {
            while (offset < size) {
                mac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
                previous = mac.doFinal(previous + info + counter.toByte())
                val count = minOf(previous.size, size - offset)
                previous.copyInto(output, offset, 0, count)
                offset += count
                counter++
            }
            return output
        } finally { pseudoRandomKey.fill(0); previous.fill(0) }
    }

    override fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    override fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)

    override fun base64Url(input: ByteArray): String = Base64.encodeToString(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
}
