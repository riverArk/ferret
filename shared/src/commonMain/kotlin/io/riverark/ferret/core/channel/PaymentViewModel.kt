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
import kotlinx.serialization.Serializable

@Serializable
data class PaymentQuote(
    val id: String,
    val amount: Lovelace,
    val invoiceAmountMsat: Long,
    val routingFee: Lovelace,
    val adaptorFee: Lovelace,
    val expiresAtEpochMillis: Long,
    val invoiceHash: String,
) {
    init {
        require(id.length in 1..256 && invoiceAmountMsat > 0 && expiresAtEpochMillis >= 0)
        require(Regex("[0-9a-f]{64}").matches(invoiceHash))
    }
}

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
    private val payments: PaymentStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PaymentUiState>(PaymentUiState.Scanning)
    private var acceptingScan = false
    private var invoice: String? = null
    val state = mutableState.asStateFlow()

    fun scanned(raw: String, nowEpochMillis: Long) {
        if (mutableState.value != PaymentUiState.Scanning || acceptingScan) return
        acceptingScan = true
        viewModelScope.launch {
            try {
                require(raw.startsWith("ln", ignoreCase = true))
                val normalized = raw.trim()
                val parsed = Bolt11Invoice.read(normalized).get()
                require(!parsed.isExpired(nowEpochMillis / 1000)) { "Invoice expired." }
                val paymentHash = parsed.paymentHash.toString()
                val expectedChain = gateway.lightningChain().lowercase()
                require(parsed.chain.toString().lowercase() == expectedChain) { "Invoice network does not match the adaptor." }
                val amountMsat = parsed.amount?.msat ?: error("Amountless invoices are unsupported.")
                require(!payments.isPaid(walletId, paymentHash) && payments.pending(walletId)?.paymentHash != paymentHash) { "Invoice already paid." }
                val quote = gateway.quote(walletId, normalized, paymentHash, amountMsat)
                require(quote.invoiceHash == paymentHash && quote.invoiceAmountMsat == amountMsat && quote.expiresAtEpochMillis > nowEpochMillis)
                invoice = normalized
                mutableState.value = PaymentUiState.Confirming(parsed.description, quote, nowEpochMillis + 3_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("This QR code is not a payable BOLT11 invoice.")
            } finally {
                acceptingScan = false
            }
        }
    }

    fun confirm(operationId: String, intentHash: String, nowEpochMillis: Long) {
        val confirming = mutableState.value as? PaymentUiState.Confirming ?: return
        require(nowEpochMillis >= confirming.confirmAfterEpochMillis)
        require(nowEpochMillis < confirming.quote.expiresAtEpochMillis)
        checkNotNull(invoice) { "invoice unavailable" }
        viewModelScope.launch {
            mutableState.value = PaymentUiState.Processing(operationId)
            try {
                payments.recordPending(walletId, PendingPaymentV1(operationId, confirming.quote.invoiceHash, confirming.quote, nowEpochMillis))
                channels.mutate(walletId, operationId, intentHash, ChannelAction.Pay(confirming.quote.id, confirming.quote.invoiceHash))
                val receipt = gateway.receipt(walletId, operationId, confirming.quote)
                requireReceipt(receipt, operationId, confirming.quote)
                payments.complete(walletId, receipt, nowEpochMillis)
                mutableState.value = PaymentUiState.Complete(receipt)
                invoice = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Payment requires reconciliation.")
            }
        }
    }

    fun reconcile(nowEpochMillis: Long) {
        viewModelScope.launch {
            val pending = payments.pending(walletId) ?: return@launch
            mutableState.value = PaymentUiState.Processing(pending.operationId)
            try {
                channels.reconcile(walletId)
                val receipt = gateway.receipt(walletId, pending.operationId, pending.quote)
                requireReceipt(receipt, pending.operationId, pending.quote)
                payments.complete(walletId, receipt, nowEpochMillis)
                mutableState.value = PaymentUiState.Complete(receipt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Payment requires reconciliation.")
            }
        }
    }

    private fun requireReceipt(receipt: Receipt, operationId: String, quote: PaymentQuote) {
        require(receipt.verified)
        require(receipt.operationId == operationId && receipt.paymentHash == quote.invoiceHash)
        require(receipt.amount == quote.amount && receipt.fee == quote.routingFee + quote.adaptorFee)
    }

    fun scanAgain() { invoice = null; mutableState.value = PaymentUiState.Scanning }
}
