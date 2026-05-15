package com.pen15.data.storage

import android.content.Context
import java.io.File

/**
 * Single owner of all on-device file paths used by Pen15.
 * Everything lives under the app's external files dir so it's
 * accessible to the user (e.g. via USB-MTP) without needing
 * MANAGE_EXTERNAL_STORAGE.
 */
object StorageManager {

    private lateinit var root: File

    fun init(ctx: Context) {
        root = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        listOf("captures", "hashes", "wordlists", "engagements", "logs").forEach {
            File(root, it).mkdirs()
        }
    }

    fun captures(): File   = File(root, "captures").also { it.mkdirs() }
    fun hashes(): File     = File(root, "hashes").also   { it.mkdirs() }
    fun wordlists(): File  = File(root, "wordlists").also{ it.mkdirs() }
    fun engagements(): File= File(root, "engagements").also{ it.mkdirs() }
    fun logs(): File       = File(root, "logs").also     { it.mkdirs() }

    fun engagement(id: String): File = File(engagements(), id).also { it.mkdirs() }
}
