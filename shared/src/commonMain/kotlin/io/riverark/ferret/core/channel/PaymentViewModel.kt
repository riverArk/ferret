package io.riverark.ferret.core.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.client.plugins.ResponseException
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.network.quoteRejectionMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PaymentQuote(
    val id: String,
    val keytag: ProtocolKeytag,
    val amount: AssetAmount,
    val invoiceAmountMsat: Long,
    val routingFee: AssetAmount,
    val adaptorFee: AssetAmount,
    val expiresAtEpochMillis: Long,
    val invoiceHash: String,
    val protocolIndex: Long = 0,
    val relativeTimeoutMillis: Long = 1,
    val bindingVersion: Int,
) {
    init {
        require(id.length in 1..256 && invoiceAmountMsat > 0 && expiresAtEpochMillis >= 0)
        require(protocolIndex >= 0 && relativeTimeoutMillis > 0 && bindingVersion in 1..2)
        require(Regex("[0-9a-f]{64}").matches(invoiceHash))
        require(amount.asset == routingFee.asset && amount.asset == adaptorFee.asset)
    }
}

data class ChannelChoice(val keytag: ProtocolKeytag, val asset: ChannelAsset, val spendable: AssetAmount)

sealed interface PaymentUiState {
    data object Scanning : PaymentUiState
    data object Quoting : PaymentUiState
    data class SelectingChannel(val description: String?, val choices: List<ChannelChoice>) : PaymentUiState
    data class Confirming(
        val description: String?,
        val quote: PaymentQuote,
        val selectedCapacity: AssetAmount,
        val confirmAfterEpochMillis: Long,
    ) : PaymentUiState
    data class Processing(val keytag: ProtocolKeytag, val operationId: String? = null) : PaymentUiState
    data class Complete(val receipt: Receipt) : PaymentUiState
    data class Error(val message: String) : PaymentUiState
}

interface PaymentGateway {
    suspend fun lightningChain(): String
    suspend fun quote(walletId: WalletId, keytag: ProtocolKeytag, invoice: String, invoiceHash: String, amountMsat: Long): PaymentQuote
    suspend fun prepare(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        operationId: String,
        intentHash: String,
        invoice: String,
        quote: PaymentQuote,
        preparedAtEpochMillis: Long,
    ): ChannelPreview
    suspend fun prepareInitialization(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        operationId: String,
        preparedAtEpochMillis: Long,
    ): ChannelPreview?
}

private class PaymentQuoteRejected(val displayMessage: String, cause: Throwable) : Exception(cause)

class DefaultPaymentGateway(
    private val adaptor: suspend (WalletId) -> io.riverark.ferret.core.network.AdaptorClient,
    private val selected: suspend (WalletId, ProtocolKeytag) -> ChannelSnapshot,
    private val writer: suspend (WalletId) -> WriterLease,
    private val signer: suspend (WalletId) -> ProtocolSigner,
    private val network: suspend (WalletId) -> io.riverark.ferret.core.model.CardanoNetwork,
    private val chain: String,
    private val crypto: ProtocolCrypto,
    private val assets: AssetCatalog,
) : PaymentGateway {
    override suspend fun lightningChain() = chain

    override suspend fun quote(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        invoice: String,
        invoiceHash: String,
        amountMsat: Long,
    ): PaymentQuote {
        val entry = requireSelected(walletId, keytag)
        val adaptor = adaptor(walletId)
        assets.requireDiscoveryDigest(adaptor.info().assetCatalogDigest)
        val lease = try { writer(walletId) } catch (error: Exception) {
            throw PaymentQuoteRejected("Unable to verify the payment backup writer.", error)
        }
        requireNotNull(adaptor.receipt(keytag)) { "Channel payment initialization is required." }
        val quote = try {
            adaptor.quote(keytag, lease.token, invoice)
        } catch (error: ResponseException) {
            throw PaymentQuoteRejected(error.quoteRejectionMessage(), error)
        }
        require(quote.invoice_hash == invoiceHash && quote.invoice_amount_msat == amountMsat)
        val amount = AssetAmount(entry.asset, quote.payment_amount)
        val routing = AssetAmount(entry.asset, quote.routing_fee_amount)
        val adaptorFee = AssetAmount(entry.asset, quote.adaptor_fee)
        val binding = QuoteBinding(
            walletId, keytag, entry.asset, invoiceHash, amountMsat, quote.index,
            amount, routing, adaptorFee, quote.relative_timeout, quote.expires_at_epoch_millis,
        )
        return PaymentQuote(
            crypto.sha256(Json.encodeToString(binding).encodeToByteArray()).hex(),
            keytag,
            amount,
            quote.invoice_amount_msat,
            routing,
            adaptorFee,
            quote.expires_at_epoch_millis,
            invoiceHash,
            quote.index,
            quote.relative_timeout,
            bindingVersion = 2,
        )
    }

    override suspend fun prepareInitialization(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        operationId: String,
        preparedAtEpochMillis: Long,
    ): ChannelPreview? {
        val entry = requireSelected(walletId, keytag)
        val adaptor = adaptor(walletId)
        assets.requireDiscoveryDigest(adaptor.info().assetCatalogDigest)
        if (adaptor.receipt(keytag) != null) return null
        val signer = signer(walletId)
        require(signer.verificationKeyHex() == keytag.value.take(64))
        val body = SquashBodyWire(0, 0, emptyList())
        val request = SignedSquashWire(body, signer.sign(body.taggedCbor(ProtocolTag(keytag.value.drop(64)))).hex())
        val zero = AssetAmount(entry.asset, 0)
        return ChannelPreview(
            PreparedChannelOperation(
                operationId,
                crypto.sha256(Json.encodeToString(request).encodeToByteArray()).hex(),
                keytag,
                entry.asset,
                ChannelAction.Squash,
                (entry.state as ChannelState.Open).channelId,
                preparedAtEpochMillis,
                ChannelPayload.Squash(request),
                entry.spendableBalance,
            ),
            zero, zero, zero, zero, zero, zero, entry.spendableBalance, network(walletId),
        )
    }

    override suspend fun prepare(
        walletId: WalletId,
        keytag: ProtocolKeytag,
        operationId: String,
        intentHash: String,
        invoice: String,
        quote: PaymentQuote,
        preparedAtEpochMillis: Long,
    ): ChannelPreview {
        require(quote.bindingVersion == 2 && quote.keytag == keytag && preparedAtEpochMillis < quote.expiresAtEpochMillis)
        val entry = requireSelected(walletId, keytag)
        assets.requireDiscoveryDigest(adaptor(walletId).info().assetCatalogDigest)
        require(quote.amount.asset == entry.asset)
        val signer = signer(walletId)
        require(signer.verificationKeyHex() == keytag.value.take(64))
        val total = quote.amount + quote.routingFee + quote.adaptorFee
        require(total.baseUnits <= entry.spendableBalance.baseUnits) { "Insufficient selected channel capacity." }
        val body = ChequeBodyWire(
            quote.protocolIndex,
            total.baseUnits,
            ProtocolDurationWire.fromMillis(preparedAtEpochMillis + quote.relativeTimeoutMillis),
            Hex32(quote.invoiceHash),
        )
        val authorization = body.taggedCbor(ProtocolTag(keytag.value.drop(64)))
        val request = AdaptorPayRequest(body, signer.sign(authorization).hex(), invoice)
        val fee = quote.routingFee + quote.adaptorFee
        val zero = AssetAmount(entry.asset, 0)
        return ChannelPreview(
            PreparedChannelOperation(
                operationId,
                intentHash,
                keytag,
                entry.asset,
                ChannelAction.Pay(quote.id, quote.invoiceHash),
                (entry.state as ChannelState.Open).channelId,
                preparedAtEpochMillis,
                ChannelPayload.Payment(authorization, invoice, quote.invoiceHash, quote.id, request, quote),
                entry.spendableBalance - total,
            ),
            quote.amount, fee, fee, zero, zero, zero, entry.spendableBalance - total, network(walletId),
        )
    }

    private suspend fun requireSelected(walletId: WalletId, keytag: ProtocolKeytag): ChannelSnapshot =
        selected(walletId, keytag).also {
            require(it.keytag == keytag && it.asset == assets.requireAsset(it.asset))
            require(it.asset == assets.ada && it.state is ChannelState.Open && it.pending == null && it.spendableBalance.baseUnits > 0)
        }

    @Serializable
    private data class QuoteBinding(
        val walletId: WalletId,
        val keytag: ProtocolKeytag,
        val asset: ChannelAsset,
        val invoiceHash: String,
        val invoiceAmountMsat: Long,
        val protocolIndex: Long,
        val amount: AssetAmount,
        val routingFee: AssetAmount,
        val adaptorFee: AssetAmount,
        val relativeTimeoutMillis: Long,
        val expiresAtEpochMillis: Long,
    )
}

class PaymentViewModel(
    private val walletId: WalletId,
    private val channels: ChannelRepository,
    private val gateway: PaymentGateway,
    private val clock: () -> Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PaymentUiState>(PaymentUiState.Scanning)
    private var acceptingScan = false
    private var invoice: String? = null
    private var invoiceHash: String? = null
    private var invoiceAmountMsat: Long? = null
    private var description: String? = null
    val state = mutableState.asStateFlow()

    fun scanned(raw: String, nowEpochMillis: Long) {
        if (mutableState.value != PaymentUiState.Scanning || acceptingScan) return
        mutableState.value = PaymentUiState.Quoting
        acceptingScan = true
        viewModelScope.launch {
            try {
                val normalized = raw.trim().let { if (it.startsWith("lightning:", true)) it.substringAfter(':') else it }
                require(normalized.startsWith("ln", true))
                val parsed = Bolt11Invoice.read(normalized).get()
                require(!parsed.isExpired(nowEpochMillis / 1_000)) { "expired" }
                val hash = parsed.paymentHash.toString()
                require(parsed.chain.toString().lowercase() == gateway.lightningChain().lowercase())
                val amountMsat = parsed.amount?.msat ?: error("Amountless invoices are unsupported.")
                channels.load(walletId)
                val collection = requireNotNull(channels.snapshots.value[walletId])
                require(hash !in collection.paidHashes && collection.channels.values.none { it.payments.pending?.paymentHash == hash })
                val choices = collection.channels.values.filter {
                    it.asset.policyId == null && it.state is ChannelState.Open && it.pending == null && it.spendableBalance.baseUnits > 0
                }.sortedBy { it.keytag.value }.map { ChannelChoice(it.keytag, it.asset, it.spendableBalance) }
                if (choices.isEmpty()) {
                    mutableState.value = PaymentUiState.Error("No compatible payment channel.")
                    return@launch
                }
                invoice = normalized
                invoiceHash = hash
                invoiceAmountMsat = amountMsat
                description = parsed.description
                mutableState.value = PaymentUiState.SelectingChannel(parsed.description, choices)
                if (choices.size == 1) selectChannel(choices.single().keytag, clock())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("This QR code is not a payable BOLT11 invoice.")
            } finally {
                acceptingScan = false
            }
        }
    }

    fun selectChannel(keytag: ProtocolKeytag, nowEpochMillis: Long) {
        val selecting = mutableState.value as? PaymentUiState.SelectingChannel ?: return
        val choice = selecting.choices.singleOrNull { it.keytag == keytag } ?: return
        val encodedInvoice = checkNotNull(invoice)
        if (Bolt11Invoice.read(encodedInvoice).get().isExpired(nowEpochMillis / 1_000)) {
            failExpired()
            return
        }
        mutableState.value = PaymentUiState.Quoting
        viewModelScope.launch {
            try {
                channels.initializePayment(walletId, keytag, gateway, nowEpochMillis)
                val quote = gateway.quote(walletId, keytag, encodedInvoice, checkNotNull(invoiceHash), checkNotNull(invoiceAmountMsat))
                val quotedAt = clock()
                require(!Bolt11Invoice.read(encodedInvoice).get().isExpired(quotedAt / 1_000))
                require(quote.expiresAtEpochMillis > quotedAt && quote.keytag == keytag)
                require((quote.amount + quote.routingFee + quote.adaptorFee).baseUnits <= choice.spendable.baseUnits)
                mutableState.value = PaymentUiState.Confirming(description, quote, choice.spendable, quotedAt + 3_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PaymentQuoteRejected) {
                mutableState.value = PaymentUiState.Error(error.displayMessage)
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Unable to obtain a payment quote.")
            }
        }
    }

    fun confirm(nowEpochMillis: Long) {
        val confirming = mutableState.value as? PaymentUiState.Confirming ?: return
        require(nowEpochMillis >= confirming.confirmAfterEpochMillis && nowEpochMillis < confirming.quote.expiresAtEpochMillis)
        val encodedInvoice = checkNotNull(invoice)
        if (Bolt11Invoice.read(encodedInvoice).get().isExpired(nowEpochMillis / 1_000)) {
            failExpired()
            return
        }
        val keytag = confirming.quote.keytag
        mutableState.value = PaymentUiState.Processing(keytag)
        viewModelScope.launch {
            try {
                val result = channels.submitPayment(walletId, keytag, encodedInvoice, confirming.quote, gateway, nowEpochMillis)
                if (result.status == OperationState.FAILED) {
                    mutableState.value = PaymentUiState.Error(result.failureMessage ?: "The Lightning payment failed.")
                    return@launch
                }
                mutableState.value = PaymentUiState.Processing(keytag, result.operationId)
                if (result.status != OperationState.COMPLETED) channels.reconcile(walletId, keytag)
                complete(keytag, result.operationId, confirming.quote)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Payment was not confirmed. Channel reconciliation is required.")
            }
        }
    }

    fun reconcile(keytag: ProtocolKeytag) {
        viewModelScope.launch {
            val collection = channels.snapshots.value[walletId] ?: return@launch
            val pending = collection.channels[keytag.value]?.payments?.pending ?: return@launch
            mutableState.value = PaymentUiState.Processing(keytag, pending.operationId)
            try {
                channels.reconcile(walletId, keytag)
                complete(keytag, pending.operationId, pending.quote)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PaymentUiState.Error("Payment was not confirmed. Channel reconciliation is required.")
            }
        }
    }

    private fun complete(keytag: ProtocolKeytag, operationId: String, quote: PaymentQuote) {
        val receipt = requireNotNull(channels.snapshots.value[walletId]?.channels?.get(keytag.value)
            ?.payments?.receipts?.singleOrNull { it.receipt.operationId == operationId }?.receipt)
        require(receipt.verified && receipt.keytag == keytag && receipt.paymentHash == quote.invoiceHash)
        require(receipt.amount == quote.amount && receipt.fee == quote.routingFee + quote.adaptorFee)
        mutableState.value = PaymentUiState.Complete(receipt)
        clearInvoice()
    }

    private fun failExpired() {
        mutableState.value = PaymentUiState.Error("This BOLT11 invoice has expired. Scan a fresh invoice.")
        clearInvoice()
    }

    fun scanAgain() {
        clearInvoice()
        mutableState.value = PaymentUiState.Scanning
    }

    private fun clearInvoice() {
        invoice = null
        invoiceHash = null
        invoiceAmountMsat = null
        description = null
    }
}

private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
