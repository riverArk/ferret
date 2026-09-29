@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.riverark.ferret.core.security

import io.riverark.ferret.core.backup.BackupCrypto
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

private const val CRYPTO_FAILURE = "iOS cryptographic operation failed."

class IosSecureRandomSource : SecureRandomSource {
    override fun bytes(size: Int): ByteArray {
        require(size >= 0)
        val result = ByteArray(size)
        if (size > 0 && result.usePinned {
                SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0))
            } != 0) {
            result.fill(0)
            throw IllegalArgumentException(CRYPTO_FAILURE)
        }
        return result
    }
}

class IosBackupCrypto(private val crypto: IosCrypto) : BackupCrypto {
    private val randomSource = IosSecureRandomSource()

    override fun random(size: Int): ByteArray = randomSource.bytes(size)

    override fun sha256(input: ByteArray): ByteArray =
        (crypto.sha256(input) ?: throw IllegalArgumentException(CRYPTO_FAILURE)).also {
            if (it.size != 32) {
                it.fill(0)
                throw IllegalArgumentException(CRYPTO_FAILURE)
            }
        }

    override fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        require(size in 1..8160)
        return (crypto.hkdfSha256(input, salt, info, size) ?: throw IllegalArgumentException(CRYPTO_FAILURE)).also {
            if (it.size != size) {
                it.fill(0)
                throw IllegalArgumentException(CRYPTO_FAILURE)
            }
        }
    }

    override fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        return (crypto.encryptAesGcm(key, nonce, plaintext, aad) ?: throw IllegalArgumentException(CRYPTO_FAILURE)).also {
            if (it.size.toLong() != plaintext.size.toLong() + 16L) {
                it.fill(0)
                throw IllegalArgumentException(CRYPTO_FAILURE)
            }
        }
    }

    override fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12 && ciphertext.size >= 16)
        return (crypto.decryptAesGcm(key, nonce, ciphertext, aad) ?: throw IllegalArgumentException(CRYPTO_FAILURE)).also {
            if (it.size != ciphertext.size - 16) {
                it.fill(0)
                throw IllegalArgumentException(CRYPTO_FAILURE)
            }
        }
    }

    override fun base64Url(input: ByteArray): String {
        if (input.isEmpty()) return ""
        return input.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), input.size.toULong())
                .base64EncodedStringWithOptions(0u)
        }.replace('+', '-').replace('/', '_').trimEnd('=')
    }
}
