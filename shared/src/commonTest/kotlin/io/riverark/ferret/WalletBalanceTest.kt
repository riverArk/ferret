package io.riverark.ferret

import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelPayload
import io.riverark.ferret.core.channel.PreparedChannelOperation
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.network.lovelaceBalance
import io.riverark.ferret.feature.wallet.channelRouteAvailable
import io.riverark.ferret.feature.wallet.channelStateLabel
import io.riverark.ferret.feature.wallet.formatAda
import io.riverark.ferret.feature.wallet.HomeViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WalletBalanceTest {
    @Test fun emptyUtxosHaveZeroBalance() {
        assertEquals(Lovelace(0), balance("[]"))
    }

    @Test fun lovelaceIsSummedAndNativeAssetsAreIgnored() {
        val response = """[
            {"value":[
                {"unit":"lovelace","quantity":"1000000"},
                {"unit":"asset1token","quantity":"99"}
            ]},
            {"value":[{"unit":"lovelace","quantity":"230000"}]}
        ]"""

        assertEquals(Lovelace(1_230_000), balance(response))
    }

    @Test fun invalidLovelaceQuantitiesFail() {
        listOf(
            """[{}]""",
            """[{"value":[{"unit":"lovelace"}]}]""",
            """[{"value":[{"unit":"lovelace","quantity":"nope"}]}]""",
            """[{"value":[{"unit":"lovelace","quantity":"-1"}]}]""",
            """[{"value":[{"unit":"lovelace","quantity":"9223372036854775808"}]}]""",
            """[{"value":[
                {"unit":"lovelace","quantity":"9223372036854775807"},
                {"unit":"lovelace","quantity":"1"}
            ]}]""",
        ).forEach { response -> assertFails { balance(response) } }
    }

    @Test fun adaFormattingUsesSixDecimalPlacesWithoutTrailingZeros() {
        assertEquals("₳ 0", formatAda(Lovelace(0)))
        assertEquals("₳ 1", formatAda(Lovelace(1_000_000)))
        assertEquals("₳ 1.23", formatAda(Lovelace(1_230_000)))
        assertEquals("₳ 0.000001", formatAda(Lovelace(1)))
        assertEquals("₳ 1.000001", formatAda(Lovelace(1_000_001)))
    }

    @Test fun activeChannelLifecycleIsReachableAndDescribed() {
        val active = listOf(
            ChannelState.Opening("opening") to "Opening",
            ChannelState.Open("channel") to "Open",
            ChannelState.Closing("closing") to "Closing",
            ChannelState.Responded to "Responded",
            ChannelState.Ending to "Ending",
        )
        active.forEach { (state, label) ->
            assertEquals(true, channelRouteAvailable(state))
            assertEquals(label, channelStateLabel(state))
        }
        assertEquals(false, channelRouteAvailable(ChannelState.Absent))
        assertEquals(false, channelRouteAvailable(ChannelState.Closed))
    }

    @Test fun homeRefreshUpdatesBalanceLatestActivityAndTimestamp() = runBlocking {
        val profile = WalletProfile(
            WalletId("preprod-${"a".repeat(56)}"),
            "Primary",
            CardanoNetwork.PREPROD,
            "addr_test1primary",
            "stake_test1primary",
        )
        var balance = Lovelace(1_000_000)
        var history = listOf(
            record("1".repeat(64), 100),
            record("2".repeat(64), 200),
        )
        var now = 300L
        val viewModel = HomeViewModel(profile, { balance }, { history }, nowEpochMillis = { now })

        viewModel.refreshNow()
        assertEquals(balance, viewModel.state.value.balance)
        assertEquals(history[1], viewModel.state.value.latestActivity)
        assertEquals(now, viewModel.state.value.lastRefreshEpochMillis)

        balance = Lovelace(2_000_000)
        history = listOf(record("3".repeat(64), 400))
        now = 500
        viewModel.refreshNow()
        assertEquals(balance, viewModel.state.value.balance)
        assertEquals(history.single(), viewModel.state.value.latestActivity)
        assertEquals(now, viewModel.state.value.lastRefreshEpochMillis)
    }

    @Test fun homeAutoRefreshRepeatsOnlyWhilePending() = runBlocking {
        val profile = WalletProfile(
            WalletId("preprod-${"a".repeat(56)}"),
            "Primary",
            CardanoNetwork.PREPROD,
            "addr_test1primary",
            "stake_test1primary",
        )
        val histories = listOf(
            listOf(record("1".repeat(64), 100, TransactionState.PENDING)),
            listOf(record("1".repeat(64), 100, TransactionState.CONFIRMED)),
        )
        var loads = 0
        val delays = mutableListOf<Long>()
        val viewModel = HomeViewModel(
            profile,
            { Lovelace(1_000_000) },
            { histories[minOf(loads++, histories.lastIndex)] },
        )

        viewModel.refreshWhilePending { delays += it }

        assertEquals(2, loads)
        assertEquals(listOf(20_000L), delays)
        assertEquals(TransactionState.CONFIRMED, viewModel.state.value.latestActivity?.state)
    }

    @Test fun homeProjectsJournalChannelStateAndRefreshesWhileChannelOperationIsPending() = runBlocking {
        val profile = WalletProfile(
            WalletId("preprod-${"a".repeat(56)}"),
            "Primary",
            CardanoNetwork.PREPROD,
            "addr_test1primary",
            "stake_test1primary",
        )
        val snapshots = listOf(
            ChannelSnapshot(
                ChannelState.Opening("opening"),
                PreparedChannelOperation(
                    "operation",
                    "intent",
                    ChannelAction.Open(3_000_000),
                    preparedAtEpochMillis = 1,
                    payload = ChannelPayload.Protocol(byteArrayOf(1)),
                    state = OperationState.PENDING_RECONCILIATION,
                ),
            ),
            ChannelSnapshot(ChannelState.Open("channel")),
        )
        var loads = 0
        val delays = mutableListOf<Long>()
        val viewModel = HomeViewModel(
            profile,
            { Lovelace(1_000_000) },
            { emptyList() },
            { snapshots[minOf(loads++, snapshots.lastIndex)] },
        )

        viewModel.refreshWhilePending { delays += it }

        assertEquals(2, loads)
        assertEquals(listOf(20_000L), delays)
        assertEquals(ChannelState.Open("channel"), viewModel.state.value.profile.channelState)
    }

    private fun record(
        id: String,
        timestamp: Long,
        state: TransactionState = TransactionState.CONFIRMED,
    ) = TransactionRecord(
        id,
        timestamp,
        Lovelace(1),
        Lovelace(0),
        Realm.L1,
        state,
    )

    private fun balance(response: String) = Json.parseToJsonElement(response).jsonArray.lovelaceBalance()
}
