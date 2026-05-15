package com.pen15.domain.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Thin Termux RUN_COMMAND launcher.
 *
 * The honest part: most heavy crack jobs run inside Termux. The
 * frustrating part: Termux requires `allow-external-apps=true` in
 * `~/.termux/termux.properties` before RUN_COMMAND works. We detect
 * that, surface a one-screen fix, and otherwise stay out of the way.
 *
 * Audit §9 fix: no monitor-mode commands here. WiFi capture is the
 * AWOK's job. Termux is for cracking the captured pcap, identifying
 * hashes, running nmap, and OSINT scripts. Nothing else.
 */
object TermuxRunner {

    private const val TERMUX_PKG    = "com.termux"
    private const val RUN_ACTION    = "com.termux.RUN_COMMAND"
    private const val BASH          = "/data/data/com.termux/files/usr/bin/bash"

    enum class Status { Ready, MissingApp, NeedsAllowExternalApps, Unknown }

    fun status(ctx: Context): Status {
        val pm = ctx.packageManager
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PKG, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PKG, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return Status.MissingApp
        // We can't introspect ~/.termux/termux.properties from outside
        // Termux. The first run command attempt will tell us.
        return Status.Unknown
    }

    /**
     * Fire-and-forget command in Termux. Caller writes output to a
     * file and tails it back via the [tail] helper.
     */
    fun run(
        ctx: Context,
        command: String,
        background: Boolean = true,
        workDir: String = "/data/data/com.termux/files/home",
    ): Boolean {
        return try {
            val intent = Intent(RUN_ACTION).apply {
                setPackage(TERMUX_PKG)
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Two-line snippet the user can paste in Termux to enable RUN_COMMAND. */
    val allowExternalAppsSnippet: String = """
        mkdir -p ~/.termux && \
          printf 'allow-external-apps = true\n' >> ~/.termux/termux.properties
        termux-reload-settings
    """.trimIndent()
}
