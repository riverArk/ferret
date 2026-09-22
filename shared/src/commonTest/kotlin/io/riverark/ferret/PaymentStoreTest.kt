package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelJournal
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.PaymentJournalV2
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.channel.PendingPayment
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.StoredReceipt
import io.riverark.ferret.core.channel.VaultPaymentStore
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.WalletId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentStoreTest {
    @Test fun projectsPendingPaidAndHistoryAcrossExplicitChannelsWithoutWriting() = runBlocking {
        val first = ProtocolKeytag("01".repeat(33))
        val second = ProtocolKeytag("02".repeat(33))
        val quote = PaymentQuote(
            id = "quote",
            keytag = second,
            amount = AssetAmount(ADA, 10),
            invoiceAmountMsat = 20_000,
            routingFee = AssetAmount(ADA, 2),
            adaptorFee = AssetAmount(ADA, 3),
            expiresAtEpochMillis = 1_000,
            invoiceHash = HASH,
            bindingVersion = 2,
        )
        val receipt = Receipt("settled", PAID_HASH, first, AssetAmount(ADA, 20), AssetAmount(ADA, 1), true)
        val collection = ChannelCollectionV4(
            walletId = WALLET,
            catalogDigest = DIGEST,
            channels = mapOf(
                first.value to ChannelSnapshot(
                    first, ADA, ChannelState.Open("first"), payments = PaymentJournalV2(
                        receipts = listOf(StoredReceipt(receipt, 20)),
                    ),
                ),
                second.value to ChannelSnapshot(
                    second, ADA, ChannelState.Open("second"), payments = PaymentJournalV2(
                        pending = PendingPayment("pending", HASH, quote, 10),
                    ),
                ),
            ),
            paidHashes = setOf(PAID_HASH),
        )
        val journal = FakeJournal(collection)
        val store = VaultPaymentStore(journal)

        assertTrue(store.isPaid(WALLET, PAID_HASH))
        assertFalse(store.isPaid(WALLET, HASH))
        assertTrue(store.isPending(WALLET, HASH))
        assertEquals("pending", store.pending(WALLET, second)?.operationId)
        assertEquals(null, store.pending(WALLET, first))
        assertEquals(receipt, store.receipt(WALLET, first, "settled"))
        assertEquals(listOf(TransactionState.SETTLED, TransactionState.PENDING), store.history(WALLET).map { it.state })
        assertEquals(0, journal.writes)
    }

    private class FakeJournal(private var collection: ChannelCollectionV4) : ChannelJournal {
        var writes = 0
        override suspend fun load(walletId: WalletId) = collection
        override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) {
            writes++
            this.collection = collection
        }
    }

    private companion object {
        val DIGEST = "00" + "11".repeat(31)
        val WALLET = WalletId("preprod-" + "00".repeat(28))
        val ADA = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, DIGEST)
        val HASH = "11".repeat(32)
        val PAID_HASH = "22".repeat(32)
    }
}
