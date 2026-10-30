package cl.guardiania.agent.ui

import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import cl.guardiania.agent.R
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ViewfinderView

/**
 * Lector de QR en vertical. La actividad original de la librería se declara
 * como `sensorLandscape`, por eso giraba la pantalla; aquí se fija retrato en
 * el manifiesto y se usa un marco propio para centrar el código.
 */
class QrScanActivity : CaptureActivity() {

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_qr_scan)
        val barcodeView = findViewById<DecoratedBarcodeView>(R.id.qr_barcode_view)

        // El marco y el texto propios reemplazan la guía de la librería.
        for (index in 0 until barcodeView.childCount) {
            when (barcodeView.getChildAt(index)) {
                is ViewfinderView -> barcodeView.getChildAt(index).visibility = View.GONE
                is TextView -> barcodeView.getChildAt(index).visibility = View.GONE
            }
        }

        findViewById<View>(R.id.qr_close).setOnClickListener { finish() }

        val torch = findViewById<TextView>(R.id.qr_torch)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            torch.visibility = View.GONE
        } else {
            var on = false
            torch.setOnClickListener {
                on = !on
                if (on) barcodeView.setTorchOn() else barcodeView.setTorchOff()
                torch.isSelected = on
                torch.text = getString(if (on) R.string.qr_scan_torch_off else R.string.qr_scan_torch_on)
            }
        }

        return barcodeView
    }
}
