package io.riverark.ferret.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.CreatedWallet
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.InvalidRecoveryPhraseException
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
    fun select(walletId: WalletId, result: () -> Unit = {}) =
        launch(result = { result() }) { manager.load(walletId); Unit }
    fun create(name: String, network: CardanoNetwork, result: (CreatedWallet) -> Unit) {
        if (name.isBlank()) return fail("Enter a wallet name.")
        launch(result) { manager.create(name, network) }
    }
    fun restore(name: String, network: CardanoNetwork, phrase: String, result: (WalletProfile) -> Unit) {
        if (name.isBlank()) return fail("Enter a wallet name.")
        if (phrase.trim().split(Regex("\\s+")).filter(String::isNotBlank).size != 24) return fail("Enter all 24 recovery words.")
        launch(result, { error ->
            when {
                error is InvalidRecoveryPhraseException -> "That recovery phrase is not valid."
                error is IllegalArgumentException && error.message == "wallet already restored" -> "This wallet is already on this device."
                error is IllegalArgumentException -> "That recovery phrase is not valid."
                else -> "Wallet operation failed. Try again."
            }
        }) { manager.restore(name, network, phrase) }
    }
    fun recoveryWords(walletId: WalletId, result: (List<String>) -> Unit) =
        launch(result) { manager.recoveryWords(walletId) }
    fun confirmRecoveryPhrase(walletId: WalletId, result: () -> Unit = {}) =
        launch(result = { result() }) { manager.confirmRecoveryPhrase(walletId); Unit }
    fun rename(walletId: WalletId, name: String) = launch { manager.rename(walletId, name) }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(busy = false, error = message)
    }

    private fun <T> launch(
        result: (T) -> Unit = {},
        errorMessage: (Exception) -> String = { "Wallet operation failed. Try again." },
        action: suspend () -> T,
    ) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val value = action()
                mutableState.value = WalletPickerUiState(manager.load())
                result(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = errorMessage(error))
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

data class HomeUiState(
    val profile: WalletProfile,
    val balance: Lovelace? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(private val profile: WalletProfile, private val loadBalance: suspend (WalletProfile) -> Lovelace) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(profile))
    val state = mutableState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                mutableState.value = mutableState.value.copy(balance = loadBalance(profile), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = "Unable to load balance. Check your connection and try again.",
                )
            }
        }
    }
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
