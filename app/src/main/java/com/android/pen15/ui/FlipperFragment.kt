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
import com.android.pen15.databinding.FragmentFlipperBinding
import com.android.pen15.model.AppState
import com.android.pen15.tools.SubGhzBruteForce

class FlipperFragment : Fragment() {

    private var _binding: FragmentFlipperBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()

    private val subghzFrequencies = arrayOf(
        "433.92 MHz" to "433920000",
        "315.00 MHz" to "315000000",
        "868.35 MHz" to "868350000",
        "915.00 MHz" to "915000000"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFlipperBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRfidRead.setOnClickListener { send("rfid read") }
        binding.btnRfidEmulate.setOnClickListener { send("rfid emulate") }
        binding.btnRfidList.setOnClickListener { send("storage list /ext/lfrfid") }

        binding.btnNfcFieldOn.setOnClickListener { send("nfc field on") }
        binding.btnNfcFieldOff.setOnClickListener { send("nfc field off") }
        binding.btnNfcList.setOnClickListener { send("storage list /ext/nfc") }

        binding.btnSubghzRx.setOnClickListener { showFrequencyPicker() }
        binding.btnSubghzTxFile.setOnClickListener { showTxFilePicker() }
        binding.btnSubghzList.setOnClickListener { send("storage list /ext/subghz") }
        binding.btnSubghzBrute.setOnClickListener { showBruteForceMenu() }

        binding.btnIrRx.setOnClickListener { send("ir rx") }
        binding.btnIrTx.setOnClickListener { showIrTxDialog() }
        binding.btnIrList.setOnClickListener { send("storage list /ext/infrared") }

        binding.btnIkeyRead.setOnClickListener { send("ikey read") }
        binding.btnIkeyEmulate.setOnClickListener { send("ikey emulate") }

        binding.btnBadusbList.setOnClickListener { send("storage list /ext/badusb") }
        binding.btnBadusbDemo.setOnClickListener { send("storage read /ext/badusb/demo.txt") }

        binding.btnStorageRoot.setOnClickListener { send("storage list /ext") }
        binding.btnStorageInfo.setOnClickListener { send("storage info /ext") }

        appState.connected.observe(viewLifecycleOwner) { connected ->
            setAllEnabled(binding.root, connected)
        }
    }

    private fun showFrequencyPicker() {
        val names = subghzFrequencies.map { it.first }.toTypedArray()
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
            .setTitle("RX Frequency")
            .setItems(names) { _, which ->
                send("subghz rx ${subghzFrequencies[which].second}")
            }.show()
    }

    private fun showTxFilePicker() {
        val input = EditText(requireContext()).apply {
            hint = "/ext/subghz/filename.sub"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
            .setTitle("TX from File")
            .setView(input)
            .setPositiveButton("Transmit") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) send("subghz tx_from_file $path")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBruteForceMenu() {
        val protocols = SubGhzBruteForce.PROTOCOLS.map { "${it.name} (${it.bits}bit)" }.toTypedArray()
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
            .setTitle("Brute Force Protocol")
            .setItems(protocols) { _, which ->
                showBruteForceFreq(which)
            }.show()
    }

    private fun showBruteForceFreq(protocolIdx: Int) {
        val protocol = SubGhzBruteForce.PROTOCOLS[protocolIdx]
        val freqs = protocol.frequencies.map { "${it / 1_000_000.0} MHz" }.toTypedArray()
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
            .setTitle("${protocol.name} - Frequency")
            .setItems(freqs) { _, which ->
                startBruteForce(protocolIdx, which)
            }.show()
    }

    private fun startBruteForce(protocolIdx: Int, freqIdx: Int) {
        val protocol = SubGhzBruteForce.PROTOCOLS[protocolIdx]
        val freq = protocol.frequencies[freqIdx]
        val subContent = SubGhzBruteForce.generateSubFile(protocol, freq)
        val fileName = "${protocol.name.lowercase()}_brute_${freq / 1000000}.sub"
        val flipperPath = "/ext/subghz/$fileName"

        mainActivity()?.let { main ->
            main.appendToTerminal("[BruteForce] Generating $fileName...")
            main.appendToTerminal("[BruteForce] ${protocol.name} ${protocol.bits}bit @ ${freq / 1_000_000.0} MHz")
            main.appendToTerminal("[BruteForce] ${1 shl protocol.bits} combinations")

            val lines = subContent.split("\n")
            var idx = 0
            fun writeNext() {
                if (idx < lines.size) {
                    val line = lines[idx]
                    if (idx == 0) {
                        main.serial?.sendCommand("storage write $flipperPath \"$line\"")
                    } else {
                        main.serial?.sendCommand("storage write_append $flipperPath \"$line\"")
                    }
                    idx++
                    main.handler.postDelayed({ writeNext() }, 50)
                } else {
                    main.appendToTerminal("[BruteForce] File uploaded: $flipperPath")
                    main.appendToTerminal("[BruteForce] Starting TX...")
                    main.serial?.sendCommand("subghz tx_from_file $flipperPath")
                }
            }
            writeNext()
        }
    }

    private fun showIrTxDialog() {
        val options = arrayOf("TV Power (NEC)", "TV Vol Up (NEC)", "TV Vol Down (NEC)", "Custom...")
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
            .setTitle("IR Transmit")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> send("ir tx NEC 0x04 0x08")
                    1 -> send("ir tx NEC 0x04 0x02")
                    2 -> send("ir tx NEC 0x04 0x03")
                    3 -> showCustomIrDialog()
                }
            }.show()
    }

    private fun showCustomIrDialog() {
        val input = EditText(requireContext()).apply {
            hint = "NEC 0x04 0x08"
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE8F0FF.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
            .setTitle("IR TX: protocol address command")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val args = input.text.toString().trim()
                if (args.isNotEmpty()) send("ir tx $args")
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
