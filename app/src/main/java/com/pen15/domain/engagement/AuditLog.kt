package com.pen15.domain.engagement

import com.pen15.data.storage.StorageManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only JSONL audit log per engagement.
 * Every disruptive call passes through here so we can produce a
 * client-ready zip at the end of an engagement.
 */
object AuditLog {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    fun log(engagementId: String, action: String, target: String?, details: Map<String, Any?> = emptyMap()) {
        val dir = StorageManager.engagement(engagementId)
        val file = File(dir, "audit.jsonl")
        val obj = JSONObject().apply {
            put("ts", iso.format(Date()))
            put("action", action)
            if (target != null) put("target", target)
            details.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
        }
        file.appendText(obj.toString() + "\n")
    }
}
