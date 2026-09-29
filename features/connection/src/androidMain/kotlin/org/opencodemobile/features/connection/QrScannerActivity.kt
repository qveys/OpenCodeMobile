package org.opencodemobile.features.connection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR capture surface behind [QrCodeScanner] on Android
 * (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * It owns the platform camera stack so neither `commonMain` nor the app shell
 * needs CameraX/ML Kit: the `AndroidQrCodeScanner` port implementation starts
 * this activity and observes its result. The camera permission is requested at
 * the point of use — when the activity is created — never at app startup.
 *
 * The activity never interprets the decoded text; it returns it verbatim in
 * [EXTRA_PAYLOAD], exactly like a deep link would, so `ServerImportLink` stays
 * the single parser.
 */
public class QrScannerActivity : ComponentActivity() {

    private val showPreview = mutableStateOf(false)

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showPreview.value = true
            } else {
                finishCancelled()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showPreview.value = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            if (showPreview.value) {
                QrScannerContent(
                    lifecycleOwner = this,
                    onPayload = ::finishWithPayload,
                    onCancel = ::finishCancelled,
                )
            }
        }
    }

    private fun finishWithPayload(payload: String) {
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, payload))
            finish()
        }
    }

    private fun finishCancelled() {
        runOnUiThread {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    public companion object {
        /** Result extra carrying the decoded text payload. */
        public const val EXTRA_PAYLOAD: String = "org.opencodemobile.features.connection.EXTRA_QR_PAYLOAD"

        /** Intent that opens the capture surface. */
        public fun intent(context: Context): Intent = Intent(context, QrScannerActivity::class.java)
    }
}

@Composable
private fun QrScannerContent(
    lifecycleOwner: LifecycleOwner,
    onPayload: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor: Executor = remember { Executors.newSingleThreadExecutor() }
    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    decodeQrCode(imageProxy, scanner) { payload ->
                        if (delivered.compareAndSet(false, true)) {
                            onPayload(payload)
                        }
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            },
            context.mainExecutor,
        )

        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            Text("Cancel")
        }
    }
}

/**
 * Runs one ML Kit pass over [imageProxy] and reports the first decoded QR
 * payload. The proxy is always closed, including when ML Kit fails.
 */
private fun decodeQrCode(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    onPayload: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val payload = barcodes.firstNotNullOfOrNull { it.rawValue }
            if (payload != null) onPayload(payload)
        }
        .addOnCompleteListener { imageProxy.close() }
}
