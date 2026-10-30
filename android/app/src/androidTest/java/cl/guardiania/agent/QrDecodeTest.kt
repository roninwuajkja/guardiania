package cl.guardiania.agent

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Comprueba que el modelo empaquetado de ML Kit decodifica un QR en este
 * dispositivo, sin cámara ni red. Es la parte que el escáner usa después de
 * recibir cada fotograma de CameraX.
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeTest {

    @Test
    fun mlKitDecodesGeneratedQr() {
        val payload = "guardiania://pair?v=1&t=prueba-instrumentada"
        val bitmap = renderQr(payload)

        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )

        try {
            val result = Tasks.await(
                scanner.process(InputImage.fromBitmap(bitmap, 0)),
                30,
                TimeUnit.SECONDS,
            )
            assertEquals(payload, result.firstOrNull()?.rawValue)
        } finally {
            scanner.close()
        }
    }

    private fun renderQr(text: String): Bitmap {
        val size = 640
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}
