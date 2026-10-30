package cl.guardiania.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import cl.guardiania.agent.BuildConfig
import cl.guardiania.agent.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var frames = 0
    private var lastCandidates = 0
    private var lastDumpAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusTicker = object : Runnable {
        override fun run() {
            refocusCenter()
            if (!delivered) mainHandler.postDelayed(this, 2_000)
        }
    }

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
                    // RGBA: permite convertir el fotograma a Bitmap para
                    // diagnosticar y evita rarezas de planos YUV del fabricante.
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
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

                // Algunos equipos no reenfocan solos al acercarse a un QR:
                // se pide enfoque y exposición al centro cada 2 s.
                mainHandler.post(focusTicker)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyze(proxy: ImageProxy) {
        val client = scanner
        if (client == null) {
            proxy.close()
            return
        }

        val bitmap = runCatching { proxy.toBitmap() }.getOrNull()
        val rotation = proxy.imageInfo.rotationDegrees
        proxy.close()
        if (bitmap == null) {
            Log.w(TAG, "Fotograma ilegible")
            return
        }

        frames++
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Log.i(TAG, "Fotograma ${bitmap.width}x${bitmap.height} rotación ${rotation}°")
        }
        if (BuildConfig.QR_DIAGNOSTIC) dumpFrame(bitmap, rotation)

        client.process(InputImage.fromBitmap(bitmap, rotation))
            .addOnSuccessListener { barcodes ->
                lastCandidates = barcodes.size
                if (barcodes.isNotEmpty()) {
                    Log.i(TAG, "ML Kit encontró ${barcodes.size} candidato(s) en el fotograma $frames")
                }
                val raw = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                if (raw != null) deliver(raw)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML Kit falló en el fotograma", e)
            }
            .addOnCompleteListener {
                if (BuildConfig.QR_DIAGNOSTIC && frames % 30 == 0) {
                    Log.i(TAG, "frames=$frames candidatos=$lastCandidates")
                }
            }
    }

    /** Solo en el build de diagnóstico: guarda un fotograma para revisarlo. */
    private fun dumpFrame(bitmap: Bitmap, rotation: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 1500) return
        lastDumpAt = now
        runCatching {
            val file = File(cacheDir, "scan-frame.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.i(TAG, "Fotograma guardado: ${file.absolutePath} (${bitmap.width}x${bitmap.height}, rot=$rotation)")
        }.onFailure { Log.w(TAG, "No se pudo guardar el fotograma", it) }
    }

    private fun deliver(raw: String) {
        if (delivered) return
        delivered = true
        // No se registra el contenido del QR: puede ser un token de vinculación.
        Log.i(TAG, "QR detectado (${raw.length} caracteres)")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, raw))
        finish()
    }

    private fun refocusCenter() {
        val bound = camera ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
        runCatching { bound.cameraControl.startFocusAndMetering(action) }
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
        mainHandler.removeCallbacks(focusTicker)
        runCatching { scanner?.close() }
        analyzerExecutor.shutdown()
    }
}
