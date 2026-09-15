package io.riverark.ferret.feature.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.riverark.ferret.core.channel.PaymentUiState
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.feature.wallet.assetName
import io.riverark.ferret.feature.wallet.distinctKeytagSuffixes
import io.riverark.ferret.feature.wallet.formatAsset
import io.riverark.ferret.ui.FerretCard
import io.riverark.ferret.ui.FerretDataBlock
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretStatusChip
import io.riverark.ferret.ui.FerretTopBar

@Composable
fun SelectPaymentChannelScreen(
    state: PaymentUiState.SelectingChannel,
    catalog: AssetCatalog,
    onSelect: (ProtocolKeytag) -> Unit,
    onScanAgain: () -> Unit,
) {
    val sorted = state.choices.sortedBy { it.keytag.value }
    val labels = distinctKeytagSuffixes(sorted.map { it.keytag.value })
    FerretScreen {
        FerretTopBar("Choose payment channel")
        state.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Select the channel that will pay this invoice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm),
        ) {
            items(sorted, key = { it.keytag.value }) { choice ->
                FerretCard(Modifier.fillMaxWidth(), onClick = { onSelect(choice.keytag) }) {
                    FerretDataBlock("Channel", labels.getValue(choice.keytag.value))
                    FerretDataBlock("Asset", assetName(choice.asset, catalog))
                    FerretDataBlock("Spendable capacity", formatAsset(choice.spendable, catalog))
                }
            }
        }
        FerretPrimaryButton("Scan another QR", onScanAgain)
    }
}

@Composable
fun ConfirmPaymentScreen(
    description: String?,
    quote: PaymentQuote,
    selectedCapacity: AssetAmount,
    catalog: AssetCatalog,
    guardComplete: Boolean,
    onConfirm: () -> Unit,
) {
    val total = quote.amount + quote.routingFee + quote.adaptorFee
    val remaining = selectedCapacity - total
    FerretScreen {
        FerretTopBar("Confirm payment")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FerretSpacing.md),
        ) {
            description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("TOTAL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatAsset(total, catalog), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            FerretCard(Modifier.fillMaxWidth()) {
                FerretDataBlock("Channel", quote.keytag.value.takeLast(12))
                FerretDataBlock("Selected capacity", formatAsset(selectedCapacity, catalog))
                FerretDataBlock("Amount", formatAsset(quote.amount, catalog))
                FerretDataBlock("Routing fee", formatAsset(quote.routingFee, catalog))
                FerretDataBlock("Adaptor fee", formatAsset(quote.adaptorFee, catalog))
                FerretDataBlock("Post-payment capacity", formatAsset(remaining, catalog))
                FerretDataBlock("Quote expires", quote.expiresAtEpochMillis.toString())
            }
        }
        FerretPrimaryButton("Pay", onConfirm, enabled = guardComplete)
    }
}

@Composable
fun PaymentReceiptScreen(receipt: Receipt, catalog: AssetCatalog, onDone: () -> Unit) {
    FerretScreen {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FerretStatusChip(if (receipt.verified) "VERIFIED" else "PENDING")
            Text(
                if (receipt.verified) "Payment complete" else "Payment pending verification",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Text(formatAsset(receipt.amount, catalog), style = MaterialTheme.typography.displaySmall)
            FerretCard(Modifier.fillMaxWidth()) {
                FerretDataBlock("Channel", receipt.keytag.value.takeLast(12))
                FerretDataBlock("Operation", receipt.operationId)
                FerretDataBlock("Payment hash", receipt.paymentHash)
                FerretDataBlock("Fees", formatAsset(receipt.fee, catalog))
            }
        }
        FerretPrimaryButton("Done", onDone)
    }
}
