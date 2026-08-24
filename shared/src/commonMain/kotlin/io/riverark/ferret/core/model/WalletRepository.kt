package io.riverark.ferret.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WalletRepository {
    private val mutableState = MutableStateFlow<AppState>(AppState.Locked)
    private val walletLocks = mutableMapOf<WalletId, Mutex>()
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun lock() { mutableState.value = AppState.Locked }
    fun checkingConnectivity() { mutableState.value = AppState.CheckingConnectivity }
    fun offline() { mutableState.value = AppState.Offline }
    fun publish(activeWalletId: WalletId?, wallets: List<WalletProfile>) {
        mutableState.value = if (activeWalletId == null || wallets.isEmpty()) AppState.NoWallets
        else AppState.Ready(activeWalletId, wallets.toList())
    }

    suspend fun <T> withWalletLock(walletId: WalletId, action: suspend () -> T): T =
        walletLocks.getOrPut(walletId) { Mutex() }.withLock { action() }
}
