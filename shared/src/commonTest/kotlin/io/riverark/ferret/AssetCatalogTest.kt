package io.riverark.ferret

import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.parseAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetCatalogTest {
    private val digest = "09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae"
    private val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
    private val usdm = ChannelAsset("usdm", "c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad", "0014df105553444d", 6, AssetPricing.USD_PEG, digest)

    @Test fun formatsAndParsesIntegerAmountsWithoutFloatingPoint() {
        listOf(1L to "0.000001", 1_000_001L to "1.000001", Long.MAX_VALUE to "9223372036854.775807").forEach { (units, text) ->
            assertEquals(text, AssetAmount(ada, units).format())
            assertEquals(units, ada.parseAmount(text).baseUnits)
        }
        val precise = ChannelAsset("precise", "0".repeat(56), "", 19, AssetPricing.USD_PEG, digest)
        assertEquals("0.0000000000000000001", AssetAmount(precise, 1).format())
        assertEquals(Long.MAX_VALUE, precise.parseAmount("0.9223372036854775807").baseUnits)
    }

    @Test fun rejectsUnboundInvalidAndOverflowingAmounts() {
        val changedDigest = usdm.copy(catalogDigest = "a".repeat(64))
        listOf("-1", "+1", "1e2", "0.0000001", "9223372036854.775808").forEach {
            assertFailsWith<IllegalArgumentException> { ada.parseAmount(it) }
        }
        assertFailsWith<IllegalArgumentException> { AssetAmount(ada, -1) }
        assertFailsWith<IllegalArgumentException> { AssetAmount(ada, Long.MAX_VALUE) + AssetAmount(ada, 1) }
        assertFailsWith<IllegalArgumentException> { AssetAmount(usdm.copy(decimals = 5), 1) + AssetAmount(usdm, 1) }
        assertFailsWith<IllegalArgumentException> { AssetAmount(usdm, 1) + AssetAmount(changedDigest, 1) }
        assertFailsWith<IllegalArgumentException> { ChannelAsset("ada", "0".repeat(56), "", 6, AssetPricing.USD_PEG, digest) }
    }

    @Test fun catalogMembershipRequiresEveryAssetField() {
        val assets = listOf(
            ada,
            ChannelAsset("usda", "fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae456", "55534441", 6, AssetPricing.USD_PEG, digest),
            ChannelAsset("usdcx", "1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e34", "5553444378", 6, AssetPricing.USD_PEG, digest),
            usdm,
        )
        val catalog = AssetCatalog(assets, digest, emptyMap())
        assertEquals(ada, catalog.assetForConnectorUnit("lovelace"))
        assertEquals(usdm, catalog.requireAsset(usdm))
        catalog.requireDiscoveryDigest(digest)
        assertFailsWith<IllegalArgumentException> { catalog.requireAsset(usdm.copy(assetName = "5553444d")) }
        assertFailsWith<IllegalArgumentException> { catalog.requireAsset(usdm.copy(decimals = 5)) }
        assertFailsWith<IllegalArgumentException> { catalog.requireAsset(usdm.copy(pricing = AssetPricing.ADA)) }
        assertFailsWith<IllegalArgumentException> { catalog.requireDiscoveryDigest(null) }
        assertFailsWith<IllegalArgumentException> { catalog.requireDiscoveryDigest("a".repeat(64)) }
    }
}
