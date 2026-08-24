package io.riverark.ferret.core.security

import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import io.riverark.ferret.core.model.RecoveryPhraseCodec

class AndroidRecoveryPhraseCodec : RecoveryPhraseCodec {
    override fun words(entropy: ByteArray): List<String> = MnemonicCode.INSTANCE.toMnemonic(entropy)
    override fun entropy(words: List<String>): ByteArray = MnemonicCode.INSTANCE.toEntropy(words)
}
