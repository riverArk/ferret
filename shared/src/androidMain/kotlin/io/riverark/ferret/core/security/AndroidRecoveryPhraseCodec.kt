package io.riverark.ferret.core.security

import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.crypto.bip39.MnemonicException
import io.riverark.ferret.core.model.RecoveryPhraseCodec
import io.riverark.ferret.core.model.InvalidRecoveryPhraseException

class AndroidRecoveryPhraseCodec : RecoveryPhraseCodec {
    override fun words(entropy: ByteArray): List<String> = MnemonicCode.INSTANCE.toMnemonic(entropy)
    override fun entropy(words: List<String>): ByteArray =
        try {
            MnemonicCode.INSTANCE.toEntropy(words)
        } catch (_: MnemonicException) {
            throw InvalidRecoveryPhraseException()
        }
}
