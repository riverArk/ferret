package io.riverark.ferret.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WalletRepository {
    private val mutableState = MutableStateFlow<AppState>(AppState.Locked)
    private val walletLocks = mutableMapOf<WalletId, Mutex>()
    private var selectedWalletId: WalletId? = null
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun lock() { mutableState.value = AppState.Locked }
    fun checkingConnectivity() { mutableState.value = AppState.CheckingConnectivity }
    fun offline() { mutableState.value = AppState.Offline }
    fun selectedWalletId(): WalletId? = selectedWalletId
    fun publish(activeWalletId: WalletId?, wallets: List<WalletProfile>) {
        selectedWalletId = activeWalletId?.takeIf { id -> wallets.any { it.id == id } }
        mutableState.value = if (selectedWalletId == null || wallets.isEmpty()) AppState.NoWallets
        else AppState.Ready(checkNotNull(selectedWalletId), wallets.toList())
    }

    suspend fun <T> withWalletLock(walletId: WalletId, action: suspend () -> T): T =
        walletLocks.getOrPut(walletId) { Mutex() }.withLock { action() }
}
