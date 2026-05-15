package com.pen15.domain.flipper

import android.util.Log
import com.pen15.domain.connection.DataRouter
import com.pen15.domain.connection.UsbPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed coroutine-friendly wrapper around the JSON protocol exposed by
 * the `pen15_controller.fap`.
 *
 * Replaces the old `FapProtocol` singleton that:
 *   - relied on `Handler.postDelayed`,
 *   - leaked callbacks across activities,
 *   - dropped chunked CDC frames.
 *
 * Every operation is a `suspend` function returning a `JSONObject` or
 * `null` on timeout. Callers typically wrap it in `Result.runCatching`.
 */
class FapClient(
    val port: UsbPort,
    private val scope: CoroutineScope,
) {

    @Volatile var isReady: Boolean = false
        private set

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JSONObject>>()
    private val pumpJob: Job

    init {
        pumpJob = scope.launch {
            port.router.jsonFrames.collect { json ->
                val id = json.optString("id")
                if (id.isNotEmpty()) {
                    pending.remove(id)?.complete(json)
                }
            }
        }
    }

    suspend fun ping(timeoutMs: Long = 1500L): Boolean {
        val res = call("ping", emptyMap(), timeoutMs)
        val ok = res?.optString("status") == "ok"
        if (ok) isReady = true
        return ok
    }

    suspend fun rfidRead(timeoutMs: Long = 30_000L): Pair<String, String>? {
        val res = call("rfid_read", emptyMap(), timeoutMs) ?: return null
        if (res.optString("status") != "ok") return null
        return res.optString("type") to res.optString("data")
    }

    suspend fun rfidEmulate(type: String, data: String, timeoutMs: Long = 10_000L): Boolean {
        val res = call("rfid_emulate", mapOf("type" to type, "data" to data), timeoutMs)
        return res?.optString("status") == "ok"
    }

    suspend fun nfcDetect(timeoutMs: Long = 30_000L): Pair<String, String>? {
        val res = call("nfc_detect", emptyMap(), timeoutMs) ?: return null
        if (res.optString("status") != "ok") return null
        return res.optString("type") to res.optString("uid")
    }

    suspend fun ikeyRead(timeoutMs: Long = 30_000L): Pair<String, String>? {
        val res = call("ikey_read", emptyMap(), timeoutMs) ?: return null
        if (res.optString("status") != "ok") return null
        return res.optString("type") to res.optString("data")
    }

    suspend fun irRx(timeoutMs: Long = 30_000L): Triple<String, Long, Long>? {
        val res = call("ir_rx", emptyMap(), timeoutMs) ?: return null
        if (res.optString("status") != "ok") return null
        return Triple(res.optString("protocol"), res.optLong("address"), res.optLong("command"))
    }

    suspend fun irTx(protocol: String, address: Long, command: Long): Boolean {
        val res = call("ir_tx", mapOf("protocol" to protocol, "address" to address, "command" to command), 5000L)
        return res?.optString("status") == "ok"
    }

    suspend fun subghzRx(freqHz: Long, timeoutMs: Long = 30_000L): Int {
        val res = call("subghz_rx", mapOf("freq" to freqHz), timeoutMs) ?: return 0
        return if (res.optString("status") == "ok") res.optInt("count", 0) else 0
    }

    suspend fun subghzRecord(freqHz: Long, timeoutMs: Long = 30_000L): Pair<String, Int>? {
        val res = call("subghz_record", mapOf("freq" to freqHz), timeoutMs) ?: return null
        if (res.optString("status") != "ok") return null
        return res.optString("timings") to res.optInt("count")
    }

    suspend fun subghzTxRaw(freqHz: Long, timings: String, repeat: Int): Boolean {
        val res = call("subghz_tx_raw", mapOf("freq" to freqHz, "timings" to timings, "repeat" to repeat), 10_000L)
        return res?.optString("status") == "ok"
    }

    suspend fun gpioMode(pin: Int, output: Boolean): Boolean {
        val res = call("gpio_mode", mapOf("pin" to pin, "mode" to if (output) "output" else "input"), 3000L)
        return res?.optString("status") == "ok"
    }

    suspend fun gpioWrite(pin: Int, value: Int): Boolean {
        val res = call("gpio_write", mapOf("pin" to pin, "value" to value), 3000L)
        return res?.optString("status") == "ok"
    }

    suspend fun gpioRead(pin: Int): Int {
        val res = call("gpio_read", mapOf("pin" to pin), 3000L) ?: return -1
        return res.optInt("value", -1)
    }

    suspend fun uartInit(baud: Int = 115200): Boolean {
        val res = call("uart_init", mapOf("baud" to baud), 5000L)
        return res?.optString("status") == "ok"
    }

    suspend fun uartSend(data: String, timeoutMs: Long = 5000L): String {
        val res = call("uart_send", mapOf("data" to data), timeoutMs) ?: return ""
        return res.optString("uart_rx", "")
    }

    fun hwStop() {
        port.writeText(JSONObject().apply {
            put("action", "hw_stop")
            put("id", nextId())
        }.toString() + "\n")
    }

    /** Subscribe to the underlying raw frame stream (for bridge mode). */
    val rawFrames: SharedFlow<String> get() = port.router.rawLines

    fun setRouterMode(m: DataRouter.Mode) = port.router.setMode(m)

    // ── Low-level dispatch ───────────────────────────────────────────

    private suspend fun call(action: String, params: Map<String, Any>, timeoutMs: Long): JSONObject? {
        if (!port.isOpen()) return null
        val id = nextId()
        val obj = JSONObject().apply {
            put("action", action)
            put("id", id)
            params.forEach { (k, v) -> put(k, v) }
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        pending[id] = deferred
        port.writeText(obj.toString() + "\n")
        return withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
            pending.remove(id)
        }
    }

    private fun nextId(): String = UUID.randomUUID().toString().take(8)
}
