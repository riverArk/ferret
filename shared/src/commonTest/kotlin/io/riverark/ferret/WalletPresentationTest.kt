package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.feature.wallet.channelBalance
import io.riverark.ferret.feature.wallet.channelDisplayOrder
import io.riverark.ferret.feature.wallet.distinctKeytagSuffixes
import kotlin.test.Test
import kotlin.test.assertEquals

class WalletPresentationTest {
    @Test fun keytagLabelsExtendFromTheUniqueTagSuffix() {
        val first = "11".repeat(25) + "aaaa" + "22".repeat(6)
        val second = "33".repeat(25) + "bbbb" + "22".repeat(6)

        val labels = distinctKeytagSuffixes(listOf(first, second))

        assertEquals("aaaa" + "22".repeat(6), labels.getValue(first))
        assertEquals("bbbb" + "22".repeat(6), labels.getValue(second))
    }

    @Test fun channelBalanceSumsOnlyTheRequestedAsset() {
        val digest = "00".repeat(32)
        val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
        val usdm = ChannelAsset("usdm", "11".repeat(28), "", 6, AssetPricing.USD_PEG, digest)
        val channels = listOf(
            channel("01", ada, 10),
            channel("02", usdm, 7),
            channel("03", ada, 5),
        )

        assertEquals(AssetAmount(ada, 15), channelBalance(ada, channels))
        assertEquals(AssetAmount(usdm, 7), channelBalance(usdm, channels))
    }

    @Test fun openChannelsSortBeforeOtherAndNotOpenedChannels() {
        val digest = "00".repeat(32)
        val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
        val notOpened = channel("01", ada, 0)
        val closing = channel("02", ada, 0, ChannelState.Closing("closing"))
        val open = channel("03", ada, 1, ChannelState.Open("open"))

        assertEquals(listOf(open, closing, notOpened), channelDisplayOrder(listOf(notOpened, closing, open)))
    }

    private fun channel(
        byte: String,
        asset: ChannelAsset,
        units: Long,
        state: ChannelState = ChannelState.Absent,
    ) = ChannelSnapshot(
        ProtocolKeytag(byte.repeat(66)),
        asset,
        state,
        spendableBalance = AssetAmount(asset, units),
    )
}
