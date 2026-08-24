package io.riverark.ferret.feature.payment

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AndroidQrScanner(private val context: Context) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private var provider: ProcessCameraProvider? = null
    private val accepted = AtomicBoolean(false)

    fun start(owner: LifecycleOwner, previewView: PreviewView, onResult: (String) -> Unit, onError: () -> Unit) {
        accepted.set(false)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null || accepted.get()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { barcodes ->
                            val raw = barcodes.singleOrNull()?.rawValue
                            if (raw != null && accepted.compareAndSet(false, true)) {
                                cameraProvider.unbindAll()
                                onResult(raw)
                            }
                        }
                        .addOnFailureListener { if (!accepted.get()) onError() }
                        .addOnCompleteListener { proxy.close() }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { onError() }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() { provider?.unbindAll() }
    override fun close() { stop(); scanner.close(); executor.shutdownNow() }
}
