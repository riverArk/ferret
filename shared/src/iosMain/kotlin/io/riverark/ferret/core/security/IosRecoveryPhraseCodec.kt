package io.riverark.ferret.core.security

import io.riverark.ferret.core.model.InvalidRecoveryPhraseException
import io.riverark.ferret.core.model.RecoveryPhraseCodec
import ferret.shared.generated.resources.Res
import kotlinx.coroutines.runBlocking

class IosRecoveryPhraseCodec(private val crypto: IosCrypto) : RecoveryPhraseCodec {
    override fun words(entropy: ByteArray): List<String> {
        require(entropy.size == 32) { "Recovery entropy must be 32 bytes." }
        val digest = sha256(entropy)
        val bits = ByteArray(33)
        try {
            entropy.copyInto(bits)
            bits[32] = digest[0]
            return List(24) { index ->
                var wordIndex = 0
                repeat(11) { bit ->
                    val position = index * 11 + bit
                    wordIndex = (wordIndex shl 1) or ((bits[position / 8].toInt() ushr (7 - position % 8)) and 1)
                }
                wordList[wordIndex]
            }
        } finally {
            digest.fill(0)
            bits.fill(0)
        }
    }

    override fun entropy(words: List<String>): ByteArray {
        if (words.size != 24) throw InvalidRecoveryPhraseException()
        val bits = ByteArray(33)
        try {
            words.forEachIndexed { index, word ->
                val wordIndex = wordList.binarySearch(word)
                if (wordIndex < 0) throw InvalidRecoveryPhraseException()
                repeat(11) { bit ->
                    val position = index * 11 + bit
                    bits[position / 8] = (bits[position / 8].toInt() or
                        (((wordIndex ushr (10 - bit)) and 1) shl (7 - position % 8))).toByte()
                }
            }
            val entropy = bits.copyOfRange(0, 32)
            try {
                val digest = sha256(entropy)
                try {
                    if (bits[32] != digest[0]) throw InvalidRecoveryPhraseException()
                } finally {
                    digest.fill(0)
                }
                return entropy
            } catch (failure: Throwable) {
                entropy.fill(0)
                throw failure
            }
        } finally {
            bits.fill(0)
        }
    }

    private fun sha256(input: ByteArray): ByteArray {
        val digest = crypto.sha256(input) ?: throw IllegalArgumentException("iOS cryptographic operation failed.")
        if (digest.size != 32) {
            digest.fill(0)
            throw IllegalArgumentException("iOS cryptographic operation failed.")
        }
        return digest
    }

    private companion object {
        val wordList: List<String> by lazy {
            val bytes = runBlocking { Res.readBytes("files/bip39-english.txt") }
            try {
                bytes.decodeToString().trimEnd('\n').split('\n').also { words ->
                    check(words.size == 2048 && words.indices.all { it == 0 || words[it - 1] < words[it] }) {
                        "BIP39 English word list is invalid."
                    }
                }
            } finally {
                bytes.fill(0)
            }
        }
    }
}
