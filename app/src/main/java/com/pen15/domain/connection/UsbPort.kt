package com.pen15.domain.connection

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Thin wrapper around a single USB-OTG CDC-ACM port.
 * Owns its own [DataRouter] and IO thread.
 *
 * Designed so the [ConnectionService] can hold one of these per
 * physical device (one for the Flipper, one for the AWOK direct).
 */
class UsbPort(
    private val context: Context,
    val device: UsbDevice,
    val role: Role,
    val scope: CoroutineScope,
) : SerialInputOutputManager.Listener {

    enum class Role { Flipper, Awok }

    companion object {
        private const val TAG = "Pen15Usb"
        private const val ACTION_USB_PERMISSION = "com.pen15.USB_PERMISSION"
        private const val BAUD = 115200
        const val WRITE_TIMEOUT_MS = 1500
    }

    val router = DataRouter("Pen15Usb-$role")

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val ioExec = Executors.newSingleThreadExecutor()
    @Volatile private var openJob: Job? = null

    /**
     * Open the port: request permission if needed, configure 115200 8N1,
     * pulse DTR/RTS, start IO. Suspends until either the port is ready
     * or [timeoutMs] elapses.
     */
    suspend fun open(timeoutMs: Long = 4000L): Result<Unit> = withContext(Dispatchers.IO) {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(device)) {
            requestPermission(um)
            // Wait briefly for the dialog → permission granted intent.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!um.hasPermission(device) && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            if (!um.hasPermission(device)) {
                return@withContext Result.failure(IOException("USB permission denied"))
            }
        }

        val driver: UsbSerialDriver = CdcAcmSerialDriver(device)
        if (driver.ports.isEmpty()) {
            return@withContext Result.failure(IOException("No serial ports on device"))
        }
        val con = um.openDevice(device)
            ?: return@withContext Result.failure(IOException("Failed to open USB device"))
        val p = driver.ports[0]
        try {
            p.open(con)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // CDC-ACM init: bring DTR/RTS high (no low pulse — that signals
            // the FAP to exit bridge mode on Momentum, see audit §5).
            try {
                p.dtr = true
                p.rts = true
            } catch (e: Exception) {
                if (BuildConfigShim.DEBUG) Log.w(TAG, "DTR/RTS set failed: ${e.message}")
            }
            io = SerialInputOutputManager(p, this@UsbPort).also { it.start() }
            connection = con
            port = p
            return@withContext Result.success(Unit)
        } catch (e: IOException) {
            try { p.close() } catch (_: Exception) {}
            try { con.close() } catch (_: Exception) {}
            return@withContext Result.failure(e)
        }
    }

    private fun requestPermission(um: UsbManager) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, role.ordinal,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        um.requestPermission(device, pi)
    }

    fun isOpen(): Boolean = port?.isOpen == true

    fun write(bytes: ByteArray): Boolean {
        return try {
            port?.write(bytes, WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed on $role: ${e.message}")
            false
        }
    }

    fun writeText(s: String) = write(s.toByteArray(Charsets.UTF_8))

    fun close() {
        try { io?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        io = null
        port = null
        connection = null
        ioExec.shutdownNow()
    }

    // ── SerialInputOutputManager.Listener ────────────────────────────
    override fun onNewData(data: ByteArray) {
        val s = String(data, Charsets.UTF_8)
        scope.launch { router.onBytes(s) }
    }

    override fun onRunError(e: Exception) {
        Log.w(TAG, "Serial run error on $role: ${e.message}")
        // Caller observes via state changes; nothing else to do here.
    }
}

/** Tiny shim so the `UsbPort` doesn't need to depend on the generated `BuildConfig`. */
internal object BuildConfigShim {
    const val DEBUG = true
}
