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
import java.util.concurrent.atomic.AtomicLong
import androidx.lifecycle.Lifecycle

class AndroidQrScanner(private val context: Context) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private var provider: ProcessCameraProvider? = null
    private val accepted = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)

    fun start(owner: LifecycleOwner, previewView: PreviewView, onResult: (String) -> Unit, onError: () -> Unit) {
        check(!closed.get())
        accepted.set(false)
        val token = generation.incrementAndGet()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active(token) || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            try {
                val cameraProvider = future.get()
                if (!active(token)) return@addListener
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null || accepted.get() || !active(token)) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    try {
                        scanner.process(InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes.singleOrNull()?.rawValue
                                if (raw != null && active(token) && accepted.compareAndSet(false, true)) {
                                    cameraProvider.unbindAll()
                                    onResult(raw)
                                }
                            }
                            .addOnFailureListener {
                                if (active(token) && !accepted.get()) onError()
                            }
                            .addOnCompleteListener { proxy.close() }
                    } catch (_: Exception) {
                        proxy.close()
                        if (active(token) && !accepted.get()) onError()
                    }
                }
                if (!active(token) || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                if (active(token)) onError()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        generation.incrementAndGet()
        accepted.set(true)
        provider?.unbindAll()
        provider = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scanner.close()
        executor.shutdownNow()
    }

    private fun active(token: Long) = !closed.get() && generation.get() == token
}
