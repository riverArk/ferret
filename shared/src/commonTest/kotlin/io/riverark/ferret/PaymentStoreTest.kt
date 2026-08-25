package io.riverark.ferret

import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.channel.PendingPaymentV1
import io.riverark.ferret.core.channel.VaultPaymentStore
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletOperationJournalV1
import io.riverark.ferret.core.security.WalletSecretV1
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentStoreTest {
    @Test fun pendingAndPaidHashesSurviveStoreRecreationWithoutOverwritingOtherJournals() = runBlocking {
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val json = Json
        val vault = FakeVault(walletId, json.encodeToString(WalletOperationJournalV1(l1 = byteArrayOf(1), channel = byteArrayOf(2))).encodeToByteArray())
        val quote = PaymentQuote("quote", Lovelace(10), 20_000, Lovelace(2), Lovelace(3), 1_000, HASH)
        VaultPaymentStore(vault).recordPending(walletId, PendingPaymentV1("operation", HASH, quote, 10))
        assertEquals(Realm.L2, VaultPaymentStore(vault).history(walletId).single().realm)
        assertEquals(TransactionState.PENDING, VaultPaymentStore(vault).history(walletId).single().state)
        assertEquals("operation", VaultPaymentStore(vault).pending(walletId)?.operationId)

        val receipt = Receipt("operation", HASH, Lovelace(10), Lovelace(5), true)
        VaultPaymentStore(vault).complete(walletId, receipt, 20)
        val restarted = VaultPaymentStore(vault)
        assertTrue(restarted.isPaid(walletId, HASH))
        assertEquals(TransactionState.SETTLED, restarted.history(walletId).single().state)
        assertEquals(receipt, restarted.receipt(walletId, "operation"))
        val envelope = json.decodeFromString<WalletOperationJournalV1>(vault.walletState(walletId).operationJournal.decodeToString())
        assertContentEquals(byteArrayOf(1), envelope.l1)
        assertContentEquals(byteArrayOf(2), envelope.channel)
    }

    private class FakeVault(walletId: WalletId, journal: ByteArray) : SecureVault {
        private val profile = WalletProfile(walletId, "Wallet", CardanoNetwork.PREPROD, "addr_test1", "stake_test1")
        private var state = WalletEncryptedStateV1(operationJournal = journal)
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = listOf(profile)
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T = error("not used")
        override suspend fun walletState(walletId: WalletId) = state.copy(
            channelRecovery = state.channelRecovery.copyOf(),
            operationJournal = state.operationJournal.copyOf(),
        )
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            this.state = state.copy(
                channelRecovery = state.channelRecovery.copyOf(),
                operationJournal = state.operationJournal.copyOf(),
            )
        }
    }

    private companion object {
        val HASH = "11".repeat(32)
    }
}
