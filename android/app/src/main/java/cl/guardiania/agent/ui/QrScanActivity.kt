package cl.guardiania.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import cl.guardiania.agent.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lector de QR en vertical.
 *
 * La cámara la maneja CameraX y la decodificación ML Kit con el modelo
 * empaquetado en la app (sin red). Se usa el fotograma completo, así que el
 * marco central es solo una guía visual: el código se detecta igual si queda
 * un poco descentrado.
 */
class QrScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "cl.guardiania.agent.QR_RESULT"
        private const val TAG = "QrScan"
    }

    private lateinit var previewView: PreviewView
    private lateinit var torchButton: TextView
    private lateinit var analyzerExecutor: ExecutorService
    private var scanner: BarcodeScanner? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var delivered = false
    private var loggedFirstFrame = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.qr_scan_no_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        previewView = findViewById(R.id.qr_preview)
        torchButton = findViewById(R.id.qr_torch)
        analyzerExecutor = Executors.newSingleThreadExecutor()

        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )

        findViewById<View>(R.id.qr_close).setOnClickListener { finish() }
        torchButton.setOnClickListener { toggleTorch() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = runCatching { providerFuture.get() }.getOrElse {
                    Log.e(TAG, "No se pudo abrir la cámara", it)
                    finish()
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // 720p basta para leer un QR a distancia de mano y gasta menos
                    // batería que el tamaño máximo del sensor.
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { proxy -> analyze(proxy) }

                runCatching {
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure {
                    Log.e(TAG, "No se pudo enlazar la cámara", it)
                    finish()
                    return@addListener
                }

                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                torchButton.visibility = if (hasFlash) View.VISIBLE else View.GONE
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val client = scanner
        if (media == null || client == null) {
            proxy.close()
            return
        }

        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Log.i(TAG, "Primer fotograma: ${media.width}x${media.height} rotación ${proxy.imageInfo.rotationDegrees}°")
        }

        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        client.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    Log.i(TAG, "ML Kit encontró ${barcodes.size} candidato(s) en el fotograma")
                }
                val raw = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                if (raw != null) deliver(raw)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML Kit falló en el fotograma", e)
                proxy.close()
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun deliver(raw: String) {
        if (delivered) return
        delivered = true
        // No se registra el contenido del QR: puede ser un token de vinculación.
        Log.i(TAG, "QR detectado (${raw.length} caracteres)")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, raw))
        finish()
    }

    private fun toggleTorch() {
        val bound = camera ?: return
        torchOn = !torchOn
        bound.cameraControl.enableTorch(torchOn)
        torchButton.isSelected = torchOn
        torchButton.text = getString(if (torchOn) R.string.qr_scan_torch_off else R.string.qr_scan_torch_on)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { scanner?.close() }
        analyzerExecutor.shutdown()
    }
}
