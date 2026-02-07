package com.android.pen15.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.android.pen15.MainActivity
import com.android.pen15.R
import com.android.pen15.databinding.FragmentStatusBinding
import com.android.pen15.model.AppState
import com.android.pen15.serial.FlipperSerial

class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        try {
            _binding = FragmentStatusBinding.inflate(inflater, container, false)
            return binding.root
        } catch (e: Exception) {
            val tv = android.widget.TextView(requireContext()).apply {
                text = "STATUS INFLATE ERROR:\n${e.message}\n\n${e.stackTraceToString()}"
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

        binding.btnConnect.setOnClickListener { mainActivity()?.serial?.scanAndConnect() }
        binding.btnConnectFlipper.setOnClickListener { mainActivity()?.serial?.scanAndConnect(FlipperSerial.FLIPPER_VID) }
        binding.btnConnectEsp.setOnClickListener { mainActivity()?.serial?.scanAndConnect(FlipperSerial.ESP32_CP210X_VID) }
        binding.btnDisconnect.setOnClickListener { mainActivity()?.serial?.disconnect() }

        binding.btnRefreshInfo.setOnClickListener { mainActivity()?.sendCommand("device_info") }

        binding.btn5vOn.setOnClickListener { mainActivity()?.sendCommand("power 5v 1") }
        binding.btn5vOff.setOnClickListener { mainActivity()?.sendCommand("power 5v 0") }
        binding.btnReboot.setOnClickListener { mainActivity()?.sendCommand("power reboot") }
        binding.btnShutdown.setOnClickListener { mainActivity()?.sendCommand("power off") }

        appState.connected.observe(viewLifecycleOwner) { connected ->
            binding.statusDot.setBackgroundResource(
                if (connected) R.drawable.dot_green else R.drawable.status_indicator
            )
            binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
            binding.btnConnect.isEnabled = !connected
            binding.btnConnectFlipper.isEnabled = !connected
            binding.btnConnectEsp.isEnabled = !connected
            binding.btnRefreshInfo.isEnabled = connected
            binding.btn5vOn.isEnabled = connected
            binding.btn5vOff.isEnabled = connected
            binding.btnReboot.isEnabled = connected
            binding.btnShutdown.isEnabled = connected
        }

        appState.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.statusText.text = status
            binding.statusText.setTextColor(
                if (status == "OFFLINE") requireContext().getColor(R.color.text_tertiary)
                else requireContext().getColor(R.color.green)
            )
        }

        appState.deviceName.observe(viewLifecycleOwner) { name ->
            binding.deviceNameText.text = name
        }

        appState.terminalOutput.observe(viewLifecycleOwner) { text ->
            if (text.contains(" : ")) {
                parseDeviceInfo(text)
            }
        }
    }

    private fun parseDeviceInfo(raw: String) {
        val fields = mutableMapOf<String, String>()
        val patterns = mapOf(
            "hardware_model" to "Model",
            "hardware_name" to "Name",
            "firmware_version" to "Firmware",
            "firmware_origin_fork" to "Fork",
            "firmware_build_date" to "Build Date",
            "radio_ble_mac" to "BLE MAC"
        )
        for ((key, label) in patterns) {
            val match = Regex("$key\\s+:\\s*(.+)").find(raw)
            if (match != null) fields[label] = match.groupValues[1].trim()
        }
        if (fields.isNotEmpty()) {
            val sb = StringBuilder()
            for ((k, v) in fields) sb.append("$k: $v\n")
            binding.deviceInfoText.text = sb.toString().trimEnd()
        }
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
