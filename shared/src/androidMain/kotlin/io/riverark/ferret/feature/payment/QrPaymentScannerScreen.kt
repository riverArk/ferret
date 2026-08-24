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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun QrPaymentScannerScreen(onInvoice: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val activity = context.activity()
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; requested = true }

    if (granted) {
        val scanner = remember { AndroidQrScanner(context) }
        DisposableEffect(scanner) { onDispose(scanner::close) }
        AndroidView(
            factory = { PreviewView(it).also { view -> scanner.start(owner, view, onInvoice, onError) } },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        val permanentlyDenied = requested && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Camera access is required to scan a BOLT11 QR code. Ferret does not support manual invoice entry.")
            if (permanentlyDenied) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open system settings") }
            } else {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(if (requested) "Try camera again" else "Allow camera") }
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
