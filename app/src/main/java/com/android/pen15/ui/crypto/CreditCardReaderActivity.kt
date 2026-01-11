package com.android.pen15.ui.crypto

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class CreditCardReaderActivity : BaseToolActivity() {

    override fun getToolName() = "NFC Card Reader"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { readCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("NFC Card Reader (Flipper Zero)", includeTimestamp = false)
        logMessage("Read EMV credit/debit cards via NFC", includeTimestamp = false)
        logMessage("")
        logMessage("⚠️ Educational/Security Testing Only", includeTimestamp = false)
        logMessage("⚠️ Only read your own cards!", includeTimestamp = false)
        logMessage("")
        logMessage("Readable data:", includeTimestamp = false)
        logMessage("• Card number (partial)", includeTimestamp = false)
        logMessage("• Expiry date", includeTimestamp = false)
        logMessage("• Cardholder name", includeTimestamp = false)
        logMessage("• Transaction history", includeTimestamp = false)
        logMessage("")
        logMessage("⚠️ Note: CVV/CVC not readable via NFC", includeTimestamp = false)
        logMessage("")

        // Check Flipper connection
        lifecycleScope.launch {
            val flipperConnected = checkFlipperConnection()
            if (!flipperConnected) {
                logMessage("⚠️ Flipper Zero not connected!", includeTimestamp = false)
                logMessage("")
                logMessage("Connect via USB and retry", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ Flipper Zero connected", includeTimestamp = false)
                logMessage("")
                logMessage("Place card near Flipper's NFC reader", includeTimestamp = false)
            }
        }
    }

    private fun readCard() {
        clearOutput()
        logMessage("Starting NFC card read...")
        logMessage("")
        logMessage("Initializing NFC module...")

        lifecycleScope.launch {
            // Start NFC detection
            logMessage("Polling for NFC cards...")
            logMessage("Place card near Flipper Zero...")
            logMessage("")

            var response = sendFlipperCommand("nfc detect")
            logMessage(response)

            if (response.contains("Found") || response.contains("ISO")) {
                logMessage("")
                logMessage("Card detected! Reading EMV data...")
                logMessage("")

                response = sendFlipperCommand("nfc emv")
                logMessage(response)

                logMessage("")
                logMessage("✓ Read complete!")
                logMessage("")
                logMessage("EMV data displayed above", includeTimestamp = false)
            } else {
                logMessage("")
                logMessage("No card detected. Try again.", includeTimestamp = false)
            }
        }
    }
}
