package io.riverark.ferret.core.cardano

import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.Lovelace
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosCardanoIdentityTest {
    private val catalog = AssetCatalog(listOf(
        ChannelAsset("ada", null, null, 6, AssetPricing.ADA, "a".repeat(64)),
        ChannelAsset("usda", "1".repeat(56), "55", 6, AssetPricing.USD_PEG, "a".repeat(64)),
        ChannelAsset("usdcx", "2".repeat(56), "55", 6, AssetPricing.USD_PEG, "a".repeat(64)),
        ChannelAsset("usdm", "3".repeat(56), "55", 6, AssetPricing.USD_PEG, "a".repeat(64)),
    ), "a".repeat(64), emptyMap())

    @Test fun nativeDerivationMatchesAndroidCip1852Vector() = runBlocking {
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

    @Test fun transferAndSweepConserveAdaAndBindSigner() = runBlocking {
        val engine = IosCardanoTransactionEngine(catalog) { _, _ -> error("L1 requires no evaluation") }
        val seed = ByteArray(32) { it.toByte() }
        try {
            val source = engine.deriveWallet(seed, CardanoNetwork.PREPROD)
            val destination = engine.deriveWallet(ByteArray(32) { (it + 1).toByte() }, CardanoNetwork.PREPROD)
            val ledger = LedgerSnapshot(CardanoNetwork.PREPROD,
                listOf(LedgerUtxo("ab".repeat(32), 0, source.paymentAddress, Lovelace(20_000_000))),
                """{"coins_per_utxo_size":"4310","min_fee_a":44,"min_fee_b":155381,"max_tx_size":16384}""", 100)
            val transfer = CardanoIntent.Transfer(source.paymentAddress, destination.paymentAddress,
                AssetAmount(catalog.ada, 5_000_000), "00000000-0000-4000-8000-000000000001", 100, 200)
            val unsigned = engine.build(transfer, ledger)
            val summary = engine.inspect(unsigned.cbor)
            assertEquals(5_000_000, summary.outputs.single { it.address == destination.paymentAddress }.lovelace.value)
            assertEquals(20_000_000, summary.outputs.sumOf { it.lovelace.value } + summary.fee.value)
            assertFailsWith<IllegalArgumentException> {
                engine.requireAuthorized(unsigned, transfer.copy(destinationAddress = source.paymentAddress), ledger)
            }
            val signed = engine.sign(unsigned, seed, transfer, ledger)
            assertEquals(engine.transactionId(unsigned.cbor), engine.transactionId(signed.cbor))
            assertTrue(engine.inspect(signed.cbor).keyWitnesses.single().signatureValid)

            val sweep = CardanoIntent.SweepWallet(source.paymentAddress, destination.paymentAddress,
                Lovelace(1), "00000000-0000-4000-8000-000000000002", 100, 200)
            val swept = engine.buildSweep(sweep, ledger)
            val sweptSummary = engine.inspect(swept.cbor)
            assertEquals(1, sweptSummary.outputs.size)
            assertEquals(20_000_000, sweptSummary.outputs.single().lovelace.value + sweptSummary.fee.value)
            assertTrue(sweptSummary.outputs.single().lovelace.value > 1)
        } finally {
            seed.fill(0)
        }
    }
}
