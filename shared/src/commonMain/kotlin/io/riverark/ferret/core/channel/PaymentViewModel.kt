package io.riverark.ferret.core.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.acinq.lightning.payment.Bolt11Invoice
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.core.model.WalletId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PaymentQuote(
    val id: String,
    val amount: Lovelace,
    val routingFee: Lovelace,
    val adaptorFee: Lovelace,
    val expiresAtEpochMillis: Long,
    val invoiceHash: String,
)

sealed interface PaymentUiState {
    data object Scanning : PaymentUiState
    data class Confirming(val description: String?, val quote: PaymentQuote, val confirmAfterEpochMillis: Long) : PaymentUiState
    data class Processing(val operationId: String) : PaymentUiState
    data class Complete(val receipt: Receipt) : PaymentUiState
    data class Error(val message: String) : PaymentUiState
}

interface PaymentGateway {
    suspend fun lightningChain(): String
    suspend fun quote(walletId: WalletId, invoice: String, invoiceHash: String, amountMsat: Long): PaymentQuote
    suspend fun receipt(walletId: WalletId, operationId: String, quote: PaymentQuote): Receipt
}

class PaymentViewModel(
    private val walletId: WalletId,
    private val channels: ChannelRepository,
    private val gateway: PaymentGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PaymentUiState>(PaymentUiState.Scanning)
    private val paidHashes = mutableSetOf<String>()
    private var invoice: String? = null
    val state = mutableState.asStateFlow()

    fun scanned(raw: String, nowEpochMillis: Long) {
        if (mutableState.value != PaymentUiState.Scanning) return
        viewModelScope.launch {
            try {
                require(raw.startsWith("ln", ignoreCase = true))
                val parsed = Bolt11Invoice.read(raw.trim()).get()
                require(!parsed.isExpired(nowEpochMillis / 1000)) { "Invoice expired." }
                val paymentHash = parsed.paymentHash.toString()
                require(paidHashes.add(paymentHash)) { "Invoice already paid." }
                val expectedChain = gateway.lightningChain().lowercase()
                require(parsed.chain.toString().lowercase().contains(expectedChain)) { "Invoice network does not match the adaptor." }
                val amountMsat = parsed.amount?.msat ?: error("Amountless invoices are unsupported.")
                val quote = gateway.quote(walletId, raw, paymentHash, amountMsat)
                require(quote.invoiceHash == paymentHash && quote.expiresAtEpochMillis > nowEpochMillis)
                invoice = raw
                mutableState.value = PaymentUiState.Confirming(parsed.description, quote, nowEpochMillis + 3_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("This QR code is not a payable BOLT11 invoice.")
            }
        }
    }

    fun confirm(operationId: String, intentHash: String, nowEpochMillis: Long) {
        val confirming = mutableState.value as? PaymentUiState.Confirming ?: return
        require(nowEpochMillis >= confirming.confirmAfterEpochMillis)
        require(nowEpochMillis < confirming.quote.expiresAtEpochMillis)
        val rawInvoice = invoice ?: error("invoice unavailable")
        viewModelScope.launch {
            mutableState.value = PaymentUiState.Processing(operationId)
            try {
                channels.mutate(walletId, operationId, intentHash, ChannelAction.Pay(confirming.quote.id, confirming.quote.invoiceHash))
                mutableState.value = PaymentUiState.Complete(gateway.receipt(walletId, operationId, confirming.quote))
                invoice = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Payment requires reconciliation.")
            }
        }
    }

    fun scanAgain() { invoice = null; mutableState.value = PaymentUiState.Scanning }
}
