package io.riverark.ferret.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelPreview
import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.InsufficientFundsException
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.CreatedWallet
import io.riverark.ferret.core.model.InvalidRecoveryPhraseException
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.WalletProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

data class AssetBalance(
    val total: AssetAmount,
    val spendable: AssetAmount,
    val pending: AssetAmount,
) {
    init {
        require(total.asset == spendable.asset && total.asset == pending.asset)
        require(spendable.baseUnits <= total.baseUnits && pending.baseUnits <= total.baseUnits)
    }
}

data class WalletBalance(
    val assets: List<AssetBalance>,
    val unsupportedAssets: Map<String, Long>,
) {
    init {
        require(assets.map { it.total.asset.connectorUnit }.distinct().size == assets.size)
        require(unsupportedAssets.all { (unit, amount) -> unit.isNotBlank() && amount >= 0 })
    }
}

data class TransferDestination(val name: String, val address: String)
data class TransferPreview(
    val destination: TransferDestination,
    val amount: AssetAmount,
    val feeBound: AssetAmount,
    val change: TransactionOutputSummary?,
    val recipientAda: AssetAmount,
    val ledgerMinAda: AssetAmount,
    val intent: CardanoIntent.Transfer? = null,
    val unsigned: UnsignedTransaction? = null,
    val transactionId: String? = null,
) {
    init {
        require(amount.asset.catalogDigest == feeBound.asset.catalogDigest)
        require(feeBound.asset == recipientAda.asset && feeBound.asset == ledgerMinAda.asset)
        require(feeBound.asset.policyId == null)
    }
}

data class TransferUiState(
    val preview: TransferPreview? = null,
    val busy: Boolean = false,
    val sweepPreview: io.riverark.ferret.core.cardano.SweepPreview? = null,
    val error: String? = null,
    val operationId: String? = null,
)

interface L1WalletRepository {
    suspend fun balance(walletId: WalletId): WalletBalance
    suspend fun history(walletId: WalletId): List<TransactionRecord>
    suspend fun previewTransfer(walletId: WalletId, destination: TransferDestination, amount: AssetAmount): TransferPreview
    suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview): String
    suspend fun previewSweep(walletId: WalletId, destinationAddress: String): io.riverark.ferret.core.cardano.SweepPreview
    suspend fun submitSweep(walletId: WalletId, preview: io.riverark.ferret.core.cardano.SweepPreview): String
}

data class HomeUiState(
    val profile: WalletProfile,
    val balance: WalletBalance? = null,
    val channels: ChannelCollectionV4? = null,
    val latestActivity: TransactionRecord? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val lastRefreshEpochMillis: Long? = null,
)

class HomeViewModel(
    private val profile: WalletProfile,
    private val loadBalance: suspend (WalletProfile) -> WalletBalance,
    private val loadHistory: suspend (WalletProfile) -> List<TransactionRecord>,
    private val loadChannels: suspend (WalletId) -> ChannelCollectionV4,
    private val nowEpochMillis: () -> Long = { 0 },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(profile))
    private var automaticRefresh: Job? = null
    private var hasPendingActivity = false
    val state = mutableState.asStateFlow()

    fun startRefreshing() {
        if (automaticRefresh?.isActive == true) return
        automaticRefresh = viewModelScope.launch { refreshWhilePending() }
    }

    fun stopRefreshing() {
        automaticRefresh?.cancel()
        automaticRefresh = null
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    internal suspend fun refreshWhilePending(wait: suspend (Long) -> Unit = { delay(it) }) {
        do {
            refreshNow()
            if (hasPendingActivity) wait(PENDING_REFRESH_MILLIS)
        } while (hasPendingActivity)
    }

    suspend fun refreshNow() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            val balance = loadBalance(profile)
            val history = mergeTransactionRecords(loadHistory(profile), emptyList())
            val channels = loadChannels(profile.id)
            hasPendingActivity = history.any { it.state == io.riverark.ferret.core.model.TransactionState.PENDING } ||
                channels.channels.values.any { it.pending != null || it.payments.pending != null }
            mutableState.value = mutableState.value.copy(
                balance = balance,
                channels = channels,
                latestActivity = history.firstOrNull(),
                loading = false,
                lastRefreshEpochMillis = nowEpochMillis().takeIf { it > 0 },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = "Unable to refresh wallet. Check your connection and try again.",
            )
        }
    }

    private companion object {
        const val PENDING_REFRESH_MILLIS = 20_000L
    }
}

class TopUpViewModel(val profile: WalletProfile) : ViewModel()

data class HistoryUiState(
    val profile: WalletProfile,
    val records: List<TransactionRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val lastRefreshEpochMillis: Long? = null,
)

class HistoryViewModel(
    private val profile: WalletProfile,
    private val loadHistory: suspend (WalletProfile) -> List<TransactionRecord>,
    private val nowEpochMillis: () -> Long = { 0 },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState(profile))
    val state = mutableState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                mutableState.value = mutableState.value.copy(
                    records = mergeTransactionRecords(loadHistory(profile), emptyList()),
                    lastRefreshEpochMillis = nowEpochMillis().takeIf { it > 0 },
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = "Unable to load history. Check your connection and try again.",
                )
            }
        }
    }
}

internal fun mergeTransactionRecords(
    l1: List<TransactionRecord>,
    l2: List<TransactionRecord>,
): List<TransactionRecord> = (l1 + l2)
    .groupBy { Triple(it.realm, it.id, it.channelKeytag) }
    .values
    .map { records -> records.first().also { first -> require(records.all { it == first }) { "conflicting transaction history" } } }
    .sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })

class TransferViewModel(private val walletId: WalletId, private val network: CardanoNetwork, private val l1: L1WalletRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TransferUiState())
    val state = mutableState.asStateFlow()

    fun previewAsync(destination: TransferDestination, amount: AssetAmount) {
        viewModelScope.launch {
            mutableState.value = TransferUiState(busy = true)
            try {
                mutableState.value = TransferUiState(preview(destination, amount))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = TransferUiState(error = "Unable to preview transfer. Check the amount and connection.")
            }
        }
    }

    fun previewSweepAsync(destinationAddress: String) {
        viewModelScope.launch {
            mutableState.value = TransferUiState(busy = true)
            try {
                mutableState.value = TransferUiState(sweepPreview = l1.previewSweep(walletId, destinationAddress))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = TransferUiState(error = "Unable to preview transfer. Check the address and connection.")
            }
        }
    }

    fun submitAsync() {
        val preview = mutableState.value.preview
        val sweepPreview = mutableState.value.sweepPreview
        if (preview == null && sweepPreview == null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val operationId = if (preview != null) submit(preview) else l1.submitSweep(walletId, checkNotNull(sweepPreview))
                mutableState.value = TransferUiState(operationId = operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = "Transfer status is unavailable. Check History before trying again.")
            }
        }
    }
    fun destinations(profiles: List<WalletProfile>) =
        profiles.filter { it.id != walletId && it.network == network }
            .map { TransferDestination(it.name, it.paymentAddress) }

    suspend fun preview(destination: TransferDestination, amount: AssetAmount): TransferPreview =
        l1.previewTransfer(walletId, destination, amount)

    suspend fun submit(preview: TransferPreview) = l1.submitTransfer(walletId, preview)
}

data class OpenChannelUiState(
    val preview: ChannelPreview? = null,
    val busy: Boolean = false,
    val operationId: String? = null,
    val error: String? = null,
)

class OpenChannelViewModel(
    private val walletId: WalletId,
    private val previewer: suspend (WalletId, AssetAmount) -> ChannelPreview,
    private val submitter: suspend (WalletId, ChannelPreview) -> String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OpenChannelUiState())
    val state = mutableState.asStateFlow()

    fun previewAsync(amount: AssetAmount) {
        if (mutableState.value.busy) return
        mutableState.value = OpenChannelUiState(busy = true)
        viewModelScope.launch {
            try {
                mutableState.value = OpenChannelUiState(preview = previewer(walletId, amount))
            } catch (cancelled: CancellationException) {
                mutableState.value = OpenChannelUiState()
                throw cancelled
            } catch (_: InsufficientFundsException) {
                mutableState.value = OpenChannelUiState(
                    error = "Insufficient selected asset or ADA for the channel output and transaction fee.",
                )
            } catch (_: Exception) {
                mutableState.value = OpenChannelUiState(
                    error = "Unable to preview channel. Check the amount, connection, and backup.",
                )
            }
        }
    }

    fun submitAsync() {
        if (mutableState.value.busy) return
        val preview = mutableState.value.preview ?: return
        mutableState.value = OpenChannelUiState(busy = true)
        viewModelScope.launch {
            try {
                mutableState.value = OpenChannelUiState(operationId = submitter(walletId, preview))
            } catch (cancelled: CancellationException) {
                mutableState.value = OpenChannelUiState(operationId = preview.operation.operationId)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = OpenChannelUiState(
                    operationId = preview.operation.operationId,
                    error = "Channel status is unavailable. Check the channel before trying again.",
                )
            }
        }
    }

    fun clearPreview() {
        if (!mutableState.value.busy) mutableState.value = OpenChannelUiState()
    }
}
