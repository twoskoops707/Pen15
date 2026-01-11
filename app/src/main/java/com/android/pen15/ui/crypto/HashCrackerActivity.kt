package com.android.pen15.ui.crypto

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class HashCrackerActivity : BaseToolActivity() {

    private var toolAvailable = false

    // Popular online wordlist URLs (keep app size small)
    private val wordlistUrls = mapOf(
        "rockyou (14M)" to "https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt",
        "common-passwords (1M)" to "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/10-million-password-list-top-1000000.txt",
        "darkweb2017 (10K)" to "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/darkweb2017-top10000.txt",
        "xato-net (10K)" to "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/xato-net-10-million-passwords-10000.txt"
    )

    override fun getToolName() = "Hash Cracker (Hashcat)"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startCracking() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Hash Cracker (Hashcat)", includeTimestamp = false)
        logMessage("GPU-accelerated password hash cracking", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            toolAvailable = ToolChecker.checkToolAvailable(this@HashCrackerActivity, "hashcat")
            if (!toolAvailable) {
                logMessage("⚠️ hashcat not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("hashcat")}", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ hashcat is available", includeTimestamp = false)
                logMessage("")
                logMessage("--- Online Wordlists Available ---", includeTimestamp = false)
                wordlistUrls.forEach { (name, url) ->
                    logMessage("• $name", includeTimestamp = false)
                }
                logMessage("")
                logMessage("Usage:", includeTimestamp = false)
                logMessage("1. Prepare hash file (e.g., ~/hash.txt)", includeTimestamp = false)
                logMessage("2. Click Execute to download wordlist & crack", includeTimestamp = false)
                logMessage("")
                logMessage("Hash modes:", includeTimestamp = false)
                logMessage("• MD5: -m 0", includeTimestamp = false)
                logMessage("• SHA1: -m 100", includeTimestamp = false)
                logMessage("• SHA256: -m 1400", includeTimestamp = false)
                logMessage("• NTLM: -m 1000", includeTimestamp = false)
            }
        }
    }

    private fun startCracking() {
        if (!toolAvailable) {
            logMessage("ERROR: hashcat not available")
            return
        }

        clearOutput()
        logMessage("Starting hash cracking...")
        logMessage("")

        // Download wordlist
        val wordlistUrl = wordlistUrls["darkweb2017 (10K)"]!!
        logMessage("Downloading wordlist (darkweb2017 top 10K)...")
        logMessage("URL: $wordlistUrl")
        logMessage("")

        executeCommandWithProgress("curl -L -o ~/wordlist.txt '$wordlistUrl'", useTermux = true)

        logMessage("")
        logMessage("Wordlist downloaded: ~/wordlist.txt")
        logMessage("")
        logMessage("Example crack commands:")
        logMessage("")
        logMessage("MD5:", includeTimestamp = false)
        logMessage("hashcat -m 0 -a 0 ~/hash.txt ~/wordlist.txt", includeTimestamp = false)
        logMessage("")
        logMessage("SHA256:", includeTimestamp = false)
        logMessage("hashcat -m 1400 -a 0 ~/hash.txt ~/wordlist.txt", includeTimestamp = false)
        logMessage("")
        logMessage("NTLM:", includeTimestamp = false)
        logMessage("hashcat -m 1000 -a 0 ~/hash.txt ~/wordlist.txt", includeTimestamp = false)
        logMessage("")
        logMessage("⚠️ Replace ~/hash.txt with your hash file", includeTimestamp = false)
        logMessage("")
        logMessage("More wordlists available at:", includeTimestamp = false)
        logMessage("https://github.com/danielmiessler/SecLists", includeTimestamp = false)
    }
}
