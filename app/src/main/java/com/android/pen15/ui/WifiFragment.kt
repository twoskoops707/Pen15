package com.android.pen15.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.android.pen15.MainActivity
import com.android.pen15.databinding.FragmentWifiBinding
import com.android.pen15.model.AppState
import com.android.pen15.serial.FlipperSerial
import com.android.pen15.tools.TermuxHelper

class WifiFragment : Fragment() {

    private var _binding: FragmentWifiBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private val autoHideRunnables = mutableMapOf<TextView, Runnable>()
    private val streamBuffers = mutableMapOf<TextView, StringBuilder>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        try {
            _binding = FragmentWifiBinding.inflate(inflater, container, false)
            return binding.root
        } catch (e: Exception) {
            val tv = TextView(requireContext()).apply {
                text = "WIFI INFLATE ERROR:\n${e.message}\n\n${e.stackTraceToString()}"
                setTextColor(0xFFFF0000.toInt())
                textSize = 10f
                setPadding(16, 16, 16, 16)
            }
            return tv
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (_binding == null) return

        setupSections()
        setupScan()
        setupAttack()
        setupCapture()
        setupCrack()
        setupRecon()
        setupBluetooth()
        setupObservers()
    }

    private fun setupSections() {
        wireSection(binding.headerScan, binding.contentScan, binding.arrowScan)
        wireSection(binding.headerAttack, binding.contentAttack, binding.arrowAttack)
        wireSection(binding.headerCapture, binding.contentCapture, binding.arrowCapture)
        wireSection(binding.headerCrack, binding.contentCrack, binding.arrowCrack)
        wireSection(binding.headerRecon, binding.contentRecon, binding.arrowRecon)
        wireSection(binding.headerBluetooth, binding.contentBluetooth, binding.arrowBluetooth)
    }

    private fun wireSection(header: View, content: View, arrow: View) {
        header.setOnClickListener {
            if (content.visibility == View.VISIBLE) {
                content.visibility = View.GONE
                arrow.animate().rotation(0f).setDuration(200).start()
            } else {
                content.visibility = View.VISIBLE
                arrow.animate().rotation(180f).setDuration(200).start()
            }
        }
    }

    private fun setupScan() {
        binding.btnScanAp.setOnClickListener { send("scanap") }
        binding.btnScanSta.setOnClickListener { send("scansta") }
        binding.btnListAp.setOnClickListener { send("list -a") }
        binding.btnSelectAp.setOnClickListener { showSelectApDialog() }
        binding.btnStopScan.setOnClickListener { send("stopscan") }
        binding.btnWardrive.setOnClickListener { send("wardrive") }
        binding.btnSigmon.setOnClickListener { send("sigmon") }
    }

    private fun setupAttack() {
        binding.btnDeauth.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Deauth Attack")
                .setMessage("This will kick all clients off the selected AP.\n\nMake sure you've scanned and selected a target first (SCAN section).")
                .setPositiveButton("ATTACK") { _, _ -> send("attack -t deauth") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnBeacon.setOnClickListener { showBeaconMenu() }
        binding.btnRickroll.setOnClickListener { send("attack -t rickroll") }
        binding.btnProbe.setOnClickListener { send("attack -t probe") }
        binding.btnEvilPortalStart.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Evil Portal")
                .setMessage("Creates a fake WiFi login page.\n\nWhen victims connect and enter credentials, they're captured on the ESP32.\n\nRequires Evil Portal files on SD card.")
                .setPositiveButton("START") { _, _ -> send("evilportal -c start") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnEvilPortalStop.setOnClickListener { send("evilportal -c stop") }
        binding.btnKarma.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Karma Attack")
                .setMessage("Responds to ALL probe requests from nearby devices, tricking them into connecting to your ESP32.\n\nDevices will think they found a known network.")
                .setPositiveButton("START KARMA") { _, _ -> send("karma") }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupCapture() {
        binding.btnSniffPmkid.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Capture PMKID")
                .setMessage("Captures PMKID hashes from nearby APs.\n\nFastest method - no client connection needed.\n\nCaptured hashes can be cracked offline.")
                .setPositiveButton("START") { _, _ -> send("sniffpmkid") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnSniffBeacon.setOnClickListener { send("sniffbeacon") }
        binding.btnSniffDeauth.setOnClickListener { send("sniffdeauth") }
        binding.btnSniffRaw.setOnClickListener { send("sniffraw") }
        binding.btnSniffProbe.setOnClickListener { send("sniffprobe") }
        binding.btnSniffSkim.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Detect Card Skimmers")
                .setMessage("Scans for suspicious Bluetooth devices that match known card skimmer signatures.\n\nWalk near ATMs and gas pumps to check for skimmers.")
                .setPositiveButton("SCAN") { _, _ -> send("sniffskim") }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupCrack() {
        binding.btnCrackWizard.setOnClickListener { showCrackWizard() }
        binding.btnCrackInApp.setOnClickListener { showInAppCrackDialog() }
        binding.btnCrackTermux.setOnClickListener { showTermuxCrackDialog() }
        binding.btnDownloadWordlist.setOnClickListener { downloadWordlist() }
    }

    private fun setupRecon() {
        binding.btnPingScan.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Ping Sweep")
                .setMessage("Discovers active hosts on the network by pinging all IPs in the subnet.\n\nMust be connected to a network first (use JOIN NET).")
                .setPositiveButton("SWEEP") { _, _ -> send("pingscan") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnPortScan.setOnClickListener { showPortScanDialog() }
        binding.btnJoinNetwork.setOnClickListener { showJoinNetworkDialog() }
    }

    private fun setupBluetooth() {
        binding.btnBleSpam.setOnClickListener { showBleSpamMenu() }
        binding.btnSniffBt.setOnClickListener { send("sniffbt") }
        binding.btnSniffAirtag.setOnClickListener { send("sniffbt -t airtag") }
        binding.btnBtWardrive.setOnClickListener { send("btwardrive") }
    }

    private fun setupObservers() {
        appState.activeCommand.observe(viewLifecycleOwner) { cmd ->
            if (cmd != null) {
                val tv = statusViewForCommand(cmd) ?: return@observe
                showRunning(tv, cmd)
            }
        }
        appState.lastResponse.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                val tv = statusViewForCommand(result.cmd) ?: return@observe
                showResult(tv, result.response)
            }
        }
        appState.terminalOutput.observe(viewLifecycleOwner) { data ->
            if (data != null && appState.activeCommand.value != null) {
                val tv = statusViewForCommand(appState.activeCommand.value!!) ?: return@observe
                appendStreaming(tv, data)
            }
        }
    }

    private fun statusViewForCommand(cmd: String): TextView? {
        if (_binding == null) return null
        return when {
            cmd.startsWith("scan") || cmd.startsWith("list") || cmd.startsWith("select") || cmd == "stopscan" || cmd == "wardrive" || cmd == "sigmon" -> binding.statusScan
            cmd.startsWith("attack") || cmd.startsWith("evilportal") || cmd == "karma" -> binding.statusAttack
            cmd.startsWith("sniffpmkid") || cmd.startsWith("sniffbeacon") || cmd.startsWith("sniffdeauth") || cmd.startsWith("sniffraw") || cmd.startsWith("sniffprobe") || cmd.startsWith("sniffskim") -> binding.statusCapture
            cmd.startsWith("pingscan") || cmd.startsWith("portscan") || cmd.startsWith("join") -> binding.statusRecon
            cmd.startsWith("blespam") || cmd.startsWith("sniffbt") || cmd == "btwardrive" -> binding.statusBle
            else -> null
        }
    }

    private fun showSelectApDialog() {
        if (!checkConnected()) return
        val input = EditText(requireContext()).apply {
            hint = "AP index (0-based)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Select Target AP")
            .setMessage("Run SCAN first to see available networks, then RESULTS to get the index number.")
            .setView(input)
            .setPositiveButton("Select") { _, _ ->
                val idx = input.text.toString().trim()
                if (idx.isNotEmpty()) send("select -a $idx")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBeaconMenu() {
        if (!checkConnected()) return
        val options = arrayOf("Random SSIDs", "Rickroll SSIDs", "AP List SSIDs")
        AlertDialog.Builder(requireContext())
            .setTitle("Beacon Spam Type")
            .setMessage("Flood the area with fake WiFi network names.")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> send("attack -t beacon -r")
                    1 -> send("attack -t rickroll")
                    2 -> send("attack -t beacon -l")
                }
            }.show()
    }

    private fun showBleSpamMenu() {
        if (!checkConnected()) return
        val types = arrayOf("All Devices", "Apple (iPhone/iPad)", "Samsung", "Google", "Microsoft")
        AlertDialog.Builder(requireContext())
            .setTitle("BLE Spam Target")
            .setMessage("Flood nearby phones with fake Bluetooth pairing popups.")
            .setItems(types) { _, w ->
                val cmds = arrayOf("blespam -t all", "blespam -t apple", "blespam -t samsung", "blespam -t google", "blespam -t microsoft")
                send(cmds[w])
            }.show()
    }

    private fun showCrackWizard() {
        val options = arrayOf("I have a PMKID hash", "I have a PCAP capture file", "I need to capture first")
        AlertDialog.Builder(requireContext())
            .setTitle("Crack WiFi Password")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> showCrackToolPicker("pmkid")
                    1 -> showCrackToolPicker("pcap")
                    2 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Capture First")
                            .setMessage("Use the CAPTURE section above to grab a PMKID or handshake first.\n\nPMKID is fastest - no connected client needed.\n\nAfter capturing, come back here to crack it.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }.show()
    }

    private fun showCrackToolPicker(type: String) {
        val tools = arrayOf("Quick Crack (built-in dictionary)", "Aircrack-ng (Termux)", "Hashcat (Termux)")
        AlertDialog.Builder(requireContext())
            .setTitle("Which cracking tool?")
            .setItems(tools) { _, w ->
                when (w) {
                    0 -> showInAppCrackDialog()
                    1 -> if (type == "pcap") showAircrackDialog() else showHashcatDialog()
                    2 -> showHashcatDialog()
                }
            }.show()
    }

    private fun showInAppCrackDialog() {
        val input = EditText(requireContext()).apply {
            hint = "/path/to/capture.pcap"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Quick Crack")
            .setMessage("Uses built-in common password dictionary.\nFast but limited. For serious cracking use Termux.")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    mainActivity()?.appendToTerminal("[Crack] Starting in-app dictionary attack...")
                    mainActivity()?.appendToTerminal("[Crack] File: $path")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTermuxCrackDialog() {
        val options = arrayOf("Install aircrack-ng", "Install hashcat", "Run aircrack-ng on PCAP", "Run hashcat on PMKID")
        AlertDialog.Builder(requireContext())
            .setTitle("Termux WiFi Cracking")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> TermuxHelper.runCommand(requireContext(), "pkg install -y aircrack-ng")
                    1 -> TermuxHelper.runCommand(requireContext(), "pkg install -y hashcat")
                    2 -> showAircrackDialog()
                    3 -> showHashcatDialog()
                }
            }.show()
    }

    private fun showAircrackDialog() {
        val input = EditText(requireContext()).apply {
            hint = "/path/to/capture.pcap"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("aircrack-ng")
            .setMessage("Enter PCAP path:")
            .setView(input)
            .setPositiveButton("Crack") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    TermuxHelper.runCommand(requireContext(), "aircrack-ng -w ${TermuxHelper.TERMUX_HOME}/rockyou.txt $path")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHashcatDialog() {
        val input = EditText(requireContext()).apply {
            hint = "/path/to/pmkid.22000"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("hashcat")
            .setMessage("Enter PMKID hash file path:")
            .setView(input)
            .setPositiveButton("Crack") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    TermuxHelper.runCommand(requireContext(), "hashcat -m 22000 $path ${TermuxHelper.TERMUX_HOME}/rockyou.txt")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPortScanDialog() {
        if (!checkConnected()) return
        val options = arrayOf("Scan selected AP", "Scan by IP address")
        AlertDialog.Builder(requireContext())
            .setTitle("Port Scan")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> send("portscan")
                    1 -> {
                        val input = EditText(requireContext()).apply {
                            hint = "192.168.1.1"
                            setPadding(48, 24, 48, 24)
                            setTextColor(0xFFE8F0FF.toInt())
                            setHintTextColor(0xFF4A5B78.toInt())
                        }
                        AlertDialog.Builder(requireContext())
                            .setTitle("Port Scan IP")
                            .setView(input)
                            .setPositiveButton("Scan") { _, _ ->
                                val ip = input.text.toString().trim()
                                if (ip.isNotEmpty()) send("portscan -a $ip")
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }.show()
    }

    private fun showJoinNetworkDialog() {
        if (!checkConnected()) return
        val idxInput = EditText(requireContext()).apply {
            hint = "AP index from scan results"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Join Network")
            .setMessage("Enter the AP index from scan results.\nYou'll be prompted for the password next.")
            .setView(idxInput)
            .setPositiveButton("Next") { _, _ ->
                val idx = idxInput.text.toString().trim()
                if (idx.isNotEmpty()) {
                    val passInput = EditText(requireContext()).apply {
                        hint = "WiFi password"
                        setPadding(48, 24, 48, 24)
                        setTextColor(0xFFE8F0FF.toInt())
                        setHintTextColor(0xFF4A5B78.toInt())
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle("Enter Password")
                        .setView(passInput)
                        .setPositiveButton("Join") { _, _ ->
                            val pass = passInput.text.toString().trim()
                            if (pass.isNotEmpty()) send("join -a $idx -p $pass")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadWordlist() {
        AlertDialog.Builder(requireContext())
            .setTitle("Download rockyou.txt")
            .setMessage("Downloads rockyou.txt (~133MB) to Termux.\n\nThis is the most common password wordlist for WiFi cracking.")
            .setPositiveButton("Download") { _, _ ->
                TermuxHelper.runCommand(requireContext(), "wget -O ~/rockyou.txt https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt")
                mainActivity()?.appendToTerminal("[Wordlist] Download started in Termux...")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appendStreaming(tv: TextView, data: String) {
        val buf = streamBuffers.getOrPut(tv) { StringBuilder() }
        buf.append(data)
        val clean = buf.toString().replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
        val lines = clean.lines().filter { it.isNotBlank() }
        val display = if (lines.size > 8) lines.takeLast(8).joinToString("\n") else lines.joinToString("\n")
        if (display.isNotBlank()) tv.text = display
    }

    private fun showRunning(tv: TextView, cmd: String) {
        cancelAutoHide(tv)
        streamBuffers.remove(tv)
        tv.visibility = View.VISIBLE
        tv.text = "RUNNING: $cmd"
        tv.alpha = 1.0f
        startPulse(tv)
    }

    private fun showResult(tv: TextView, response: String) {
        stopPulse(tv)
        tv.alpha = 1.0f
        val clean = response.replace(Regex(">\u001B\\[[0-9;]*m"), "").replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
        val lines = clean.lines().filter { it.isNotBlank() }
        val display = if (lines.size > 8) lines.take(8).joinToString("\n") + "\n... (${lines.size - 8} more)" else lines.joinToString("\n")
        tv.text = if (display.isBlank()) "DONE (no output)" else display
        tv.visibility = View.VISIBLE
        scheduleAutoHide(tv, 10_000)
    }

    private fun startPulse(tv: TextView) {
        stopPulse(tv)
        pulseAnimator = ObjectAnimator.ofFloat(tv, "alpha", 1.0f, 0.3f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse(tv: TextView) {
        pulseAnimator?.let {
            if (it.target == tv) { it.cancel(); pulseAnimator = null }
        }
        tv.alpha = 1.0f
    }

    private fun scheduleAutoHide(tv: TextView, delayMs: Long) {
        cancelAutoHide(tv)
        val r = Runnable { tv.visibility = View.GONE }
        autoHideRunnables[tv] = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelAutoHide(tv: TextView) {
        autoHideRunnables.remove(tv)?.let { handler.removeCallbacks(it) }
    }

    private fun checkConnected(): Boolean {
        if (mainActivity()?.serial?.connected != true) {
            Toast.makeText(context, "Connect device first (STATUS tab)", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun send(cmd: String) {
        if (!checkConnected()) return
        val main = mainActivity() ?: return
        val serial = main.serial ?: return
        if (serial.deviceType == FlipperSerial.DeviceType.FLIPPER && !serial.bridgeMode) {
            serial.startBridge { main.sendCommand(cmd) }
            return
        }
        main.sendCommand(cmd)
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        pulseAnimator = null
        autoHideRunnables.values.forEach { handler.removeCallbacks(it) }
        autoHideRunnables.clear()
        streamBuffers.clear()
        _binding = null
    }
}
