package io.riverark.ferret

import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.feature.wallet.L1WalletRepository
import io.riverark.ferret.feature.wallet.TransferPreview
import io.riverark.ferret.feature.wallet.TransferViewModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransferViewModelTest {
    private val source = profile('0', CardanoNetwork.PREPROD)
    private val destination = profile('1', CardanoNetwork.PREPROD)
    private val mainnet = profile('2', CardanoNetwork.MAINNET)
    private val repository = RecordingL1Repository()
    private val viewModel = TransferViewModel(source.id, source.network, repository)

    @Test fun destinationsContainOnlyOtherWalletsOnTheSameNetwork() {
        assertEquals(listOf(destination), viewModel.destinations(listOf(source, destination, mainnet)))
    }

    @Test fun invalidDestinationFailsBeforeTransactionPreview() = runBlocking {
        assertFailsWith<IllegalArgumentException> { viewModel.preview(mainnet, Lovelace(1)) }
        assertFailsWith<IllegalArgumentException> { viewModel.preview(source, Lovelace(1)) }
        assertEquals(0, repository.previewCalls)
    }

    private fun profile(suffix: Char, network: CardanoNetwork) = WalletProfile(
        WalletId("${network.name.lowercase()}-${suffix.toString().repeat(56)}"),
        "Wallet $suffix",
        network,
        if (network == CardanoNetwork.PREPROD) "addr_test1$suffix" else "addr1$suffix",
        if (network == CardanoNetwork.PREPROD) "stake_test1$suffix" else "stake1$suffix",
    )

    private class RecordingL1Repository : L1WalletRepository {
        var previewCalls = 0

        override suspend fun balance(walletId: WalletId) = error("not used")
        override suspend fun history(walletId: WalletId): List<TransactionRecord> = error("not used")
        override suspend fun previewTransfer(walletId: WalletId, destination: WalletProfile, amount: Lovelace): TransferPreview {
            previewCalls++
            return TransferPreview(destination, amount, Lovelace(1), Lovelace(0))
        }
        override suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview) = error("not used")
    }
}
