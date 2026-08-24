package io.riverark.ferret.feature.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.model.Receipt
import io.riverark.ferret.ui.FerretCard
import io.riverark.ferret.ui.FerretDataBlock
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretStatusChip
import io.riverark.ferret.ui.FerretTopBar

@Composable
fun ConfirmPaymentScreen(description: String?, quote: PaymentQuote, guardComplete: Boolean, onConfirm: () -> Unit) {
    val total = quote.amount + quote.routingFee + quote.adaptorFee
    FerretScreen {
        FerretTopBar("Confirm payment")
        description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TOTAL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${total.value} lovelace", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        }
        FerretCard(Modifier.fillMaxWidth()) {
            FerretDataBlock("Amount", "${quote.amount.value} lovelace")
            FerretDataBlock("Routing fee", "${quote.routingFee.value} lovelace")
            FerretDataBlock("Adaptor fee", "${quote.adaptorFee.value} lovelace")
            FerretDataBlock("Quote expires", quote.expiresAtEpochMillis.toString())
        }
        Box(Modifier.weight(1f))
        FerretPrimaryButton("Pay", onConfirm, enabled = guardComplete)
    }
}

@Composable
fun PaymentReceiptScreen(receipt: Receipt, onDone: () -> Unit) {
    FerretScreen {
        Column(
            Modifier.weight(1f).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FerretStatusChip(if (receipt.verified) "VERIFIED" else "PENDING")
            Text(
                if (receipt.verified) "Payment complete" else "Payment pending verification",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Text("${receipt.amount.value} lovelace", style = MaterialTheme.typography.displaySmall)
        }
        FerretCard(Modifier.fillMaxWidth()) {
            FerretDataBlock("Operation", receipt.operationId)
            FerretDataBlock("Payment hash", receipt.paymentHash)
            FerretDataBlock("Fees", "${receipt.fee.value} lovelace")
        }
        FerretPrimaryButton("Done", onDone)
    }
}
