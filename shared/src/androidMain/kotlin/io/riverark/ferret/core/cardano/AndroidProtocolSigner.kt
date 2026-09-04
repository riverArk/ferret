package io.riverark.ferret.core.cardano

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.util.HexUtil
import io.riverark.ferret.core.channel.ProtocolSigner
import io.riverark.ferret.core.channel.ProtocolCrypto
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.SecureVault
import java.security.MessageDigest

class AndroidProtocolSigner(
    private val vault: SecureVault,
    private val walletId: WalletId,
    private val network: CardanoNetwork,
) : ProtocolSigner {
    override suspend fun verificationKeyHex(): String = vault.withWalletSeed(walletId) { entropy ->
        val account = account(entropy)
        HexUtil.encodeHexString(account.publicKeyBytes())
    }

    override suspend fun sign(message: ByteArray): ByteArray = vault.withWalletSeed(walletId) { entropy ->
        val account = account(entropy)
        val privateKey = account.hdKeyPair().privateKey.keyData
        try { EdDSASigningProvider().signExtended(message, privateKey) } finally { privateKey.fill(0) }
    }

    private fun account(entropy: ByteArray): Account {
        require(entropy.size == 32)
        val mnemonic = MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" ")
        val bloxbeanNetwork = if (network == CardanoNetwork.MAINNET) Networks.mainnet() else Networks.preprod()
        return Account.createFromMnemonic(bloxbeanNetwork, mnemonic)
    }
}

object AndroidProtocolCrypto : ProtocolCrypto {
    private val edDsa = EdDSASigningProvider()

    override fun verify(verificationKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(verificationKey.size == 32 && signature.size == 64)
        return edDsa.verify(signature, message, verificationKey)
    }

    override fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}
