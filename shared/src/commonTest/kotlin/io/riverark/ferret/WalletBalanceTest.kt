package io.riverark.ferret

import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.network.lovelaceBalance
import io.riverark.ferret.feature.wallet.formatAda
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    private fun balance(response: String) = Json.parseToJsonElement(response).jsonArray.lovelaceBalance()
}
