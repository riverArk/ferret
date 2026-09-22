package io.riverark.ferret

import io.riverark.ferret.core.channel.*
import io.riverark.ferret.core.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChannelRepositoryTest {
    private val walletId = WalletId("mainnet-${"0".repeat(56)}")
    private val digest = "a".repeat(64)
    private val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
    private val usdm = ChannelAsset("usdm", "1".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
    private val usdcx = ChannelAsset("usdcx", "2".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
    private val first = ProtocolKeytag("1".repeat(64) + "a".repeat(64))
    private val second = ProtocolKeytag("1".repeat(64) + "b".repeat(64))
    private val nativeOne = ProtocolKeytag("1".repeat(64) + "c".repeat(64))
    private val nativeTwo = ProtocolKeytag("1".repeat(64) + "d".repeat(64))
    private val writer = WriterLease("e".repeat(64), 1, "f".repeat(64), "0".repeat(64), Long.MAX_VALUE)

    @Test fun paymentCompletionChangesOnlySelectedChannelAndWalletPaidSet() = runBlocking {
        var stored = collection()
        val repository = repository({ stored }, { stored = it }) { operation ->
            ChannelRemoteResult(
                operation.operationId, operation.intentHash, operation.keytag, operation.asset,
                state = ChannelState.Open(requireNotNull(operation.priorChannelIdentity)),
                status = OperationState.COMPLETED,
            )
        }
        repository.load(walletId)
        val before = stored
        val quote = quote(second)

        repository.submitPayment(walletId, second, "ln-invoice", quote, gateway(second), 1)

        assertEquals(before.channels.getValue(first.value), stored.channels.getValue(first.value))
        assertEquals(before.channels.getValue(nativeOne.value), stored.channels.getValue(nativeOne.value))
        assertEquals(before.channels.getValue(nativeTwo.value), stored.channels.getValue(nativeTwo.value))
        assertEquals(8_750L, stored.channels.getValue(second.value).spendableBalance.baseUnits)
        assertEquals(setOf(quote.invoiceHash), stored.paidHashes)
        assertEquals(second, stored.channels.getValue(second.value).payments.receipts.single().receipt.keytag)
        assertNull(stored.channels.getValue(second.value).pending)
    }

    @Test fun sequentialNativePaymentsDebitOnlyTheirSelectedChannelsAndProjectHistory() = runBlocking {
        var stored = collection()
        val before = stored
        val repository = repository({ stored }, { stored = it }) { operation ->
            ChannelRemoteResult(
                operation.operationId, operation.intentHash, operation.keytag, operation.asset,
                state = ChannelState.Open(requireNotNull(operation.priorChannelIdentity)),
                status = OperationState.COMPLETED,
            )
        }
        repository.load(walletId)
        val usdmQuote = quote(nativeOne, "a".repeat(64))
        val usdcxQuote = quote(nativeTwo, "b".repeat(64))

        repository.submitPayment(walletId, nativeOne, "ln-usdm", usdmQuote, gateway(nativeOne), 1)
        assertEquals(before.channels.getValue(first.value), stored.channels.getValue(first.value))
        assertEquals(before.channels.getValue(second.value), stored.channels.getValue(second.value))
        assertEquals(before.channels.getValue(nativeTwo.value), stored.channels.getValue(nativeTwo.value))
        repository.submitPayment(walletId, nativeTwo, "ln-usdcx", usdcxQuote, gateway(nativeTwo), 2)

        assertEquals(28_750L, stored.channels.getValue(nativeOne.value).spendableBalance.baseUnits)
        assertEquals(38_750L, stored.channels.getValue(nativeTwo.value).spendableBalance.baseUnits)
        assertEquals(before.channels.getValue(first.value), stored.channels.getValue(first.value))
        assertEquals(before.channels.getValue(second.value), stored.channels.getValue(second.value))
        assertEquals(setOf(usdmQuote.invoiceHash, usdcxQuote.invoiceHash), stored.paidHashes)
        val history = VaultPaymentStore(object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = stored
            override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) = error("persist not expected")
        }).history(walletId).associateBy { it.channelKeytag }
        assertEquals(usdmQuote.amount, history.getValue(nativeOne).amounts.single())
        assertEquals(usdmQuote.routingFee + usdmQuote.adaptorFee, history.getValue(nativeOne).fee)
        assertEquals(usdcxQuote.amount, history.getValue(nativeTwo).amounts.single())
        assertEquals(usdcxQuote.routingFee + usdcxQuote.adaptorFee, history.getValue(nativeTwo).fee)
    }

    @Test fun duplicateInvoiceAcrossChannelsRejectsBeforePreparationOrRemoteCall() = runBlocking {
        val hash = "9".repeat(64)
        val pendingQuote = quote(first, hash)
        val pending = PendingPayment("old", hash, pendingQuote, 0)
        var stored = collection().let { value ->
            value.copy(channels = value.channels + (first.value to value.channels.getValue(first.value).copy(
                payments = PaymentJournalV2(pending = pending),
            )))
        }
        var prepared = 0
        var remoteCalls = 0
        val repository = repository({ stored }, { stored = it }) { operation ->
            remoteCalls++
            ChannelRemoteResult(operation.operationId, operation.intentHash, operation.keytag, operation.asset,
                state = ChannelState.Open("second"), status = OperationState.COMPLETED)
        }
        repository.load(walletId)

        assertFailsWith<IllegalArgumentException> {
            repository.submitPayment(walletId, nativeOne, "ln-invoice", quote(nativeOne, hash), gateway(nativeOne) { prepared++ }, 1)
        }
        assertEquals(0, prepared)
        assertEquals(0, remoteCalls)
    }

    @Test fun adaQuoteAddressedToNativeChannelRejectsBeforePreparationOrRemoteCall() = runBlocking {
        var stored = collection()
        var prepared = 0
        var remoteCalls = 0
        val repository = repository({ stored }, { stored = it }) { operation ->
            remoteCalls++
            ChannelRemoteResult(
                operation.operationId, operation.intentHash, operation.keytag, operation.asset,
                state = ChannelState.Open("usdm"), status = OperationState.COMPLETED,
            )
        }
        repository.load(walletId)
        val before = stored
        val mismatched = quote(first).copy(keytag = nativeOne)

        assertFailsWith<IllegalArgumentException> {
            repository.submitPayment(walletId, nativeOne, "ln-invoice", mismatched, gateway(nativeOne) { prepared++ }, 1)
        }
        assertEquals(0, prepared)
        assertEquals(0, remoteCalls)
        assertEquals(before, stored)
    }

    @Test fun mismatchedRemoteIdentityCannotSettleOrDebit() = runBlocking {
        var stored = collection()
        val repository = repository({ stored }, { stored = it }) { operation ->
            ChannelRemoteResult(operation.operationId, operation.intentHash, first, operation.asset,
                state = ChannelState.Open("wrong"), status = OperationState.COMPLETED)
        }
        repository.load(walletId)

        assertFailsWith<IllegalArgumentException> {
            repository.submitPayment(walletId, second, "ln-invoice", quote(second), gateway(second), 1)
        }
        assertEquals(10_000L, stored.channels.getValue(second.value).spendableBalance.baseUnits)
        assertEquals(emptySet(), stored.paidHashes)
    }

    @Test fun guardedCleanupBacksUpBeforeReplacingLocalCollection() = runBlocking {
        val inactive = ChannelSnapshot(nativeOne, ada, ChannelState.Absent)
        var stored = collection().copy(
            channels = collection().channels + (inactive.keytag.value to inactive),
            unresolvedLegacy = byteArrayOf(1),
        )
        val cleaned = stored.copy(
            channels = stored.channels - inactive.keytag.value,
            unresolvedLegacy = byteArrayOf(),
        )
        val events = mutableListOf<String>()
        val repository = repository(
            { stored },
            { events += "persist"; stored = it },
            cleanup = { cleaned },
            writeAhead = { events += "backup" },
        ) { error("remote mutation not expected") }
        repository.load(walletId)

        assertEquals(cleaned, repository.cleanupInactive(walletId))
        assertEquals(listOf("backup", "persist"), events)
        assertEquals(cleaned, stored)
    }

    @Test fun cleanupBackupFailureLeavesLocalRecordsUntouched() = runBlocking {
        val original = collection().copy(unresolvedLegacy = byteArrayOf(1))
        var stored = original
        val cleaned = original.copy(unresolvedLegacy = byteArrayOf())
        val repository = repository(
            { stored },
            { stored = it },
            cleanup = { cleaned },
            writeAhead = { error("Drive unavailable") },
        ) { error("remote mutation not expected") }
        repository.load(walletId)

        val failure = assertFailsWith<InactiveChannelCleanupRejected> {
            repository.cleanupInactive(walletId)
        }

        assertEquals("The encrypted Drive backup could not be updated. Local records were not changed.", failure.message)
        assertEquals(original, stored)
    }

    private fun collection(): ChannelCollectionV4 {
        val entries = listOf(
            ChannelSnapshot(first, ada, ChannelState.Open("first"), spendableBalance = AssetAmount(ada, 20_000)),
            ChannelSnapshot(second, ada, ChannelState.Open("second"), spendableBalance = AssetAmount(ada, 10_000)),
            ChannelSnapshot(nativeOne, usdm, ChannelState.Open("usdm"), spendableBalance = AssetAmount(usdm, 30_000)),
            ChannelSnapshot(nativeTwo, usdcx, ChannelState.Open("usdcx"), spendableBalance = AssetAmount(usdcx, 40_000)),
        )
        return ChannelCollectionV4(walletId = walletId, catalogDigest = digest, channels = entries.associateBy { it.keytag.value })
    }

    private fun quote(keytag: ProtocolKeytag, hash: String = "8".repeat(64)): PaymentQuote {
        val asset = collection().channels.getValue(keytag.value).asset
        return PaymentQuote(
            "quote", keytag, AssetAmount(asset, 1_000), 1_000, AssetAmount(asset, 200), AssetAmount(asset, 50),
            10_000, hash, bindingVersion = 2,
        )
    }

    private fun gateway(keytag: ProtocolKeytag, prepared: () -> Unit = {}) = object : PaymentGateway {
        override suspend fun lightningChain() = "mainnet"
        override suspend fun quote(walletId: WalletId, keytag: ProtocolKeytag, invoice: String, invoiceHash: String, amountMsat: Long) =
            error("quote not expected")
        override suspend fun prepareInitialization(walletId: WalletId, keytag: ProtocolKeytag, operationId: String, preparedAtEpochMillis: Long) = null
        override suspend fun prepare(
            walletId: WalletId,
            keytag: ProtocolKeytag,
            operationId: String,
            intentHash: String,
            invoice: String,
            quote: PaymentQuote,
            preparedAtEpochMillis: Long,
        ): ChannelPreview {
            prepared()
            val entry = collection().channels.getValue(keytag.value)
            val total = quote.amount + quote.routingFee + quote.adaptorFee
            val request = AdaptorPayRequest(
                ChequeBodyWire(0, total.baseUnits, ProtocolDurationWire.fromMillis(1), Hex32(quote.invoiceHash)),
                "0".repeat(128),
                invoice,
            )
            val operation = PreparedChannelOperation(
                operationId, intentHash, keytag, entry.asset, ChannelAction.Pay(quote.id, quote.invoiceHash),
                (entry.state as ChannelState.Open).channelId, preparedAtEpochMillis,
                ChannelPayload.Payment(byteArrayOf(1), invoice, quote.invoiceHash, quote.id, request, quote),
                entry.spendableBalance - total,
            )
            val fee = quote.routingFee + quote.adaptorFee
            val zero = AssetAmount(entry.asset, 0)
            return ChannelPreview(operation, quote.amount, fee, fee, null, zero, zero, operation.resultingSpendableBalance!!, CardanoNetwork.MAINNET, zero)
        }
    }

    private fun repository(
        load: () -> ChannelCollectionV4,
        save: (ChannelCollectionV4) -> Unit,
        cleanup: (ChannelCollectionV4) -> ChannelCollectionV4 = { error("cleanup not expected") },
        writeAhead: (ChannelCollectionV4) -> Unit = {},
        mutate: (PreparedChannelOperation) -> ChannelRemoteResult,
    ): ChannelRepository {
        var nextOperationId = 0
        return ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = load()
                override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) = save(collection)
                override fun cleanupInactive(collection: ChannelCollectionV4) = cleanup(collection)
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
                override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) = writeAhead(collection)
                override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) = Unit
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) = mutate(operation)
                override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) = null
            },
            newOperationId = {
                "00000000-0000-4000-8000-${(++nextOperationId).toString().padStart(12, '0')}"
            },
        )
    }
}
