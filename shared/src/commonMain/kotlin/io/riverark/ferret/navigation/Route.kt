package io.riverark.ferret.navigation

import kotlinx.serialization.Serializable

@Serializable sealed interface Route {
    @Serializable data object Unlock : Route
    @Serializable data object CheckingConnectivity : Route
    @Serializable data object Offline : Route
    @Serializable data object WalletPicker : Route
    @Serializable data object CreateWallet : Route
    @Serializable data object RestoreWallet : Route
    @Serializable data class RestoreBackup(val walletId: String) : Route
    @Serializable data class RecoveryPhrase(val walletId: String) : Route
    @Serializable data class VerifyRecovery(val walletId: String) : Route
    @Serializable data class Home(val walletId: String) : Route
    @Serializable data class TopUp(val walletId: String) : Route
    @Serializable data class Transfer(val walletId: String) : Route
    @Serializable data class History(val walletId: String) : Route
    @Serializable data class OpenChannel(val walletId: String) : Route
    @Serializable data class Channel(val walletId: String) : Route
    @Serializable data class ScanInvoice(val walletId: String) : Route
    @Serializable data class ConfirmPayment(val walletId: String) : Route
    @Serializable data class PaymentReceipt(val walletId: String, val operationId: String) : Route
    @Serializable data class Settings(val walletId: String) : Route
    @Serializable data class RemoveWallet(val walletId: String) : Route
}
