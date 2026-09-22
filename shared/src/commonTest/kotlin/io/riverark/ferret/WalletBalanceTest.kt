package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelPayload
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.PreparedChannelOperation
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.feature.wallet.AssetBalance
import io.riverark.ferret.feature.wallet.HomeViewModel
import io.riverark.ferret.feature.wallet.WalletBalance
import io.riverark.ferret.feature.wallet.channelRouteAvailable
import io.riverark.ferret.feature.wallet.channelStateLabel
import io.riverark.ferret.feature.wallet.formatAsset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WalletBalanceTest {
    @Test fun exactAssetFormattingUsesCatalogIdentity() {
        assertEquals("₳ 0", formatAsset(AssetAmount(ADA, 0), CATALOG))
        assertEquals("₳ 1.000001", formatAsset(AssetAmount(ADA, 1_000_001), CATALOG))
        assertEquals("1.000001 USDM", formatAsset(AssetAmount(USDM, 1_000_001), CATALOG))
        assertEquals("2.5 USDCx", formatAsset(AssetAmount(USDCX, 2_500_000), CATALOG))
    }

    @Test fun channelCollectionAvailabilityAndLabelsIncludeEveryLifecycle() {
        val profile = profile()
        assertEquals(false, channelRouteAvailable(collection(profile)))
        entriesForTest().forEachIndexed { index, (state, label) ->
            val keytag = ProtocolKeytag(index.toString(16).padStart(2, '0').repeat(33))
            assertEquals(
                true,
                channelRouteAvailable(collection(profile, mapOf(keytag.value to ChannelSnapshot(keytag, ADA, state)))),
            )
            assertEquals(label, channelStateLabel(state))
        }
        assertEquals(true, channelRouteAvailable(collection(profile).copy(unresolvedLegacy = byteArrayOf(1))))
    }

    @Test fun homeRefreshPublishesBalanceCollectionActivityAndTimestamp() = runBlocking {
        val profile = profile()
        val keytag = ProtocolKeytag("aa".repeat(33))
        var balance = walletBalance(1_000_000)
        val channels = collection(
            profile,
            mapOf(keytag.value to ChannelSnapshot(keytag, ADA, ChannelState.Open("channel"), spendableBalance = AssetAmount(ADA, 3_000_000))),
        )
        var history = listOf(record("1".repeat(64), 100), record("2".repeat(64), 200))
        var now = 300L
        val viewModel = HomeViewModel(profile, { balance }, { history }, { channels }, { now })

        viewModel.refreshNow()
        assertEquals(balance, viewModel.state.value.balance)
        assertEquals(channels, viewModel.state.value.channels)
        assertEquals(history[1], viewModel.state.value.latestActivity)
        assertEquals(now, viewModel.state.value.lastRefreshEpochMillis)

        balance = walletBalance(2_000_000)
        history = listOf(record("3".repeat(64), 400))
        now = 500
        viewModel.refreshNow()
        assertEquals(balance, viewModel.state.value.balance)
        assertEquals(history.single(), viewModel.state.value.latestActivity)
    }

    @Test fun homeAutoRefreshRepeatsForPendingL1OrChannelWork() = runBlocking {
        val profile = profile()
        val keytag = ProtocolKeytag("bb".repeat(33))
        val pending = PreparedChannelOperation(
            "operation",
            "intent",
            keytag,
            ADA,
            ChannelAction.Open(AssetAmount(ADA, 3_000_000)),
            preparedAtEpochMillis = 1,
            payload = ChannelPayload.Protocol(byteArrayOf(1)),
            state = OperationState.PENDING_RECONCILIATION,
        )
        val collections = listOf(
            collection(profile, mapOf(keytag.value to ChannelSnapshot(keytag, ADA, ChannelState.Opening("opening"), pending))),
            collection(profile, mapOf(keytag.value to ChannelSnapshot(keytag, ADA, ChannelState.Open("channel")))),
        )
        var loads = 0
        val delays = mutableListOf<Long>()
        val viewModel = HomeViewModel(
            profile,
            { walletBalance(1_000_000) },
            { emptyList() },
            { collections[minOf(loads++, collections.lastIndex)] },
        )

        viewModel.refreshWhilePending { delays += it }

        assertEquals(2, loads)
        assertEquals(listOf(20_000L), delays)
        assertEquals(ChannelState.Open("channel"), viewModel.state.value.channels?.channels?.get(keytag.value)?.state)
    }

    private fun walletBalance(units: Long) = WalletBalance(
        listOf(AssetBalance(AssetAmount(ADA, units), AssetAmount(ADA, units), AssetAmount(ADA, 0))),
        emptyMap(),
    )

    private fun record(id: String, timestamp: Long) = TransactionRecord(
        id,
        timestamp,
        listOf(AssetAmount(ADA, 1)),
        AssetAmount(ADA, 0),
        Realm.L1,
        TransactionState.CONFIRMED,
    )

    private fun profile() = WalletProfile(
        WalletId("preprod-${"a".repeat(56)}"),
        "Primary",
        CardanoNetwork.PREPROD,
        "addr_test1primary",
        "stake_test1primary",
    )

    private fun collection(profile: WalletProfile, channels: Map<String, ChannelSnapshot> = emptyMap()) =
        ChannelCollectionV4(walletId = profile.id, catalogDigest = DIGEST, channels = channels)

    private fun entriesForTest() = listOf(
        ChannelState.Opening("opening") to "Opening",
        ChannelState.Open("channel") to "Open",
        ChannelState.Closing("closing") to "Closing",
        ChannelState.Closed to "Closed",
        ChannelState.Responded to "Responded",
        ChannelState.Ending to "Ending",
    )

    private companion object {
        const val DIGEST = "09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae"
        val ADA = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, DIGEST)
        val USDA = ChannelAsset("usda", "11".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST)
        val USDCX = ChannelAsset("usdcx", "22".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST)
        val USDM = ChannelAsset("usdm", "33".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST)
        val CATALOG = AssetCatalog(listOf(ADA, USDA, USDCX, USDM), DIGEST, mapOf(
            "usda" to io.riverark.ferret.core.model.AssetPresentation("USDA", "USDA", "", byteArrayOf()),
            "usdcx" to io.riverark.ferret.core.model.AssetPresentation("USDCx", "USDCx", "", byteArrayOf()),
            "usdm" to io.riverark.ferret.core.model.AssetPresentation("USDM", "USDM", "", byteArrayOf()),
        ))
    }
}
