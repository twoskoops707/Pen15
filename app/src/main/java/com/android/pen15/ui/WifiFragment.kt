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
import com.android.pen15.tools.TermuxHelper

class WifiFragment : Fragment() {

    private var _binding: FragmentWifiBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private val autoHideRunnables = mutableMapOf<TextView, Runnable>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        try {
            _binding = FragmentWifiBinding.inflate(inflater, container, false)
            return binding.root
        } catch (e: Exception) {
            val tv = android.widget.TextView(requireContext()).apply {
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

        binding.btnScanAp.setOnClickListener { send("scanap") }
        binding.btnScanSta.setOnClickListener { send("scansta") }
        binding.btnListAp.setOnClickListener { send("list -a") }
        binding.btnSelectAp.setOnClickListener { showSelectApDialog() }
        binding.btnStopScan.setOnClickListener { send("stopscan") }

        binding.btnDeauth.setOnClickListener { send("attack -t deauth") }
        binding.btnBeacon.setOnClickListener { showBeaconMenu() }
        binding.btnProbe.setOnClickListener { send("attack -t probe") }
        binding.btnRickroll.setOnClickListener { send("attack -t rickroll") }

        binding.btnSniffPmkid.setOnClickListener { send("sniffpmkid") }
        binding.btnSniffBeacon.setOnClickListener { send("sniffbeacon") }
        binding.btnSniffDeauth.setOnClickListener { send("sniffdeauth") }
        binding.btnSniffRaw.setOnClickListener { send("sniffraw") }
        binding.btnSniffProbe.setOnClickListener { send("sniffprobe") }

        binding.btnEvilPortalStart.setOnClickListener { send("evilportal -c start") }
        binding.btnEvilPortalStop.setOnClickListener { send("evilportal -c stop") }

        binding.btnCrackInApp.setOnClickListener { showInAppCrackDialog() }
        binding.btnCrackTermux.setOnClickListener { showTermuxCrackDialog() }
        binding.btnDownloadWordlist.setOnClickListener { downloadWordlist() }

        binding.btnBleSpam.setOnClickListener { showBleSpamMenu() }
        binding.btnSniffBt.setOnClickListener { send("sniffbt") }
        binding.btnSniffAirtag.setOnClickListener { send("sniffbt -t airtag") }

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
    }

    private fun statusViewForCommand(cmd: String): TextView? {
        if (_binding == null) return null
        return when {
            cmd.startsWith("scan") || cmd.startsWith("list") || cmd.startsWith("select") || cmd == "stopscan" -> binding.statusScan
            cmd.startsWith("attack") || cmd.startsWith("evilportal") -> binding.statusAttack
            cmd.startsWith("sniffpmkid") || cmd.startsWith("sniffbeacon") || cmd.startsWith("sniffdeauth") || cmd.startsWith("sniffraw") || cmd.startsWith("sniffprobe") -> binding.statusCapture
            cmd.startsWith("blespam") || cmd.startsWith("sniffbt") -> binding.statusBle
            else -> null
        }
    }

    private fun showRunning(tv: TextView, cmd: String) {
        cancelAutoHide(tv)
        tv.visibility = View.VISIBLE
        tv.text = "RUNNING: $cmd"
        tv.alpha = 1.0f
        startPulse(tv)
    }

    private fun showResult(tv: TextView, response: String) {
        stopPulse(tv)
        tv.alpha = 1.0f
        val clean = response.replace(Regex(">\u001B\\[[0-9;]*m"), "")
            .replace(Regex("\u001B\\[[0-9;]*m"), "")
            .trim()
        val lines = clean.lines().filter { it.isNotBlank() }
        val display = if (lines.size > 8) {
            lines.take(8).joinToString("\n") + "\n... (${lines.size - 8} more)"
        } else {
            lines.joinToString("\n")
        }
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
            if (it.target == tv) {
                it.cancel()
                pulseAnimator = null
            }
        }
        tv.alpha = 1.0f
    }

    private fun scheduleAutoHide(tv: TextView, delayMs: Long) {
        cancelAutoHide(tv)
        val runnable = Runnable { tv.visibility = View.GONE }
        autoHideRunnables[tv] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoHide(tv: TextView) {
        autoHideRunnables.remove(tv)?.let { handler.removeCallbacks(it) }
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
            .setTitle("Select AP")
            .setMessage("Run 'Scan APs' first, then enter index:")
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
            .setItems(options) { _, which ->
                when (which) {
                    0 -> send("attack -t beacon -r")
                    1 -> send("attack -t rickroll")
                    2 -> send("attack -t beacon -l")
                }
            }.show()
    }

    private fun showBleSpamMenu() {
        if (!checkConnected()) return
        val types = arrayOf("All", "Apple", "Samsung", "Google", "Microsoft", "Flipper")
        val cmds = arrayOf("blespam -t all", "blespam -t apple", "blespam -t samsung", "blespam -t google", "blespam -t microsoft", "sniffbt -t flipper")
        AlertDialog.Builder(requireContext())
            .setTitle("BLE Spam Type")
            .setItems(types) { _, which ->
                send(cmds[which])
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
            .setTitle("In-App WPA Crack")
            .setMessage("Enter path to PCAP/PMKID file:")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    mainActivity()?.appendToTerminal("[Crack] Starting in-app dictionary attack...")
                    mainActivity()?.appendToTerminal("[Crack] File: $path")
                    mainActivity()?.appendToTerminal("[Crack] Use Termux for heavy wordlists")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTermuxCrackDialog() {
        val options = arrayOf(
            "Install aircrack-ng",
            "Install hashcat",
            "Run aircrack-ng on PCAP",
            "Run hashcat on PMKID"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Termux WiFi Cracking")
            .setItems(options) { _, which ->
                val ctx = requireContext()
                when (which) {
                    0 -> TermuxHelper.runCommand(ctx, "pkg install -y aircrack-ng")
                    1 -> TermuxHelper.runCommand(ctx, "pkg install -y hashcat")
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
                    val wordlist = "${TermuxHelper.TERMUX_HOME}/rockyou.txt"
                    TermuxHelper.runCommand(requireContext(), "aircrack-ng -w $wordlist $path")
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
                    val wordlist = "${TermuxHelper.TERMUX_HOME}/rockyou.txt"
                    TermuxHelper.runCommand(requireContext(), "hashcat -m 22000 $path $wordlist")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadWordlist() {
        AlertDialog.Builder(requireContext())
            .setTitle("Download rockyou.txt")
            .setMessage("This will download rockyou.txt (~133MB) to Termux home directory via wget.")
            .setPositiveButton("Download") { _, _ ->
                TermuxHelper.runCommand(
                    requireContext(),
                    "wget -O ~/rockyou.txt https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt"
                )
                mainActivity()?.appendToTerminal("[Wordlist] Download started in Termux...")
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        autoHideRunnables.values.forEach { handler.removeCallbacks(it) }
        autoHideRunnables.clear()
        _binding = null
    }
}
