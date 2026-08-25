package io.riverark.ferret

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.DerivedWallet
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.SignedTransaction
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.TransactionSummary
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.L1OperationDto
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import io.riverark.ferret.feature.wallet.DefaultL1WalletRepository
import io.riverark.ferret.feature.wallet.L1OperationState
import io.riverark.ferret.feature.wallet.parseAdaAmount
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class L1WalletRepositoryTest {
    @Test fun journalsBeforeSubmissionAndReconcilesWithoutResubmitting() = runBlocking {
        val source = profile('0')
        val destination = profile('1')
        val wallets = WalletRepository().apply { publish(source.id, listOf(source, destination)) }
        val vault = FakeVault(listOf(source, destination))
        val engine = FakeEngine()
        var submissions = 0
        val repository = DefaultL1WalletRepository(
            wallets,
            vault,
            { LedgerSnapshot(CardanoNetwork.PREPROD, listOf(LedgerUtxo("00".repeat(32), 0, source.paymentAddress, Lovelace(10_000_000))), "{}", 100) },
            { emptyList<TransactionRecord>() },
            { _, request ->
                submissions++
                val persisted = vault.walletState(source.id).operationJournal.decodeToString()
                assertFalse(persisted.contains("010203"))
                L1OperationDto(request.operationId, request.expectedTransactionId, request.expectedTransactionId, "pending")
            },
            { _, _ -> error("lookup not used") },
            engine,
            { OPERATION_ID },
            { 123L },
        )

        val preview = repository.previewTransfer(source.id, destination, Lovelace(5_000_000))
        assertEquals(Lovelace(200_000), preview.feeBound)
        assertEquals(Lovelace(4_800_000), preview.change)
        assertEquals(OPERATION_ID, repository.submitTransfer(source.id, preview))
        assertEquals(1, submissions)
        assertEquals(L1OperationState.PENDING, repository.operation(source.id)?.state)

        val restarted = DefaultL1WalletRepository(
            wallets,
            vault,
            { error("ledger must not reload during reconciliation") },
            { emptyList<TransactionRecord>() },
            { _, _ -> error("a mutation must not be retried") },
            { _, operationId -> L1OperationDto(operationId, TRANSACTION_ID, TRANSACTION_ID, "confirmed", 5) },
            engine,
            { error("operation id must remain stable") },
            { 999L },
        )
        assertEquals(L1OperationState.CONFIRMED, restarted.reconcilePending(source.id)?.state)
        assertEquals(1, submissions)
    }

    @Test fun parsesOnlyPositiveAdaWithAtMostSixDecimals() {
        assertEquals(Lovelace(1_230_000), parseAdaAmount("1.23"))
        assertEquals(Lovelace(1), parseAdaAmount("0.000001"))
        assertNull(parseAdaAmount("0"))
        assertNull(parseAdaAmount("1.0000001"))
        assertNull(parseAdaAmount("-1"))
    }

    private class FakeEngine : CardanoTransactionEngine {
        private lateinit var intent: CardanoIntent.Transfer
        override suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet = error("not used")
        override suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction {
            this.intent = intent as CardanoIntent.Transfer
            return UnsignedTransaction(byteArrayOf(0), intent.operationId, Lovelace(200_000))
        }
        override fun sign(unsigned: UnsignedTransaction, seed: ByteArray) = SignedTransaction(byteArrayOf(1, 2, 3))
        override fun inspect(signedCbor: ByteArray) = TransactionSummary(
            CardanoNetwork.PREPROD,
            listOf(
                TransactionOutputSummary(intent.destinationAddress, intent.amount, emptyMap()),
                TransactionOutputSummary(intent.sourceAddress, Lovelace(4_800_000), emptyMap()),
            ),
            Lovelace(200_000),
            emptySet(),
            intent.validFrom,
            intent.validUntil,
        )
        override fun transactionId(signedCbor: ByteArray) = TRANSACTION_ID
    }

    private class FakeVault(private val storedProfiles: List<WalletProfile>) : SecureVault {
        private val states = storedProfiles.associate { it.id to WalletEncryptedStateV1() }.toMutableMap()
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = storedProfiles
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T =
            action(ByteArray(32))
        override suspend fun walletState(walletId: WalletId): WalletEncryptedStateV1 = states.getValue(walletId).copy(
            channelRecovery = states.getValue(walletId).channelRecovery.copyOf(),
            operationJournal = states.getValue(walletId).operationJournal.copyOf(),
        )
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            states[walletId] = state.copy(
                channelRecovery = state.channelRecovery.copyOf(),
                operationJournal = state.operationJournal.copyOf(),
            )
        }
    }

    private fun profile(digit: Char) = WalletProfile(
        WalletId("preprod-" + digit.toString().repeat(56)),
        "Wallet $digit",
        CardanoNetwork.PREPROD,
        "addr_test1_$digit",
        "stake_test1_$digit",
    )

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000001"
        val TRANSACTION_ID = "11".repeat(32)
    }
}
