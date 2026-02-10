package com.android.pen15.tools

import android.content.Context
import android.content.Intent

object TermuxHelper {

    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"

    fun runCommand(context: Context, command: String, background: Boolean = true) {
        val intent = Intent(TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
