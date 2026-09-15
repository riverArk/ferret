package io.riverark.ferret.core.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.client.plugins.ResponseException
import io.riverark.ferret.core.network.quoteRejectionMessage
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
    val protocolIndex: Long = 0,
    val relativeTimeoutMillis: Long = 1,
) {
    init {
        require(id.length in 1..256 && invoiceAmountMsat > 0 && expiresAtEpochMillis >= 0 && protocolIndex >= 0 && relativeTimeoutMillis > 0)
        require(Regex("[0-9a-f]{64}").matches(invoiceHash))
    }
}

sealed interface PaymentUiState {
    data object Scanning : PaymentUiState
    data object Quoting : PaymentUiState
    data class Confirming(val description: String?, val quote: PaymentQuote, val confirmAfterEpochMillis: Long) : PaymentUiState
    data class Processing(val operationId: String? = null) : PaymentUiState
    data class Complete(val receipt: Receipt) : PaymentUiState
    data class Error(val message: String) : PaymentUiState
}

interface PaymentGateway {
    suspend fun lightningChain(): String
    suspend fun quote(walletId: WalletId, invoice: String, invoiceHash: String, amountMsat: Long): PaymentQuote
    suspend fun prepare(
        walletId: WalletId,
        operationId: String,
        intentHash: String,
        invoice: String,
        quote: PaymentQuote,
        preparedAtEpochMillis: Long,
    ): ChannelPreview
}

private class PaymentQuoteRejected(
    val displayMessage: String,
    cause: Throwable,
) : Exception(cause)

class DefaultPaymentGateway(
    private val adaptor: suspend (WalletId) -> io.riverark.ferret.core.network.AdaptorClient,
    private val keytag: suspend (WalletId) -> ProtocolKeytag,
    private val writer: suspend (WalletId) -> WriterLease,
    private val signer: suspend (WalletId) -> ProtocolSigner,
    private val network: suspend (WalletId) -> io.riverark.ferret.core.model.CardanoNetwork,
    private val chain: String,
    private val crypto: ProtocolCrypto,
) : PaymentGateway {
    override suspend fun lightningChain() = chain

    override suspend fun quote(
        walletId: WalletId,
        invoice: String,
        invoiceHash: String,
        amountMsat: Long,
    ): PaymentQuote {
        val adaptor = try {
            adaptor(walletId)
        } catch (error: Exception) {
            throw PaymentQuoteRejected("The payment service is unavailable.", error)
        }
        val keytag = try {
            keytag(walletId)
        } catch (error: Exception) {
            throw PaymentQuoteRejected("The verified channel identity is unavailable.", error)
        }
        val writer = try {
            writer(walletId)
        } catch (error: Exception) {
            throw PaymentQuoteRejected("Unable to verify the payment backup writer.", error)
        }
        val receipt = try {
            adaptor.receipt(keytag)
        } catch (error: ResponseException) {
            throw PaymentQuoteRejected(error.quoteRejectionMessage(), error)
        } catch (error: Exception) {
            throw PaymentQuoteRejected("Unable to verify the channel payment state.", error)
        }
        if (receipt == null) {
            val signer = signer(walletId)
            require(signer.verificationKeyHex() == keytag.value.take(64))
            val body = SquashBodyWire(0, 0, emptyList())
            try {
                adaptor.squash(
                    keytag,
                    writer.token,
                    SignedSquashWire(
                        body,
                        signer.sign(body.taggedCbor(ProtocolTag(keytag.value.drop(64)))).toHex(),
                    ),
                )
            } catch (error: ResponseException) {
                throw PaymentQuoteRejected(
                    "Channel payment initialization failed: ${error.quoteRejectionMessage()}",
                    error,
                )
            }
        }
        val quote = try {
            adaptor.quote(keytag, writer.token, invoice)
        } catch (error: ResponseException) {
            throw PaymentQuoteRejected(error.quoteRejectionMessage(), error)
        } catch (error: Exception) {
            throw PaymentQuoteRejected("The adaptor returned an invalid payment quote.", error)
        }
        if (quote.invoice_hash != invoiceHash || quote.invoice_amount_msat != amountMsat) {
            throw PaymentQuoteRejected(
                "The adaptor returned a quote for a different invoice.",
                IllegalArgumentException("payment quote identity mismatch"),
            )
        }
        val digest = crypto.sha256(
            "${quote.index}:${quote.amount}:${quote.relative_timeout}:${quote.routing_fee}:$invoiceHash:${quote.expires_at_epoch_millis}"
                .encodeToByteArray(),
        ).toHex()
        return PaymentQuote(
            digest,
            Lovelace(quote.payment_amount),
            quote.invoice_amount_msat,
            Lovelace(quote.routing_fee_amount),
            Lovelace(quote.adaptor_fee),
            quote.expires_at_epoch_millis,
            invoiceHash,
            quote.index,
            quote.relative_timeout,
        )
    }

    override suspend fun prepare(
        walletId: WalletId,
        operationId: String,
        intentHash: String,
        invoice: String,
        quote: PaymentQuote,
        preparedAtEpochMillis: Long,
    ): ChannelPreview {
        require(preparedAtEpochMillis < quote.expiresAtEpochMillis)
        val keytag = keytag(walletId)
        val signer = signer(walletId)
        require(signer.verificationKeyHex() == keytag.value.take(64))
        val body = ChequeBodyWire(
            index = quote.protocolIndex,
            amount = quote.amount.value + quote.routingFee.value + quote.adaptorFee.value,
            timeout = ProtocolDurationWire.fromMillis(preparedAtEpochMillis + quote.relativeTimeoutMillis),
            latch = Hex32(quote.invoiceHash),
        )
        val authorization = body.taggedCbor(ProtocolTag(keytag.value.drop(64)))
        val request = AdaptorPayRequest(body, signer.sign(authorization).toHex(), invoice)
        val payload = ChannelPayload.Payment(authorization, invoice, quote.invoiceHash, quote.id, request, quote)
        return ChannelPreview(
            PreparedChannelOperation(
                operationId,
                intentHash,
                ChannelAction.Pay(quote.id, quote.invoiceHash),
                preparedAtEpochMillis = preparedAtEpochMillis,
                payload = payload,
                keytag = keytag.value,
            ),
            quote.amount,
            quote.routingFee + quote.adaptorFee,
            quote.routingFee + quote.adaptorFee,
            Lovelace(0),
            Lovelace(0),
            Lovelace(0),
            Lovelace(0),
            network(walletId),
        )
    }

}

class PaymentViewModel(
    private val walletId: WalletId,
    private val channels: ChannelRepository,
    private val gateway: PaymentGateway,
    private val payments: PaymentStore,
    private val clock: () -> Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PaymentUiState>(PaymentUiState.Scanning)
    private var acceptingScan = false
    private var invoice: String? = null
    val state = mutableState.asStateFlow()

    fun scanned(raw: String, nowEpochMillis: Long) {
        if (mutableState.value != PaymentUiState.Scanning || acceptingScan) return
        mutableState.value = PaymentUiState.Quoting
        acceptingScan = true
        viewModelScope.launch {
            try {
                val rawInvoice = raw.trim()
                val normalized = if (rawInvoice.startsWith("lightning:", ignoreCase = true)) {
                    rawInvoice.substringAfter(':')
                } else {
                    rawInvoice
                }
                require(normalized.startsWith("ln", ignoreCase = true))
                val parsed = Bolt11Invoice.read(normalized).get()
                if (parsed.isExpired(nowEpochMillis / 1000)) {
                    mutableState.value = PaymentUiState.Error("This BOLT11 invoice has expired.")
                    return@launch
                }
                val paymentHash = parsed.paymentHash.toString()
                val expectedChain = gateway.lightningChain().lowercase()
                require(parsed.chain.toString().lowercase() == expectedChain) { "Invoice network does not match the adaptor." }
                val amountMsat = parsed.amount?.msat ?: error("Amountless invoices are unsupported.")
                require(!payments.isPaid(walletId, paymentHash) && payments.pending(walletId)?.paymentHash != paymentHash) { "Invoice already paid." }
                val quote = try {
                    gateway.quote(walletId, normalized, paymentHash, amountMsat)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PaymentQuoteRejected) {
                    mutableState.value = PaymentUiState.Error(error.displayMessage)
                    return@launch
                } catch (error: Exception) {
                    mutableState.value = PaymentUiState.Error(
                        (error as? ResponseException)?.quoteRejectionMessage()
                            ?: "Unable to obtain a payment quote.",
                    )
                    return@launch
                }
                val quotedAt = clock()
                if (parsed.isExpired(quotedAt / 1_000)) {
                    mutableState.value = PaymentUiState.Error("This BOLT11 invoice expired while obtaining the quote.")
                    return@launch
                }
                require(quote.invoiceHash == paymentHash && quote.invoiceAmountMsat == amountMsat && quote.expiresAtEpochMillis > quotedAt)
                invoice = normalized
                mutableState.value = PaymentUiState.Confirming(parsed.description, quote, quotedAt + 3_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("This QR code is not a payable BOLT11 invoice.")
            } finally {
                acceptingScan = false
            }
        }
    }

    fun confirm(nowEpochMillis: Long) {
        val confirming = mutableState.value as? PaymentUiState.Confirming ?: return
        require(nowEpochMillis >= confirming.confirmAfterEpochMillis)
        require(nowEpochMillis < confirming.quote.expiresAtEpochMillis)
        val pendingInvoice = checkNotNull(invoice) { "invoice unavailable" }
        if (Bolt11Invoice.read(pendingInvoice).get().isExpired(nowEpochMillis / 1_000)) {
            mutableState.value = PaymentUiState.Error("This BOLT11 invoice has expired. Scan a fresh invoice.")
            invoice = null
            return
        }
        mutableState.value = PaymentUiState.Processing()
        viewModelScope.launch {
            try {
                val result = channels.submitPayment(
                    walletId,
                    pendingInvoice,
                    confirming.quote,
                    gateway,
                    nowEpochMillis,
                )
                if (result.status == io.riverark.ferret.core.model.OperationState.FAILED) {
                    mutableState.value = PaymentUiState.Error(
                        result.failureMessage ?: "The Lightning payment failed.",
                    )
                    invoice = null
                    return@launch
                }
                mutableState.value = PaymentUiState.Processing(result.operationId)
                if (result.status != io.riverark.ferret.core.model.OperationState.COMPLETED) {
                    channels.reconcile(walletId)
                }
                val receipt = requireNotNull(payments.receipt(walletId, result.operationId))
                requireReceipt(receipt, result.operationId, confirming.quote)
                mutableState.value = PaymentUiState.Complete(receipt)
                invoice = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val pending = payments.pending(walletId)
                try {
                    requireNotNull(pending)
                    channels.reconcile(walletId)
                    val receipt = requireNotNull(payments.receipt(walletId, pending.operationId))
                    requireReceipt(receipt, pending.operationId, pending.quote)
                    mutableState.value = PaymentUiState.Complete(receipt)
                    invoice = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = PaymentUiState.Error("Payment was not confirmed. Channel reconciliation is required.")
                }
        }
    }
    }

    fun reconcile(nowEpochMillis: Long) {
        viewModelScope.launch {
            val pending = payments.pending(walletId) ?: return@launch
            mutableState.value = PaymentUiState.Processing(pending.operationId)
            try {
                channels.reconcile(walletId)
                val receipt = requireNotNull(payments.receipt(walletId, pending.operationId))
                requireReceipt(receipt, pending.operationId, pending.quote)
                mutableState.value = PaymentUiState.Complete(receipt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Payment was not confirmed. Channel reconciliation is required.")
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

private fun ByteArray.toHex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789abcdef" })
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
