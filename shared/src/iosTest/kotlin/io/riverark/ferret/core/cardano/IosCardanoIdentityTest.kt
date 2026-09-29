package io.riverark.ferret.core.cardano

import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class IosCardanoIdentityTest {
    @Test fun nativeDerivationMatchesAndroidCip1852Vector() = runBlocking {
        val digest = "a".repeat(64)
        val catalog = AssetCatalog(listOf(
            ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest),
            ChannelAsset("usda", "1".repeat(56), "55", 6, AssetPricing.USD_PEG, digest),
            ChannelAsset("usdcx", "2".repeat(56), "55", 6, AssetPricing.USD_PEG, digest),
            ChannelAsset("usdm", "3".repeat(56), "55", 6, AssetPricing.USD_PEG, digest),
        ), digest, emptyMap())
        val engine = IosCardanoTransactionEngine(catalog) { _, _ -> error("No evaluation in derivation") }
        val seed = ByteArray(32) { it.toByte() }
        try {
            val preprod = engine.deriveWallet(seed, CardanoNetwork.PREPROD)
            val mainnet = engine.deriveWallet(seed, CardanoNetwork.MAINNET)
            assertEquals("addr_test1qqzkxpwrnvu3ylqvj6wupde0pjk4w28zu9893wu55z4upfcuafluqtl6qqeua5h8m66l6mxpvvqh0w7gfuwrs6npgtus705qux", preprod.paymentAddress)
            assertEquals("stake_test1uqww5l7q9laqqv7w6tnaad0adnqkxqthh0yy78pcdfs597gejrlds", preprod.stakeAddress)
            assertEquals("addr1qyzkxpwrnvu3ylqvj6wupde0pjk4w28zu9893wu55z4upfcuafluqtl6qqeua5h8m66l6mxpvvqh0w7gfuwrs6npgtusaefqse", mainnet.paymentAddress)
            assertEquals("stake1uyww5l7q9laqqv7w6tnaad0adnqkxqthh0yy78pcdfs597g7cfafd", mainnet.stakeAddress)
            assertEquals("056305c39b39127c0c969dc0b72f0cad5728e2e14e58bb94a0abc0a7", preprod.paymentCredentialHex)
            assertEquals(preprod.paymentCredentialHex, mainnet.paymentCredentialHex)
        } finally {
            seed.fill(0)
        }
    }
}
