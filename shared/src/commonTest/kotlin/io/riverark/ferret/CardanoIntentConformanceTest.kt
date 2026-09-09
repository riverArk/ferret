package io.riverark.ferret

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.ProhibitedBodyField
import io.riverark.ferret.core.cardano.TransactionDatum
import io.riverark.ferret.core.cardano.TransactionKeyWitness
import io.riverark.ferret.core.cardano.TransactionRedeemer
import io.riverark.ferret.core.cardano.TransactionScriptReference
import io.riverark.ferret.core.cardano.TransactionInputReference
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.TransactionSummary
import io.riverark.ferret.core.cardano.requireMatches
import io.riverark.ferret.core.cardano.requireL1Funding
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardanoIntentConformanceTest {
    private val source = "addr_test1source"
    private val intents = listOf(
        CardanoIntent.Transfer(source, "addr_test1destination", Lovelace(1_000_000), OPERATION_ID, 10, 20),
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

    @Test fun onlyOneDesignatedOutputAndOneSourceChangeAreAllowed() {
        intents.forEach { intent ->
            val original = summary(intent)
            val change = TransactionOutputSummary(source, Lovelace(123), emptyMap())
            original.copy(outputs = original.outputs + change).requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
            assertFailsWith<IllegalArgumentException> {
                original.copy(outputs = original.outputs + change + change).requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
            assertFailsWith<IllegalArgumentException> {
                original.copy(outputs = original.outputs + original.outputs.single().copy(lovelace = Lovelace(123)))
                    .requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
        }
    }

    @Test fun l1OutputsAreAdaOnlyAndCannotPaySelf() {
        intents.filter { it is CardanoIntent.Transfer || it is CardanoIntent.SweepWallet }.forEach { intent ->
            assertFailsWith<IllegalArgumentException> {
                summary(intent).copy(outputs = summary(intent).outputs + TransactionOutputSummary(source, Lovelace(123), mapOf("aa".repeat(28) to 1)))
                    .requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
            val self = when (intent) {
                is CardanoIntent.Transfer -> intent.copy(destinationAddress = source)
                is CardanoIntent.SweepWallet -> intent.copy(destinationAddress = source)
                else -> error("not L1")
            }
            assertFailsWith<IllegalArgumentException> {
                summary(self).requireMatches(self, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
        }
    }
    @Test fun l1RejectsScriptAndBodyContamination() {
        intents.filter { it is CardanoIntent.Transfer || it is CardanoIntent.SweepWallet }.forEach { intent ->
            val valid = summary(intent)
            listOf(
                valid.copy(outputs = listOf(valid.outputs.single().copy(datum = TransactionDatum.Hash("aa".repeat(32))))),
                valid.copy(outputs = listOf(valid.outputs.single().copy(datum = TransactionDatum.Inline("d87980")))),
                valid.copy(outputs = listOf(valid.outputs.single().copy(
                    scriptReference = TransactionScriptReference(1, "00", "bb".repeat(28)),
                ))),
                valid.copy(requiredSigners = setOf("cc".repeat(28))),
                valid.copy(referenceInputs = listOf(TransactionInputReference("33".repeat(32), 0))),
                valid.copy(collateralInputs = listOf(TransactionInputReference("44".repeat(32), 0))),
                valid.copy(collateralReturn = valid.outputs.single()),
                valid.copy(totalCollateral = Lovelace(1)),
                valid.copy(redeemers = listOf(TransactionRedeemer("spend", 0, "00", 1, 1))),
                valid.copy(scriptDataHashHex = "dd".repeat(32)),
                valid.copy(prohibitedBodyFields = setOf(ProhibitedBodyField.MINT)),
                valid.copy(containsNonKeyWitnesses = true),
            ).forEach { contaminated ->
                assertFailsWith<IllegalArgumentException> {
                    contaminated.requireMatches(intent, CardanoNetwork.PREPROD, Lovelace(200_000))
                }
            }
            val zero = when (intent) {
                is CardanoIntent.Transfer -> intent.copy(amount = Lovelace(0))
                is CardanoIntent.SweepWallet -> intent.copy(amount = Lovelace(0))
                else -> error("not L1")
            }
            assertFailsWith<IllegalArgumentException> {
                summary(zero).requireMatches(zero, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
            val emptyValidity = when (intent) {
                is CardanoIntent.Transfer -> intent.copy(validFrom = intent.validUntil)
                is CardanoIntent.SweepWallet -> intent.copy(validFrom = intent.validUntil)
            }
            assertFailsWith<IllegalArgumentException> {
                summary(emptyValidity).requireMatches(emptyValidity, CardanoNetwork.PREPROD, Lovelace(200_000))
            }
        }
    }

    @Test fun l1WitnessesRequireTheSingleExpectedValidSpendingKey() {
        val credential = "ab".repeat(28)
        val valid = TransactionKeyWitness("01".repeat(32), credential, "02".repeat(64), true)
        val summary = summary(intents.first())
        summary.requireL1Witnesses(credential, signed = false)
        summary.copy(keyWitnesses = listOf(valid)).requireL1Witnesses(credential, signed = true)

        assertFailsWith<IllegalArgumentException> {
            summary.copy(keyWitnesses = listOf(valid)).requireL1Witnesses(credential, signed = false)
        }
        listOf(
            emptyList(),
            listOf(valid.copy(keyHashHex = "cd".repeat(28))),
            listOf(valid, valid),
            listOf(valid, valid.copy(keyHashHex = "cd".repeat(28))),
            listOf(valid.copy(signatureValid = false)),
        ).forEach { witnesses ->
            assertFailsWith<IllegalArgumentException> {
                summary.copy(keyWitnesses = witnesses).requireL1Witnesses(credential, signed = true)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            summary.requireL1Witnesses(credential.uppercase(), signed = false)
        }
    }


    @Test fun l1FundingUsesOnlySelectedInputsAndAllowsExactSpend() {
        val selected = LedgerUtxo("22".repeat(32), 0, source, Lovelace(10_000_000))
        val mixedLedger = LedgerSnapshot(
            CardanoNetwork.PREPROD,
            listOf(selected, LedgerUtxo("33".repeat(32), 0, source, Lovelace(90_000_000))),
            "",
            0,
        )
        intents.filter { it is CardanoIntent.Transfer || it is CardanoIntent.SweepWallet }.forEach { intent ->
            val original = summary(intent)
            original.copy(
                outputs = original.outputs + TransactionOutputSummary(source, Lovelace(8_900_000), emptyMap()),
            ).requireL1Funding(intent, mixedLedger)
            original.requireL1Funding(
                intent,
                LedgerSnapshot(CardanoNetwork.PREPROD, listOf(selected.copy(lovelace = Lovelace(1_100_000))), "", 0),
            )
        }
    }

    @Test fun l1FundingRejectsInvalidInputProvenance() {
        val intent = intents.filterIsInstance<CardanoIntent.Transfer>().single()
        val selected = LedgerUtxo("22".repeat(32), 0, source, Lovelace(1_100_000))
        val protected = listOf(
            LedgerUtxo("33".repeat(32), 0, source, Lovelace(9_000_000), datumHashHex = "aa".repeat(32)),
            LedgerUtxo("44".repeat(32), 0, source, Lovelace(9_000_000), datumHex = "d87980"),
            LedgerUtxo("55".repeat(32), 0, source, Lovelace(9_000_000), scriptRefHashHex = "bb".repeat(28)),
            LedgerUtxo("66".repeat(32), 0, source, Lovelace(9_000_000), mapOf("cc".repeat(28) to 0)),
        )
        val valid = summary(intent)
        valid.requireL1Funding(intent, LedgerSnapshot(CardanoNetwork.PREPROD, listOf(selected) + protected, "", 0))

        listOf(
            valid.copy(inputs = emptyList()),
            valid.copy(inputs = valid.inputs + valid.inputs),
            valid.copy(inputs = listOf(TransactionInputReference("77".repeat(32), 0))),
            valid.copy(network = CardanoNetwork.MAINNET),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                invalid.requireL1Funding(intent, LedgerSnapshot(CardanoNetwork.PREPROD, listOf(selected), "", 0))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            valid.requireL1Funding(intent, LedgerSnapshot(CardanoNetwork.PREPROD, listOf(selected, selected), "", 0))
        }
        listOf(
            selected.copy(address = "addr_test1foreign"),
            selected.copy(assets = mapOf("cc".repeat(28) to 0)),
            selected.copy(datumHashHex = "aa".repeat(32)),
            selected.copy(datumHex = "d87980"),
            selected.copy(scriptRefHashHex = "bb".repeat(28)),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                valid.requireL1Funding(intent, LedgerSnapshot(CardanoNetwork.PREPROD, listOf(invalid), "", 0))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            valid.requireL1Funding(intent.copy(amount = Lovelace(0)), LedgerSnapshot(CardanoNetwork.PREPROD, listOf(selected), "", 0))
        }
    }

    @Test fun l1FundingRequiresExactOverflowSafeAdaConservation() {
        val intent = intents.filterIsInstance<CardanoIntent.Transfer>().single()
        val valid = summary(intent)
        listOf(1_099_999L, 1_100_001L, 10_000_000L).forEach { selectedValue ->
            assertFailsWith<IllegalArgumentException> {
                valid.requireL1Funding(
                    intent,
                    LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("22".repeat(32), 0, source, Lovelace(selectedValue))), "", 0),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(
                outputs = valid.outputs + TransactionOutputSummary(source, Lovelace(8_900_001), emptyMap()),
            ).requireL1Funding(
                intent,
                LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("22".repeat(32), 0, source, Lovelace(10_000_000))), "", 0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(outputs = listOf(valid.outputs.single().copy(assets = mapOf("cc".repeat(28) to 0))))
                .requireL1Funding(intent, LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("22".repeat(32), 0, source, Lovelace(1_100_000))), "", 0))
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(inputs = valid.inputs + TransactionInputReference("23".repeat(32), 0)).requireL1Funding(
                intent,
                LedgerSnapshot(
                    CardanoNetwork.PREPROD,
                    listOf(
                        LedgerUtxo("22".repeat(32), 0, source, Lovelace(Long.MAX_VALUE)),
                        LedgerUtxo("23".repeat(32), 0, source, Lovelace(1)),
                    ),
                    "",
                    0,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(outputs = listOf(TransactionOutputSummary(intent.destinationAddress, Lovelace(Long.MAX_VALUE), emptyMap())), fee = Lovelace(1))
                .requireL1Funding(
                    intent,
                    LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("22".repeat(32), 0, source, Lovelace(Long.MAX_VALUE))), "", 0),
                )
        }
    }

    private fun summary(intent: CardanoIntent): TransactionSummary {
        val output = when (intent) {
            is CardanoIntent.Transfer -> TransactionOutputSummary(intent.destinationAddress, intent.amount, emptyMap())
            is CardanoIntent.SweepWallet -> TransactionOutputSummary(intent.destinationAddress, intent.amount, emptyMap())
            else -> error("not L1")
        }
        return TransactionSummary(CardanoNetwork.PREPROD, listOf(output), Lovelace(100_000), emptySet(), intent.validFrom, intent.validUntil, listOf(TransactionInputReference("22".repeat(32), 0)))
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000001"
    }
}
