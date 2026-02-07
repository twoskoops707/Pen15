package com.android.pen15.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.android.pen15.MainActivity
import com.android.pen15.R
import com.android.pen15.databinding.FragmentWifiBinding
import com.android.pen15.model.AppState
import com.android.pen15.tools.TermuxHelper
import com.android.pen15.tools.WpaCracker

class WifiFragment : Fragment() {

    private var _binding: FragmentWifiBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWifiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        binding.btnCrackInApp.setOnClickListener { showInAppCrackDialog() }
        binding.btnCrackTermux.setOnClickListener { showTermuxCrackDialog() }
        binding.btnDownloadWordlist.setOnClickListener { downloadWordlist() }

        binding.btnBleSpam.setOnClickListener { showBleSpamMenu() }
        binding.btnSniffBt.setOnClickListener { send("sniffbt") }

        appState.connected.observe(viewLifecycleOwner) { connected ->
            setAllEnabled(binding.root, connected)
            binding.btnCrackInApp.isEnabled = true
            binding.btnCrackTermux.isEnabled = true
            binding.btnDownloadWordlist.isEnabled = true
        }
    }

    private fun showSelectApDialog() {
        val input = EditText(requireContext()).apply {
            hint = "AP index (0-based)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        val options = arrayOf("Random SSIDs", "Rickroll SSIDs", "AP List SSIDs")
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        val types = arrayOf("All", "Apple", "Samsung", "Google", "Microsoft", "Flipper")
        val cmds = arrayOf("blespam -t all", "blespam -t apple", "blespam -t samsung", "blespam -t google", "blespam -t microsoft", "sniffbt -t flipper")
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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

    private fun send(cmd: String) {
        mainActivity()?.sendCommand(cmd)
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    private fun setAllEnabled(view: View, enabled: Boolean) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setAllEnabled(view.getChildAt(i), enabled)
        }
        if (view is com.google.android.material.button.MaterialButton) view.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
