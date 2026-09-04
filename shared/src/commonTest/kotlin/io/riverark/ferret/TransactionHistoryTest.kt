package io.riverark.ferret

import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.network.ConnectorTransactionDto
import io.riverark.ferret.core.network.transactionRecords
import io.riverark.ferret.core.network.validatedFor
import io.riverark.ferret.feature.wallet.mergeTransactionRecords
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TransactionHistoryTest {
    private val wallet = "addr_test1wallet"
    private val response = """[{
        "id":"${"a".repeat(64)}",
        "index":1,
        "depth":4,
        "timestamp":1771459333,
        "invalid_before":115776058,
        "inputs":[{
            "transaction_id":"${"b".repeat(64)}",
            "output_index":0,
            "address":"$wallet",
            "value":[{"unit":"lovelace","quantity":"10000000"}]
        }],
        "outputs":[
            {"address":"addr_test1recipient","value":[{"unit":"lovelace","quantity":"7000000"}]},
            {"address":"$wallet","value":[{"unit":"lovelace","quantity":"2800000"}]}
        ]
    }]"""

    @Test fun connectorTransactionsMapAmountsFeesAndFinalityBoundaries() {
        val transaction = Json.decodeFromString<List<ConnectorTransactionDto>>(response).single()
        val expectedStates = mapOf(
            4L to TransactionState.PENDING,
            5L to TransactionState.CONFIRMED,
            2_159L to TransactionState.CONFIRMED,
            2_160L to TransactionState.SETTLED,
        )

        expectedStates.forEach { (depth, expectedState) ->
            val record = listOf(transaction.copy(depth = depth)).transactionRecords(wallet).single()
            assertEquals(Lovelace(7_000_000), record.amount)
            assertEquals(Lovelace(200_000), record.fee)
            assertEquals(1_771_459_333_000, record.timestampEpochMillis)
            assertEquals(Realm.L1, record.realm)
            assertEquals(expectedState, record.state)
        }
    }

    @Test fun incomingTransactionDoesNotChargeSenderFeeToWallet() {
        val transaction = Json.decodeFromString<List<ConnectorTransactionDto>>(response)
            .single()
            .copy(
                inputs = emptyList(),
                outputs = listOf(
                    io.riverark.ferret.core.network.ConnectorOutputDto(
                        wallet,
                        listOf(io.riverark.ferret.core.network.ConnectorAssetDto("lovelace", "5000000")),
                    ),
                ),
            )

        val record = listOf(transaction).transactionRecords(wallet).single()

        assertEquals(Lovelace(5_000_000), record.amount)
        assertEquals(Lovelace(0), record.fee)
    }

    @Test fun mergingHistoryIsDescendingAndDoesNotMutateSources() {
        val older = record("older", 1)
        val newer = record("newer", 2)
        val l1 = mutableListOf(older)
        val l2 = mutableListOf(newer)

        assertEquals(listOf(newer, older), mergeTransactionRecords(l1, l2))
        assertEquals(listOf(older), l1)
        assertEquals(listOf(newer), l2)
    }

    @Test fun malformedConnectorValuesFailClosed() {
        val malformed = Json.decodeFromString<List<ConnectorTransactionDto>>(response)
            .single()
            .copy(id = "not-a-transaction-id")

        assertFails { listOf(malformed).transactionRecords(wallet) }
    }

    @Test fun transactionLookupRejectsMismatchedOrMalformedResponses() {
        val transaction = Json.decodeFromString<List<ConnectorTransactionDto>>(response).single()

        assertEquals(transaction, transaction.validatedFor(transaction.id))
        assertFails { transaction.validatedFor("b".repeat(64)) }
        assertFails { transaction.copy(inputs = transaction.inputs.map { it.copy(transactionId = "bad") }).validatedFor(transaction.id) }
    }

    private fun record(id: String, timestamp: Long) = TransactionRecord(
        id,
        timestamp,
        Lovelace(1),
        Lovelace(0),
        Realm.L1,
        TransactionState.CONFIRMED,
    )
}
