package io.riverark.ferret.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.CreatedWallet
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.WalletProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WalletPickerUiState(val wallets: List<WalletProfile> = emptyList(), val busy: Boolean = false, val error: String? = null)

class WalletPickerViewModel(private val manager: WalletManager) : ViewModel() {
    private val mutableState = MutableStateFlow(WalletPickerUiState())
    val state: StateFlow<WalletPickerUiState> = mutableState.asStateFlow()

    fun load() = launch { manager.load() }
    fun create(name: String, network: CardanoNetwork, result: (CreatedWallet) -> Unit) = launch(result) { manager.create(name, network) }
    fun restore(name: String, network: CardanoNetwork, phrase: String, result: (WalletProfile) -> Unit) = launch(result) { manager.restore(name, network, phrase) }
    fun rename(walletId: WalletId, name: String) = launch { manager.rename(walletId, name) }

    private fun <T> launch(result: (T) -> Unit = {}, action: suspend () -> T) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val value = action()
                mutableState.value = WalletPickerUiState(manager.load())
                result(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = "Wallet operation failed.")
            }
        }
    }
}

data class WalletBalance(val spendable: Lovelace, val pending: Lovelace)
data class TransferPreview(val destination: WalletProfile?, val externalAddress: String?, val amount: Lovelace, val feeBound: Lovelace, val change: Lovelace)

interface L1WalletRepository {
    suspend fun balance(walletId: WalletId): WalletBalance
    suspend fun history(walletId: WalletId): List<TransactionRecord>
    suspend fun previewTransfer(walletId: WalletId, destination: WalletProfile?, externalAddress: String?, amount: Lovelace): TransferPreview
    suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview): String
}

data class HomeUiState(val profile: WalletProfile, val balance: WalletBalance? = null, val loading: Boolean = true)
class HomeViewModel(private val walletId: WalletId, profile: WalletProfile, private val l1: L1WalletRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(profile))
    val state = mutableState.asStateFlow()
    fun refresh() { viewModelScope.launch { mutableState.value = mutableState.value.copy(balance = l1.balance(walletId), loading = false) } }
}

class TopUpViewModel(val profile: WalletProfile) : ViewModel()

class TransferViewModel(private val walletId: WalletId, private val network: CardanoNetwork, private val l1: L1WalletRepository) : ViewModel() {
    suspend fun preview(destination: WalletProfile?, externalAddress: String?, amount: Lovelace): TransferPreview {
        require(destination == null || destination.network == network) { "cross-network transfer" }
        require((destination == null) xor (externalAddress == null))
        return l1.previewTransfer(walletId, destination, externalAddress, amount)
    }
    suspend fun submit(preview: TransferPreview) = l1.submitTransfer(walletId, preview)
}

class HistoryViewModel(private val walletId: WalletId, private val l1: L1WalletRepository) : ViewModel() {
    suspend fun records(): List<TransactionRecord> = l1.history(walletId).sortedByDescending(TransactionRecord::timestampEpochMillis)
}
