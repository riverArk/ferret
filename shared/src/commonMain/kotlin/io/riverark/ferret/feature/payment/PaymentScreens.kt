package io.riverark.ferret.feature.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.model.Receipt

@Composable
fun ConfirmPaymentScreen(description: String?, quote: PaymentQuote, guardComplete: Boolean, onConfirm: () -> Unit) {
    val total = quote.amount + quote.routingFee + quote.adaptorFee
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Confirm payment")
        description?.let { Text(it) }
        Text("Amount: ${quote.amount.value} lovelace")
        Text("Routing fee: ${quote.routingFee.value} lovelace")
        Text("Adaptor fee: ${quote.adaptorFee.value} lovelace")
        Text("Total: ${total.value} lovelace")
        Text("Quote expires at ${quote.expiresAtEpochMillis}")
        Button(onClick = onConfirm, enabled = guardComplete) { Text("Pay") }
    }
}

@Composable
fun PaymentReceiptScreen(receipt: Receipt, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (receipt.verified) "Payment verified" else "Payment pending verification")
        Text("Operation: ${receipt.operationId}")
        Text("Payment hash: ${receipt.paymentHash.take(12)}…")
        Text("Amount: ${receipt.amount.value} lovelace")
        Text("Fees: ${receipt.fee.value} lovelace")
        Button(onClick = onDone) { Text("Done") }
    }
}
