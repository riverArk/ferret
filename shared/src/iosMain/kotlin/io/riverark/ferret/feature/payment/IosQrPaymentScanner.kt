package io.riverark.ferret.feature.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretInk
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretYellow
import platform.UIKit.UIView

interface IosQrScannerEvents {
    fun onInvoice(value: String)
    fun onError()
    fun onPermissionDenied()
}

/** Swift's AVFoundation client owns the capture queue and permissions. */
interface IosQrScanner {
    val view: UIView
    fun start(events: IosQrScannerEvents)
    fun stop()
    fun openSettings()
}

@Composable
fun QrPaymentScannerScreen(scannerFactory: () -> IosQrScanner, onInvoice: (String) -> Unit, onError: () -> Unit) {
    val scanner = remember { scannerFactory() }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val currentInvoice by rememberUpdatedState(onInvoice)
    val currentError by rememberUpdatedState(onError)
    val events = remember(scanner) {
        object : IosQrScannerEvents {
            override fun onInvoice(value: String) = currentInvoice(value)
            override fun onError() { failed = true; currentError() }
            override fun onPermissionDenied() { denied = true }
        }
    }
    DisposableEffect(scanner) {
        scanner.start(events)
        onDispose { scanner.stop() }
    }
    if (denied) {
        FerretScreen {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                FerretErrorState("Camera access is required to scan a BOLT11 QR code. Ferret does not support manual invoice entry.")
            }
            FerretPrimaryButton("Try camera again", {
                denied = false
                scanner.start(events)
            })
            FerretPrimaryButton("Open system settings", scanner::openSettings)
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            UIKitView(factory = { scanner.view }, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, FerretYellow, RoundedCornerShape(24.dp)))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(FerretInk.copy(alpha = 0.9f)).padding(FerretSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FerretSpacing.xs),
            ) {
                Text("Place the BOLT11 QR code inside the frame.", color = Color.White, textAlign = TextAlign.Center)
                if (failed) Text("That QR code could not be read.", color = MaterialTheme.colorScheme.errorContainer)
            }
        }
    }
}
