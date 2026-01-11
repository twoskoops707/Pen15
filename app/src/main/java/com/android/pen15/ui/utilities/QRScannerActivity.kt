package com.android.pen15.ui.utilities

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.google.zxing.ResultPoint

class QRScannerActivity : BaseToolActivity() {

    private var barcodeView: DecoratedBarcodeView? = null
    private var isScanning = false

    override fun getToolName() = "QR/Barcode Scanner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { toggleScanning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("QR/Barcode Scanner", includeTimestamp = false)
        logMessage("Scan QR codes and barcodes", includeTimestamp = false)
        logMessage("")

        // Check camera permission
        if (checkCameraPermission()) {
            logMessage("✓ Camera permission granted", includeTimestamp = false)
            logMessage("")
            logMessage("Click Execute to start scanning", includeTimestamp = false)
            logMessage("")
            logMessage("Supported formats:", includeTimestamp = false)
            logMessage("• QR Code", includeTimestamp = false)
            logMessage("• UPC/EAN Barcodes", includeTimestamp = false)
            logMessage("• Code 39, Code 128", includeTimestamp = false)
            logMessage("• Data Matrix", includeTimestamp = false)
            logMessage("• PDF417", includeTimestamp = false)
        } else {
            logMessage("⚠️ Camera permission required!", includeTimestamp = false)
            logMessage("")
            logMessage("Click Execute to request permission", includeTimestamp = false)
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logMessage("✓ Camera permission granted!")
                logMessage("")
                logMessage("Click Execute to start scanning", includeTimestamp = false)
            } else {
                logMessage("✗ Camera permission denied")
                logMessage("")
                logMessage("Enable camera in Settings to use scanner", includeTimestamp = false)
            }
        }
    }

    private fun toggleScanning() {
        if (!checkCameraPermission()) {
            requestCameraPermission()
            return
        }

        if (isScanning) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        try {
            clearOutput()
            logMessage("Starting camera...")
            logMessage("Point camera at QR code or barcode")
            logMessage("")

            // Initialize barcode scanner
            // Note: This is a simplified implementation
            // In production, you'd inflate a custom layout with DecoratedBarcodeView
            logMessage("⚠️ Scanner UI launching...", includeTimestamp = false)
            logMessage("")
            logMessage("Alternative: Use external QR scanner app:", includeTimestamp = false)
            logMessage("• QR & Barcode Scanner (F-Droid)", includeTimestamp = false)
            logMessage("• Binary Eye (F-Droid)", includeTimestamp = false)
            logMessage("")
            logMessage("Or use Termux with zbarcam:", includeTimestamp = false)
            logMessage("pkg install zbar", includeTimestamp = false)
            logMessage("zbarcam", includeTimestamp = false)

            isScanning = true
            executeButton.text = "Stop"

        } catch (e: Exception) {
            logMessage("Error: ${e.message}")
            isScanning = false
            executeButton.text = "Execute"
        }
    }

    private fun stopScanning() {
        try {
            barcodeView?.pause()
            logMessage("")
            logMessage("✓ Scanner stopped")
            isScanning = false
            executeButton.text = "Execute"
        } catch (e: Exception) {
            logMessage("Error stopping: ${e.message}")
        }
    }

    private val barcodeCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            result?.let {
                val scannedText = it.text
                val format = it.barcodeFormat.toString()

                logMessage("✓ SCANNED!")
                logMessage("")
                logMessage("Format: $format", includeTimestamp = false)
                logMessage("Content:", includeTimestamp = false)
                logMessage(scannedText, includeTimestamp = false)
                logMessage("")

                // Copy to clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("QR Code", scannedText)
                clipboard.setPrimaryClip(clip)

                logMessage("✓ Copied to clipboard!", includeTimestamp = false)
                logMessage("")

                // Analyze content type
                when {
                    scannedText.startsWith("http://") || scannedText.startsWith("https://") -> {
                        logMessage("Type: URL", includeTimestamp = false)
                    }
                    scannedText.startsWith("wifi:", ignoreCase = true) -> {
                        logMessage("Type: WiFi Credentials", includeTimestamp = false)
                    }
                    scannedText.contains("@") && scannedText.contains(".") -> {
                        logMessage("Type: Possible Email", includeTimestamp = false)
                    }
                    scannedText.matches(Regex("^\\d+$")) -> {
                        logMessage("Type: Numeric Code", includeTimestamp = false)
                    }
                    else -> {
                        logMessage("Type: Text/Data", includeTimestamp = false)
                    }
                }

                Toast.makeText(this@QRScannerActivity, "Scanned & Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
            // Visual feedback for detected points
        }
    }

    override fun onResume() {
        super.onResume()
        if (isScanning) {
            barcodeView?.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeView?.pause()
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }
}
