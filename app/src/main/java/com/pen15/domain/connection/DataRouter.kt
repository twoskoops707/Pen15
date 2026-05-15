package com.pen15.domain.connection

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-port byte router.
 *
 * Fixes audit bug #2: USB CDC delivers data in 64-byte chunks, JSON
 * responses can be 1 KB+. This buffers across callbacks and only emits
 * complete `\n`-terminated frames.
 *
 * Two output paths:
 *   - jsonFrames: completed lines that successfully parsed as JSON.
 *   - rawFrames:  raw bytes for the bridge / scan-output consumer.
 *                  Both paths see every line so consumers can choose.
 */
class DataRouter(private val tag: String) {

    private val buffer = StringBuilder()

    private val _jsonFrames = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val jsonFrames: SharedFlow<JSONObject> = _jsonFrames.asSharedFlow()

    private val _rawLines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val rawLines: SharedFlow<String> = _rawLines.asSharedFlow()

    private val mode = AtomicReference(Mode.Json)

    enum class Mode { Json, Bridge }

    fun setMode(m: Mode) { mode.set(m) }
    fun mode(): Mode = mode.get()

    /** Called from the IO thread on every CDC chunk. */
    suspend fun onBytes(chunk: String) {
        buffer.append(chunk)
        // Stitch + flush all complete lines.
        var nl = buffer.indexOf('\n')
        while (nl >= 0) {
            val line = buffer.substring(0, nl).trimEnd('\r')
            buffer.delete(0, nl + 1)
            if (line.isNotEmpty()) emit(line)
            nl = buffer.indexOf('\n')
        }
        // Hard cap to stop runaway buffer if the remote is misbehaving.
        if (buffer.length > 16 * 1024) {
            Log.w(tag, "DataRouter buffer overflow, resetting")
            buffer.clear()
        }
    }

    private suspend fun emit(line: String) {
        _rawLines.emit(line)
        if (mode.get() == Mode.Json && line.startsWith("{")) {
            try {
                _jsonFrames.emit(JSONObject(line))
            } catch (_: JSONException) {
                // Not a JSON frame after all — already delivered as raw.
            }
        }
    }

    fun reset() {
        buffer.clear()
        mode.set(Mode.Json)
    }
}
