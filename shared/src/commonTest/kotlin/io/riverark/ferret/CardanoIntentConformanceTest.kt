package io.riverark.ferret

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.TransactionSummary
import io.riverark.ferret.core.cardano.requireMatches
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardanoIntentConformanceTest {
    private val source = "addr_test1source"
    private val channel = LedgerUtxo("00".repeat(32), 0, "addr_test1channel", Lovelace(4_000_000), mapOf("aa".repeat(28) to 1))
    private val reference = LedgerUtxo("11".repeat(32), 0, "addr_test1reference", Lovelace(2_000_000))
    private val intents = listOf(
        CardanoIntent.Transfer(source, "addr_test1destination", Lovelace(1_000_000), OPERATION_ID, 10, 20),
        CardanoIntent.OpenChannel(source, "addr_test1validator", "00", Lovelace(3_000_000), OPERATION_ID, 10, 20),
        CardanoIntent.AddChannelFunds(source, channel, reference, "00", "00", Lovelace(1_000_000), OPERATION_ID, 10, 20),
        CardanoIntent.CloseChannel(source, channel, reference, "00", Lovelace(3_500_000), OPERATION_ID, 10, 20),
        CardanoIntent.SweepWallet(source, "addr_test1sweep", Lovelace(1_000_000), OPERATION_ID, 10, 20),
    )

    @Test fun allIntentsAcceptOnlyTheirExactSemanticOutput() {
        intents.forEach { intent -> summary(intent).requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000)) }
    }

    @Test fun alteredValidityAndAdditionalDestinationsFail() {
        intents.forEach { intent ->
            assertFailsWith<IllegalArgumentException> {
                summary(intent).copy(validityStart = 9).requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
            assertFailsWith<IllegalArgumentException> {
                summary(intent).copy(outputs = summary(intent).outputs + TransactionOutputSummary("addr_test1attacker", Lovelace(1), emptyMap()))
                    .requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
        }
    }

    private fun summary(intent: CardanoIntent): TransactionSummary {
        val output = when (intent) {
            is CardanoIntent.Transfer -> TransactionOutputSummary(intent.destinationAddress, intent.amount, emptyMap())
            is CardanoIntent.OpenChannel -> TransactionOutputSummary(intent.deploymentAddress, intent.amount, emptyMap())
            is CardanoIntent.AddChannelFunds -> TransactionOutputSummary(intent.channelInput.address, intent.channelInput.lovelace + intent.amount, intent.channelInput.assets)
            is CardanoIntent.CloseChannel -> TransactionOutputSummary(intent.sourceAddress, intent.amount, intent.channelInput.assets)
            is CardanoIntent.SweepWallet -> TransactionOutputSummary(intent.destinationAddress, intent.amount, emptyMap())
        }
        return TransactionSummary(CardanoNetwork.PREPROD, listOf(output), Lovelace(100_000), emptySet(), intent.validFrom, intent.validUntil)
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000001"
    }
}
