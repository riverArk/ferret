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
import io.riverark.ferret.feature.wallet.settlementWait
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

    @Test fun returnedChannelsDoNotContributeLockedFundsAndKeepTheirHistoryGroup() {
        val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, "00".repeat(32))
        val open = channel("01", ada, 3, ChannelState.Open("open"))
        val closing = channel("02", ada, 4, ChannelState.Closed)
        val returned = channel("03", ada, 99, ChannelState.FundsReturned("return"))
        val failed = channel("04", ada, 0)

        assertEquals(AssetAmount(ada, 7), channelBalance(ada, listOf(returned, closing, open)))
        assertEquals(listOf(open, closing, returned, failed), channelDisplayOrder(listOf(failed, returned, closing, open)))
    }

    @Test fun remainingWaitRoundsUpWithoutUsingTimeAsReturnAuthorization() {
        assertEquals("1 minute", settlementWait(60_001, 60_000))
        assertEquals("2 minutes", settlementWait(120_001, 60_000))
        assertEquals("1 hour", settlementWait(3_600_000, 0))
        assertEquals("2 hours", settlementWait(3_600_001, 0))
        assertEquals("1 day", settlementWait(86_400_000, 0))
        assertEquals("2 days", settlementWait(86_400_001, 0))
        assertEquals("Waiting for a refreshed status", settlementWait(60_000, 60_000))
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
