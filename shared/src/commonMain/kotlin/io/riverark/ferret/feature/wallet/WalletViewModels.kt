package io.riverark.ferret.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelPreview
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.ChannelStateChangedException
import io.riverark.ferret.core.channel.ChannelReturnNotReadyException
import io.riverark.ferret.core.backup.MissingBackupException
import io.riverark.ferret.core.backup.StaleBackupWriterException
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.InsufficientFundsException
import io.riverark.ferret.core.cardano.InsufficientCollateralException
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
    private val channelCloseEnabled: Boolean = false,
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
                channels.channels.values.any {
                    it.pending != null || it.payments.pending != null ||
                        (channelCloseEnabled && (it.state is ChannelState.Closing ||
                            it.state == ChannelState.Closed || it.state == ChannelState.Responded ||
                            it.state == ChannelState.Ending || it.confirmedReturnOperation != null))
                }
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

data class ChannelFundingUiState(
    val preview: ChannelPreview? = null,
    val busy: Boolean = false,
    val operationId: String? = null,
    val error: String? = null,
)

class ChannelFundingViewModel(
    private val walletId: WalletId,
    private val previewer: suspend (WalletId, AssetAmount) -> ChannelPreview,
    private val submitter: suspend (WalletId, ChannelPreview) -> String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChannelFundingUiState())
    val state = mutableState.asStateFlow()

    fun previewAsync(amount: AssetAmount) {
        if (mutableState.value.busy) return
        mutableState.value = ChannelFundingUiState(busy = true)
        viewModelScope.launch {
            try {
                mutableState.value = ChannelFundingUiState(preview = previewer(walletId, amount))
            } catch (cancelled: CancellationException) {
                mutableState.value = ChannelFundingUiState()
                throw cancelled
            } catch (_: InsufficientCollateralException) {
                mutableState.value = ChannelFundingUiState(
                    error = "Insufficient ADA-only wallet funds for collateral. Keep at least 5 ADA in separate plain wallet outputs; only the displayed collateral is at risk.",
                )
            } catch (_: InsufficientFundsException) {
                mutableState.value = ChannelFundingUiState(
                    error = "Insufficient selected asset or ADA for the channel output and transaction fee.",
                )
            } catch (_: Exception) {
                mutableState.value = ChannelFundingUiState(
                    error = "Unable to preview channel funding. Check the amount, connection, and backup.",
                )
            }
        }
    }

    fun submitAsync() {
        if (mutableState.value.busy) return
        val preview = mutableState.value.preview ?: return
        mutableState.value = ChannelFundingUiState(busy = true)
        viewModelScope.launch {
            try {
                mutableState.value = ChannelFundingUiState(operationId = submitter(walletId, preview))
            } catch (cancelled: CancellationException) {
                mutableState.value = ChannelFundingUiState(operationId = preview.operation.operationId)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ChannelFundingUiState(
                    operationId = preview.operation.operationId,
                    error = "Channel status is unavailable. Check the channel before trying again.",
                )
            }
        }
    }

    fun clearPreview() {
        if (!mutableState.value.busy) mutableState.value = ChannelFundingUiState()
    }
}

data class ChannelCloseUiState(
    val collection: ChannelCollectionV4? = null,
    val channel: ChannelSnapshot? = null,
    val preview: ChannelPreview? = null,
    val busy: Boolean = false,
    val operationId: String? = null,
    val error: String? = null,
    val lastRefreshEpochMillis: Long? = null,
)

class ChannelCloseViewModel(
    private val walletId: WalletId,
    private val keytag: ProtocolKeytag,
    private val loadChannels: suspend (WalletId) -> ChannelCollectionV4,
    private val previewClose: suspend (WalletId, ProtocolKeytag) -> ChannelPreview,
    private val previewReturnFunds: suspend (WalletId, ProtocolKeytag) -> ChannelPreview,
    private val submitter: suspend (WalletId, ChannelPreview) -> String,
    private val nowEpochMillis: () -> Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChannelCloseUiState())
    private var automaticRefresh: Job? = null
    private var refreshing = false
    val state = mutableState.asStateFlow()

    fun refresh() {
        if (!begin()) return
        viewModelScope.launch {
            try { reload() } finally {
                finish()
                if (refreshing && shouldPoll()) startRefreshing()
            }
        }
    }

    internal suspend fun refreshNow() {
        if (!begin()) return
        try { reload() } finally { finish() }
    }

    fun previewCloseAsync() = previewAsync(true)
    fun previewReturnFundsAsync() = previewAsync(false)

    private fun previewAsync(close: Boolean) {
        if (!beginPreview(close)) return
        viewModelScope.launch { prepare(close) }
    }

    internal suspend fun previewNow(close: Boolean) {
        if (beginPreview(close)) prepare(close)
    }

    private fun beginPreview(close: Boolean): Boolean {
        val current = mutableState.value
        val channel = current.channel ?: return false
        val collection = current.collection ?: return false
        if (current.error != null || current.operationId != null || current.preview != null ||
            channel.pending != null || channel.payments.pending != null ||
            collection.channels.values.any { it.pending?.payload is io.riverark.ferret.core.channel.ChannelPayload.Transaction } ||
            (if (close) channel.state !is ChannelState.Open else
                channel.state !in listOf(ChannelState.Closed, ChannelState.Responded) ||
                    channel.chainObservation?.canReturn != true)
        ) return false
        return begin()
    }

    private suspend fun prepare(close: Boolean) {
        try {
            val preview = if (close) previewClose(walletId, keytag) else previewReturnFunds(walletId, keytag)
            require(preview.operation.keytag == keytag)
            mutableState.value = mutableState.value.copy(preview = preview, error = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ChannelStateChangedException) {
            reload()
            mutableState.value = mutableState.value.copy(
                preview = null,
                error = "This channel changed. Review the updated transaction before confirming.",
            )
        } catch (_: ChannelReturnNotReadyException) {
            reload()
        } catch (_: InsufficientCollateralException) {
            mutableState.value = mutableState.value.copy(error =
                "Insufficient ADA-only wallet funds for collateral. Keep at least 5 ADA in separate plain wallet outputs; only the displayed collateral is at risk.")
        } catch (_: InsufficientFundsException) {
            mutableState.value = mutableState.value.copy(error = "ADA in your L1 wallet is needed for transaction fees and any extra channel ADA.")
        } catch (_: MissingBackupException) {
            backupError()
        } catch (_: StaleBackupWriterException) {
            backupError()
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "Unable to prepare this transaction. Refresh and try again.")
        } finally {
            finish()
            if (refreshing && shouldPoll()) startRefreshing()
        }
    }

    fun submitAsync() {
        val preview = beginSubmission() ?: return
        viewModelScope.launch { submit(preview) }
    }

    internal suspend fun submitNow() {
        val preview = beginSubmission() ?: return
        submit(preview)
    }

    private fun beginSubmission(): ChannelPreview? {
        val current = mutableState.value
        if (current.busy || current.error != null || current.operationId != null) return null
        val preview = current.preview ?: return null
        mutableState.value = current.copy(busy = true, preview = null, operationId = preview.operation.operationId)
        return preview
    }

    private suspend fun submit(preview: ChannelPreview) {
        try {
            require(submitter(walletId, preview) == preview.operation.operationId)
            reload()
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(error = "Checking transaction status. Refresh status before continuing.")
            throw cancelled
        } catch (_: Exception) {
            // The write-ahead or submission may already have succeeded. Only a later durable reload resolves this ID.
            mutableState.value = mutableState.value.copy(error = "Checking transaction status. Refresh status before continuing.")
        } finally {
            finish()
            if (refreshing) startRefreshing()
        }
    }

    fun clearPreview() {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(preview = null)
    }

    fun startRefreshing() {
        refreshing = true
        if (automaticRefresh?.isActive == true) return
        automaticRefresh = viewModelScope.launch { refreshWhilePending() }
    }

    fun stopRefreshing() {
        refreshing = false
        automaticRefresh?.cancel()
        automaticRefresh = null
    }

    internal suspend fun refreshWhilePending(wait: suspend (Long) -> Unit = { delay(it) }) {
        do {
            refreshNow()
            if (!shouldPoll()) return
            wait(20_000L)
        } while (true)
    }

    private fun shouldPoll(): Boolean {
        val current = mutableState.value
        val channel = current.channel ?: return false
        return current.busy || current.operationId != null ||
            current.collection?.channels?.values?.any { it.pending != null || it.payments.pending != null } == true ||
            channel.confirmedReturnOperation != null || channel.state is ChannelState.Closing ||
            channel.state == ChannelState.Ending ||
            ((channel.state == ChannelState.Closed || channel.state == ChannelState.Responded) &&
                (channel.chainObservation?.canReturn != true || current.error != null))
    }

    private fun begin(): Boolean {
        if (mutableState.value.busy) return false
        mutableState.value = mutableState.value.copy(busy = true)
        return true
    }

    private fun finish() {
        mutableState.value = mutableState.value.copy(busy = false)
    }

    private suspend fun reload() {
        try {
            val collection = loadChannels(walletId)
            require(collection.walletId == walletId)
            val channel = collection.channels.values.singleOrNull { it.keytag == keytag }
                ?: error("Selected channel is unavailable")
            val previous = mutableState.value
            val operationId = previous.operationId
            val pending = collection.channels.values.any { it.pending?.operationId == operationId }
            val result = collection.channels.values.asSequence().flatMap { it.history.asSequence() }
                .lastOrNull { it.operationId == operationId }
            val unresolved = operationId != null && (pending ||
                (result != null && result.status != OperationState.COMPLETED && result.status != OperationState.FAILED))
            val changed = previous.channel?.let {
                it.state != channel.state || it.pending != channel.pending ||
                    it.payments.pending != channel.payments.pending ||
                    it.chainObservation?.output != channel.chainObservation?.output ||
                    it.chainObservation?.datum != channel.chainObservation?.datum ||
                    it.spendableBalance != channel.spendableBalance
            } ?: true
            mutableState.value = previous.copy(
                collection = collection,
                channel = channel,
                preview = previous.preview.takeUnless { changed || unresolved },
                operationId = operationId.takeIf { unresolved },
                error = null,
                lastRefreshEpochMillis = nowEpochMillis(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MissingBackupException) {
            backupError()
        } catch (_: StaleBackupWriterException) {
            backupError()
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                preview = null,
                error = "Unable to check channel status. Refresh before continuing.",
            )
        }
    }

    private fun backupError() {
        mutableState.value = mutableState.value.copy(preview = null, error = "Channel backup is unavailable. Resolve backup in Settings before continuing.")
    }
}
