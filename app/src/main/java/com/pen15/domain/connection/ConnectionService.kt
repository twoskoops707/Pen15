package com.pen15.domain.connection

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pen15.R
import com.pen15.domain.flipper.FapClient
import com.pen15.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns:
 *   - the USB permission receiver and attach intent,
 *   - the Flipper [UsbPort] and its [FapClient],
 *   - the AWOK direct [UsbPort] (when present),
 *   - the `loader open "Pen15 Controller"` + JSON ping handshake.
 *
 * Exposes [state] as the single source of truth that every screen
 * observes. Replaces the broken three-singleton model documented in
 * `docs/AUDIT_REPORT.md` §10.2.
 */
class ConnectionService : Service() {

    companion object {
        const val CHANNEL_ID = "pen15.connection"
        private const val NOTIF_ID = 0x1505
        private const val TAG = "Pen15Conn"
        private const val ACTION_USB_PERMISSION = "com.pen15.USB_PERMISSION"

        private const val FLIPPER_VID = 0x0483
        private val ESP32_VIDS = setOf(0x10C4, 0x1A86, 0x303A, 0x0403)

        @Volatile private var instanceState: MutableStateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Idle)

        /** Public state observable from anywhere (UI, viewmodels, ops). */
        val state: StateFlow<ConnectionState> get() = instanceState.asStateFlow()

        @Volatile var flipper: FapClient? = null
            private set
        @Volatile var awok: UsbPort? = null
            private set

        fun ensureStarted(ctx: Context) {
            val intent = Intent(ctx, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flipperPort: UsbPort? = null
    private var flipperClient: FapClient? = null
    private var awokPort: UsbPort? = null
    private var connectJob: Job? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                ACTION_USB_PERMISSION -> {
                    scope.launch { rescan() }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        registerUsbReceiver()
        scope.launch { rescan() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { rescan() }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        flipperPort?.close()
        awokPort?.close()
        scope.cancel()
        flipper = null
        awok = null
        super.onDestroy()
    }

    // ── Rescan / connect ─────────────────────────────────────────────

    private suspend fun rescan() {
        instanceState.value = ConnectionState.Searching("Flipper / AWOK")
        val um = getSystemService(USB_SERVICE) as UsbManager
        val devices = um.deviceList.values.toList()

        // Identify candidate devices.
        val flipperDevice = devices.firstOrNull { it.vendorId == FLIPPER_VID }
            ?: devices.firstOrNull { it.vendorId !in ESP32_VIDS && hasCdc(it) }
        val awokDevice = devices.firstOrNull { it.vendorId in ESP32_VIDS }

        // Open Flipper if present and not already open.
        if (flipperDevice != null && (flipperPort?.device?.deviceId != flipperDevice.deviceId || flipperPort?.isOpen() != true)) {
            openFlipper(flipperDevice)
        } else if (flipperDevice == null && flipperPort != null) {
            flipperPort?.close(); flipperPort = null; flipperClient = null; flipper = null
        }

        // Open AWOK if present.
        if (awokDevice != null && (awokPort?.device?.deviceId != awokDevice.deviceId || awokPort?.isOpen() != true)) {
            openAwok(awokDevice)
        } else if (awokDevice == null && awokPort != null) {
            awokPort?.close(); awokPort = null; awok = null
        }

        recomputeState()
    }

    private suspend fun openFlipper(device: UsbDevice) {
        val port = UsbPort(this, device, UsbPort.Role.Flipper, scope)
        flipperPort = port
        val open = port.open()
        if (open.isFailure) {
            instanceState.value = ConnectionState.Error(
                "Flipper plugged in but couldn't open port: ${open.exceptionOrNull()?.message}",
            )
            return
        }
        val client = FapClient(port, scope)
        flipperClient = client
        flipper = client
        // Auto-launch the FAP and ping. Audit §3 fix.
        launchFapAndPing(client)
    }

    private suspend fun launchFapAndPing(client: FapClient) {
        // 1. CLI mode: try to open the FAP via Flipper's loader CLI.
        client.port.writeText("\r\n")
        delay(80)
        client.port.writeText("loader open \"Pen15 Controller\"\r\n")
        // 2. Wait for FAP to settle.
        delay(1500)
        // 3. Ping with retries.
        repeat(3) { attempt ->
            val ok = client.ping()
            if (ok) {
                instanceState.value = computeReadyState(flipperFw = "Pen15 FAP")
                return
            }
            delay(900)
        }
        instanceState.value = ConnectionState.Error(
            "Flipper is plugged in, but the Pen15 app on the Flipper isn't answering. Open Apps → Tools → Pen15 Controller on the Flipper, then come back here.",
        )
    }

    private suspend fun openAwok(device: UsbDevice) {
        val port = UsbPort(this, device, UsbPort.Role.Awok, scope)
        awokPort = port
        val open = port.open()
        if (open.isFailure) {
            Log.w(TAG, "AWOK open failed: ${open.exceptionOrNull()?.message}")
            return
        }
        // AWOK is a transparent serial — we don't ping it, just hold the
        // port open and route output to whoever subscribes.
        awok = port
        recomputeState()
    }

    private fun recomputeState() {
        val curr = instanceState.value
        // Don't clobber an Error that the user still needs to see, except
        // when hardware actually came back.
        val flipperUp = flipperClient?.isReady == true
        val awokUp = awokPort?.isOpen() == true
        val next = when {
            flipperUp && awokUp -> ConnectionState.Both("Pen15 FAP", awokChip(awokPort?.device))
            flipperUp           -> ConnectionState.FlipperOnly("Pen15 FAP")
            awokUp              -> ConnectionState.AwokOnly(awokChip(awokPort?.device))
            curr is ConnectionState.Error -> curr
            else                -> ConnectionState.Idle
        }
        if (next.message != curr.message || next::class != curr::class) {
            instanceState.value = next
        }
    }

    private fun computeReadyState(flipperFw: String): ConnectionState {
        val awokUp = awokPort?.isOpen() == true
        return if (awokUp) ConnectionState.Both(flipperFw, awokChip(awokPort?.device))
        else ConnectionState.FlipperOnly(flipperFw)
    }

    private fun awokChip(d: UsbDevice?): String = when (d?.vendorId) {
        0x10C4 -> "CP210x"
        0x1A86 -> "CH340"
        0x303A -> "ESP32-USB"
        0x0403 -> "FTDI"
        else   -> "AWOK"
    }

    private fun hasCdc(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_COMM) return true
        }
        return false
    }

    // ── Foreground notification ──────────────────────────────────────

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.connection_notification_title))
            .setContentText(getString(R.string.connection_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }
}
