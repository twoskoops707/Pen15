package com.android.pen15.ui.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class BluetoothScannerActivity : BaseToolActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false
    private val foundDevices = mutableSetOf<String>()

    override fun getToolName() = "Bluetooth Scanner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startScan() }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (ActivityCompat.checkSelfPermission(
                                this@BluetoothScannerActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val deviceInfo = "${it.name ?: "Unknown"} - ${it.address}"
                            if (foundDevices.add(deviceInfo)) {
                                logMessage("Found: $deviceInfo")
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    logMessage("")
                    logMessage("✓ Classic Bluetooth scan complete!")
                    logMessage("Found ${foundDevices.size} device(s)")
                    isScanning = false
                    executeButton.text = "Execute"
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                if (ActivityCompat.checkSelfPermission(
                        this@BluetoothScannerActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val deviceInfo = "${device.name ?: "Unknown BLE"} - ${device.address}"
                    if (foundDevices.add(deviceInfo)) {
                        logMessage("Found BLE: $deviceInfo")
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logMessage("BLE scan failed with error: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Bluetooth Scanner", includeTimestamp = false)
        logMessage("Scan for nearby Bluetooth devices", includeTimestamp = false)
        logMessage("")

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            logMessage("⚠️ Bluetooth not supported on this device!", includeTimestamp = false)
            executeButton.isEnabled = false
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            logMessage("⚠️ Bluetooth is disabled!", includeTimestamp = false)
            logMessage("")
            logMessage("Enable Bluetooth in Settings to scan", includeTimestamp = false)
            executeButton.isEnabled = false
            return
        }

        // Check permissions
        val hasPermissions = checkBluetoothPermissions()
        if (!hasPermissions) {
            logMessage("⚠️ Bluetooth permissions required!", includeTimestamp = false)
            logMessage("")
            logMessage("Click Execute to request permissions", includeTimestamp = false)
        } else {
            logMessage("✓ Bluetooth ready", includeTimestamp = false)
            logMessage("")
            logMessage("Scan methods:", includeTimestamp = false)
            logMessage("• Native Android BLE scanner", includeTimestamp = false)
            logMessage("• Classic Bluetooth discovery", includeTimestamp = false)
            logMessage("")
            logMessage("Click Execute to start scanning", includeTimestamp = false)
        }

        // Register receiver for classic Bluetooth
        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        ActivityCompat.requestPermissions(this, permissions, 200)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 200) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                logMessage("✓ Permissions granted!")
                logMessage("")
                logMessage("Click Execute to scan", includeTimestamp = false)
            } else {
                logMessage("✗ Permissions denied")
                logMessage("")
                logMessage("Grant Bluetooth permissions in Settings", includeTimestamp = false)
            }
        }
    }

    private fun startScan() {
        if (bluetoothAdapter == null) {
            logMessage("ERROR: Bluetooth not available")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            logMessage("ERROR: Bluetooth is disabled")
            logMessage("Enable Bluetooth and try again")
            return
        }

        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        if (isScanning) {
            stopScan()
            return
        }

        clearOutput()
        foundDevices.clear()
        isScanning = true
        executeButton.text = "Stop"

        logMessage("Starting Bluetooth scan...")
        logMessage("")

        // Start Classic Bluetooth discovery
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            logMessage("--- Classic Bluetooth Scan ---")
            if (bluetoothAdapter!!.startDiscovery()) {
                logMessage("Scanning for classic Bluetooth devices...")
            } else {
                logMessage("Failed to start classic scan")
            }

            // Start BLE scan
            logMessage("")
            logMessage("--- BLE (Low Energy) Scan ---")
            bluetoothAdapter?.bluetoothLeScanner?.startScan(bleScanCallback)
            logMessage("Scanning for BLE devices...")
            logMessage("")
        } else {
            logMessage("ERROR: Missing BLUETOOTH_SCAN permission")
            isScanning = false
            executeButton.text = "Execute"
        }
    }

    private fun stopScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter?.cancelDiscovery()
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        }

        logMessage("")
        logMessage("✓ Scan stopped")
        logMessage("Total devices found: ${foundDevices.size}")
        isScanning = false
        executeButton.text = "Execute"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter?.cancelDiscovery()
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
            }
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }
}
