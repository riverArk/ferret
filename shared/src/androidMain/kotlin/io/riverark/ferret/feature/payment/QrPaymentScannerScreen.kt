package io.riverark.ferret.feature.payment

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ferret.shared.generated.resources.Res
import ferret.shared.generated.resources.restore
import ferret.shared.generated.resources.settings
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretInk
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretYellow
import org.jetbrains.compose.resources.painterResource

@Composable
fun QrPaymentScannerScreen(onInvoice: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val activity = context.activity()
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; requested = true }

    if (granted) {
        val scanner = remember { AndroidQrScanner(context) }
        DisposableEffect(scanner) { onDispose(scanner::close) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { PreviewView(it).also { view -> scanner.start(owner, view, onInvoice) { scanFailed = true; onError() } } },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.Center).size(260.dp).border(3.dp, FerretYellow, RoundedCornerShape(24.dp)),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(FerretInk.copy(alpha = 0.9f)).padding(FerretSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FerretSpacing.xs),
            ) {
                Text("Place the BOLT11 QR code inside the frame.", color = Color.White, textAlign = TextAlign.Center)
                if (scanFailed) Text("That QR code could not be read.", color = MaterialTheme.colorScheme.errorContainer)
            }
        }
    } else {
        val permanentlyDenied = requested && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        FerretScreen {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                FerretErrorState("Camera access is required to scan a BOLT11 QR code. Ferret does not support manual invoice entry.")
            }
            if (permanentlyDenied) {
                FerretPrimaryButton(
                    "Open system settings",
                    { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                    icon = { Icon(painterResource(Res.drawable.settings), "Open system settings") },
                )
            } else {
                FerretPrimaryButton(
                    if (requested) "Try camera again" else "Allow camera",
                    { launcher.launch(Manifest.permission.CAMERA) },
                    icon = { Icon(painterResource(Res.drawable.restore), "Retry camera permission") },
                )
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
