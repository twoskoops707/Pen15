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
import com.android.pen15.databinding.FragmentFlipperBinding
import com.android.pen15.model.AppState
import com.android.pen15.tools.SubGhzBruteForce

class FlipperFragment : Fragment() {

    private var _binding: FragmentFlipperBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private val streamBuffers = mutableMapOf<TextView, StringBuilder>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        try {
            _binding = FragmentFlipperBinding.inflate(inflater, container, false)
            return binding.root
        } catch (e: Exception) {
            val tv = TextView(requireContext()).apply {
                text = "FLIPPER ERROR:\n${e.message}\n\n${e.stackTraceToString()}"
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
        setupSubGhz()
        setupRfid()
        setupNfc()
        setupIr()
        setupIkey()
        setupBadusb()
        setupGpio()
        setupObservers()
    }

    private fun setupSections() {
        wireSection(binding.headerSubghz, binding.contentSubghz, binding.arrowSubghz)
        wireSection(binding.headerRfid, binding.contentRfid, binding.arrowRfid)
        wireSection(binding.headerNfc, binding.contentNfc, binding.arrowNfc)
        wireSection(binding.headerIr, binding.contentIr, binding.arrowIr)
        wireSection(binding.headerIkey, binding.contentIkey, binding.arrowIkey)
        wireSection(binding.headerBadusb, binding.contentBadusb, binding.arrowBadusb)
        wireSection(binding.headerGpio, binding.contentGpio, binding.arrowGpio)
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

    private fun setupSubGhz() {
        binding.btnSubghzCapture.setOnClickListener { showSubghzCapture() }
        binding.btnSubghzReplay.setOnClickListener { showTxFilePicker() }
        binding.btnSubghzSaved.setOnClickListener { send("storage list /ext/subghz") }
        binding.btnSubghzBrute.setOnClickListener { showBruteForceWizard() }
        binding.btnSubghzAnalyzer.setOnClickListener { send("subghz rx 433920000") }
    }

    private fun showSubghzCapture() {
        if (!checkConnected()) return
        val freqs = arrayOf(
            "433.92 MHz (EU garage/gate)", "315.00 MHz (US garage)", "300.00 MHz (US older)",
            "868.35 MHz (EU long range)", "310.00 MHz (US Linear)", "390.00 MHz (US Genie)",
            "434.075 MHz (Ansonic)", "915.00 MHz (US ISM)"
        )
        val vals = arrayOf("433920000", "315000000", "300000000", "868350000", "310000000", "390000000", "434075000", "915000000")
        AlertDialog.Builder(requireContext())
            .setTitle("What frequency to listen on?")
            .setItems(freqs) { _, w -> send("subghz rx ${vals[w]}") }
            .show()
    }

    private fun showTxFilePicker() {
        if (!checkConnected()) return
        val input = EditText(requireContext()).apply {
            hint = "/ext/subghz/filename.sub"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Replay File")
            .setMessage("Enter path to .sub file on Flipper")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) send("subghz tx_from_file $path")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBruteForceWizard() {
        if (!checkConnected()) return
        val targets = arrayOf("Garage Door", "Gate / Barrier", "Doorbell", "I Don't Know - Capture First")
        AlertDialog.Builder(requireContext())
            .setTitle("What are you testing?")
            .setItems(targets) { _, w ->
                when (w) {
                    0 -> showBruteRegion()
                    1 -> showGateProtocol()
                    2 -> startBruteForceAuto("Holtek", 433920000L)
                    3 -> {
                        Toast.makeText(context, "Hold remote near Flipper and press button", Toast.LENGTH_LONG).show()
                        send("subghz rx 433920000")
                    }
                }
            }.show()
    }

    private fun showBruteRegion() {
        val regions = arrayOf("North America (US/Canada)", "Europe (EU/UK)", "Not Sure")
        AlertDialog.Builder(requireContext())
            .setTitle("What region are you in?")
            .setItems(regions) { _, w ->
                when (w) {
                    0 -> showNaBrands()
                    1 -> showEuBrands()
                    2 -> showBruteConfirm("Linear", 10, 300000000L, "~3.5 min", 1024)
                }
            }.show()
    }

    private fun showNaBrands() {
        val brands = arrayOf("Chamberlain / LiftMaster", "Linear / Multi-Code", "Genie", "Not Sure - Try All")
        AlertDialog.Builder(requireContext())
            .setTitle("What brand?")
            .setItems(brands) { _, w ->
                when (w) {
                    0 -> showBruteConfirm("Chamberlain", 9, 315000000L, "~2 min", 512)
                    1 -> showBruteConfirm("Linear", 10, 300000000L, "~3.5 min", 1024)
                    2 -> showBruteConfirm("Linear", 10, 390000000L, "~3.5 min", 1024)
                    3 -> showBruteConfirm("Linear", 10, 300000000L, "~3.5 min", 1024)
                }
            }.show()
    }

    private fun showEuBrands() {
        val brands = arrayOf("CAME", "NICE", "Holtek", "Ansonic", "Not Sure - Try All")
        AlertDialog.Builder(requireContext())
            .setTitle("What brand?")
            .setItems(brands) { _, w ->
                when (w) {
                    0 -> showBruteConfirm("CAME", 12, 433920000L, "~5 min", 4096)
                    1 -> showBruteConfirm("NICE", 12, 433920000L, "~10 min", 4096)
                    2 -> showBruteConfirm("Holtek", 12, 433920000L, "~6.5 min", 4096)
                    3 -> showBruteConfirm("Ansonic", 12, 434075000L, "~4.5 min", 4096)
                    4 -> showBruteConfirm("CAME", 12, 433920000L, "~5 min", 4096)
                }
            }.show()
    }

    private fun showGateProtocol() {
        val brands = arrayOf("CAME (EU)", "NICE (EU)", "Ansonic (EU)", "Linear (US)", "Not Sure")
        AlertDialog.Builder(requireContext())
            .setTitle("Gate brand?")
            .setItems(brands) { _, w ->
                when (w) {
                    0 -> showBruteConfirm("CAME", 12, 433920000L, "~5 min", 4096)
                    1 -> showBruteConfirm("NICE", 12, 433920000L, "~10 min", 4096)
                    2 -> showBruteConfirm("Ansonic", 12, 434075000L, "~4.5 min", 4096)
                    3 -> showBruteConfirm("Linear", 10, 300000000L, "~3.5 min", 1024)
                    4 -> showBruteConfirm("CAME", 12, 433920000L, "~5 min", 4096)
                }
            }.show()
    }

    private fun showBruteConfirm(protocolName: String, bits: Int, freq: Long, time: String, codes: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Ready to brute force")
            .setMessage("Protocol: $protocolName\nBits: $bits\nFrequency: ${freq / 1_000_000.0} MHz\nCodes to try: $codes\nEstimated time: $time\n\nMake sure Flipper is close to the target.")
            .setPositiveButton("START") { _, _ -> startBruteForceAuto(protocolName, freq) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startBruteForceAuto(protocolName: String, freq: Long) {
        val protocol = SubGhzBruteForce.PROTOCOLS.find { it.name == protocolName }
        if (protocol == null) {
            Toast.makeText(context, "Protocol not found: $protocolName", Toast.LENGTH_SHORT).show()
            return
        }
        val subContent = SubGhzBruteForce.generateSubFile(protocol, freq)
        val fileName = "${protocol.name.lowercase()}_brute_${freq / 1000000}.sub"
        val flipperPath = "/ext/subghz/$fileName"

        mainActivity()?.let { main ->
            main.appendToTerminal("[BruteForce] ${protocol.name} ${protocol.bits}bit @ ${freq / 1_000_000.0} MHz")
            main.appendToTerminal("[BruteForce] ${1 shl protocol.bits} codes - uploading...")

            val lines = subContent.split("\n")
            var idx = 0
            fun writeNext() {
                if (idx < lines.size) {
                    val line = lines[idx]
                    if (idx == 0) main.serial?.sendCommand("storage write $flipperPath \"$line\"")
                    else main.serial?.sendCommand("storage write_append $flipperPath \"$line\"")
                    idx++
                    main.handler.postDelayed({ writeNext() }, 50)
                } else {
                    main.appendToTerminal("[BruteForce] Uploaded: $flipperPath")
                    main.appendToTerminal("[BruteForce] Starting TX...")
                    main.serial?.sendCommand("subghz tx_from_file $flipperPath")
                }
            }
            writeNext()
        }
    }

    private fun setupRfid() {
        binding.btnRfidRead.setOnClickListener { showRfidWizard() }
        binding.btnRfidWrite.setOnClickListener {
            val data = appState.lastRfidData
            if (data == null) {
                Toast.makeText(context, "Read a card first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            send("rfid write ${data.first} ${data.second}")
        }
        binding.btnRfidEmulate.setOnClickListener {
            val data = appState.lastRfidData
            if (data == null) {
                Toast.makeText(context, "Read a card first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            send("rfid emulate ${data.first} ${data.second}")
        }
        binding.btnRfidSaved.setOnClickListener { send("storage list /ext/lfrfid") }
    }

    private fun showRfidWizard() {
        if (!checkConnected()) return
        val options = arrayOf("Access Badge", "Key Fob", "Hotel Key", "Not Sure")
        AlertDialog.Builder(requireContext())
            .setTitle("What type of card?")
            .setItems(options) { _, w ->
                when (w) {
                    0, 1 -> AlertDialog.Builder(requireContext())
                        .setTitle("Reading RFID Card")
                        .setMessage("Hold card flat against the BACK of your Flipper Zero.\n\nKeep it still until the read completes.")
                        .setPositiveButton("READ NOW") { _, _ -> send("rfid read") }
                        .setNegativeButton("Cancel", null)
                        .show()
                    2 -> AlertDialog.Builder(requireContext())
                        .setTitle("Hotel Keys")
                        .setMessage("Most hotel keys use NFC (13.56 MHz), not RFID.\n\nTry the NFC section instead. Hold the card against the TOP of your Flipper.")
                        .setPositiveButton("OK", null)
                        .show()
                    3 -> AlertDialog.Builder(requireContext())
                        .setTitle("Reading Unknown Card")
                        .setMessage("Hold card flat against the BACK of your Flipper Zero.\n\nIf nothing happens after 10 seconds, try the NFC section instead (card goes on TOP).")
                        .setPositiveButton("TRY RFID") { _, _ -> send("rfid read") }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }.show()
    }

    private fun setupNfc() {
        binding.btnNfcRead.setOnClickListener { showNfcWizard() }
        binding.btnNfcDetect.setOnClickListener { sendNfcSubshell("scanner") }
        binding.btnNfcEmulate.setOnClickListener {
            val path = appState.lastNfcPath
            if (path == null) {
                Toast.makeText(context, "Read a tag first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendNfcSubshell("emulate f $path")
        }
        binding.btnNfcFieldOn.setOnClickListener { sendNfcSubshell("field on") }
        binding.btnNfcFieldOff.setOnClickListener { sendNfcSubshell("field off") }
        binding.btnNfcSaved.setOnClickListener { send("storage list /ext/nfc") }
    }

    private fun sendNfcSubshell(cmd: String) {
        if (!checkConnected()) return
        mainActivity()?.serial?.sendSubshellCommand("nfc", cmd)
    }

    private fun showNfcWizard() {
        if (!checkConnected()) return
        val options = arrayOf("Building Access Card", "Payment Card", "Transit Card", "NFC Tag / Sticker")
        AlertDialog.Builder(requireContext())
            .setTitle("What are you scanning?")
            .setItems(options) { _, w ->
                val msg = when (w) {
                    0 -> "Hold the access card against the TOP of your Flipper Zero.\n\nKeep it still until read completes. Most office badges are NFC."
                    1 -> "Hold the payment card against the TOP of your Flipper Zero.\n\nNote: Only the card UID will be captured. EMV payment data is encrypted."
                    2 -> "Hold the transit card against the TOP of your Flipper Zero.\n\nSome transit cards (like MIFARE) can be read and emulated."
                    else -> "Hold the NFC tag against the TOP of your Flipper Zero.\n\nNTAG, MIFARE, and most common tags are supported."
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Reading NFC")
                    .setMessage(msg)
                    .setPositiveButton("READ NOW") { _, _ -> sendNfcSubshell("scanner") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }.show()
    }

    private fun setupIr() {
        binding.btnIrControl.setOnClickListener { showIrWizard() }
        binding.btnIrLearn.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Learn IR Signal")
                .setMessage("Point any remote at the Flipper Zero's IR receiver and press a button.\n\nThe signal will be captured and can be replayed later.")
                .setPositiveButton("START") { _, _ -> send("ir rx") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnIrSend.setOnClickListener { showCustomIrDialog() }
        binding.btnIrSaved.setOnClickListener { send("storage list /ext/infrared") }
    }

    private fun showIrWizard() {
        if (!checkConnected()) return
        val options = arrayOf("TV", "AC Unit", "Projector", "Custom Signal")
        AlertDialog.Builder(requireContext())
            .setTitle("What device?")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> showTvRemote()
                    1 -> AlertDialog.Builder(requireContext())
                        .setTitle("AC Unit")
                        .setMessage("AC remotes use complex IR protocols that vary by brand.\n\nUse LEARN to capture your existing remote's signals first.")
                        .setPositiveButton("LEARN SIGNAL") { _, _ -> send("ir rx") }
                        .setNegativeButton("Cancel", null)
                        .show()
                    2 -> showTvRemote()
                    3 -> showCustomIrDialog()
                }
            }.show()
    }

    private fun showTvRemote() {
        val buttons = arrayOf("Power", "Volume Up", "Volume Down", "Channel Up", "Channel Down", "Mute", "Input/Source")
        val cmds = arrayOf(
            "ir tx NEC 0x04 0x08", "ir tx NEC 0x04 0x02", "ir tx NEC 0x04 0x03",
            "ir tx NEC 0x04 0x00", "ir tx NEC 0x04 0x01", "ir tx NEC 0x04 0x09", "ir tx NEC 0x04 0x0B"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("TV Remote (NEC)")
            .setItems(buttons) { _, w -> send(cmds[w]) }
            .show()
    }

    private fun showCustomIrDialog() {
        if (!checkConnected()) return
        val input = EditText(requireContext()).apply {
            hint = "NEC 0x04 0x08"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("IR: protocol address command")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val args = input.text.toString().trim()
                if (args.isNotEmpty()) send("ir tx $args")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupIkey() {
        binding.btnIkeyRead.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Read iButton Key")
                .setMessage("Touch the iButton key to the Flipper Zero's contact pad on the bottom edge.\n\nSupports Dallas, Cyfral, and Metakom formats.")
                .setPositiveButton("READ") { _, _ -> send("ikey read") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnIkeyWrite.setOnClickListener {
            val data = appState.lastIkeyData
            if (data == null) {
                Toast.makeText(context, "Read a key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            send("ikey write ${data.first} ${data.second}")
        }
        binding.btnIkeyEmulate.setOnClickListener {
            val data = appState.lastIkeyData
            if (data == null) {
                Toast.makeText(context, "Read a key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            send("ikey emulate ${data.first} ${data.second}")
        }
    }

    private fun setupBadusb() {
        binding.btnBadusbPayloads.setOnClickListener { send("storage list /ext/badusb") }
        binding.btnBadusbDemo.setOnClickListener { send("storage read /ext/badusb/demo.txt") }
        binding.btnBadusbRun.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            send("loader open Bad USB")
        }
    }

    private fun setupGpio() {
        binding.btnGpioBridge.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            mainActivity()?.serial?.startBridge()
        }
        binding.btnGpioStopBridge.setOnClickListener { mainActivity()?.serial?.stopBridge() }
        binding.btnGpio5vOn.setOnClickListener { send("power 5v 1") }
        binding.btnGpio5vOff.setOnClickListener { send("power 5v 0") }
        binding.btnGpioReboot.setOnClickListener {
            if (!checkConnected()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Reboot Flipper?")
                .setMessage("This will reboot your Flipper Zero. Any running operations will stop.")
                .setPositiveButton("REBOOT") { _, _ -> send("power reboot") }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnGpioInfo.setOnClickListener { send("device_info") }
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
            cmd.startsWith("rfid") || cmd.startsWith("storage list /ext/lfrfid") -> binding.statusRfid
            cmd.startsWith("subghz") || cmd.startsWith("storage list /ext/subghz") || cmd.startsWith("storage write") -> binding.statusSubghz
            cmd.startsWith("nfc") || cmd.startsWith("storage list /ext/nfc") -> binding.statusNfc
            cmd.startsWith("ir ") || cmd.startsWith("storage list /ext/infrared") -> binding.statusIr
            cmd.startsWith("ikey") -> binding.statusIkey
            cmd.startsWith("device_info") || cmd.startsWith("power") || cmd.startsWith("loader") -> binding.statusGpio
            else -> null
        }
    }

    private fun appendStreaming(tv: TextView, data: String) {
        val buf = streamBuffers.getOrPut(tv) { StringBuilder() }
        buf.append(data)
        val clean = buf.toString().replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
        val lines = clean.lines().filter { it.isNotBlank() }
        val display = if (lines.size > 6) lines.takeLast(6).joinToString("\n") else lines.joinToString("\n")
        if (display.isNotBlank()) tv.text = display
    }

    private fun showRunning(tv: TextView, cmd: String) {
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
        val display = if (lines.size > 6) lines.take(6).joinToString("\n") + "\n... (${lines.size - 6} more)" else lines.joinToString("\n")
        tv.text = if (display.isBlank()) "DONE (no output)" else display
        tv.visibility = View.VISIBLE
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

    private fun checkConnected(): Boolean {
        if (mainActivity()?.serial?.connected != true) {
            Toast.makeText(context, "Connect device first (STATUS tab)", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun send(cmd: String) {
        if (!checkConnected()) return
        mainActivity()?.sendCommand(cmd)
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        pulseAnimator = null
        streamBuffers.clear()
        _binding = null
    }
}
