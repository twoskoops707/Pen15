package com.android.pen15.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.android.pen15.MainActivity
import com.android.pen15.R
import com.android.pen15.databinding.FragmentTerminalBinding
import com.android.pen15.model.AppState

class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    private val appState: AppState by activityViewModels()
    private val lines = mutableListOf<String>()
    private lateinit var adapter: TerminalAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        try {
            _binding = FragmentTerminalBinding.inflate(inflater, container, false)
            return binding.root
        } catch (e: Exception) {
            val tv = TextView(requireContext()).apply {
                text = "TERMINAL ERROR:\n${e.message}\n\n${e.stackTraceToString()}"
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

        adapter = TerminalAdapter(lines)
        binding.terminalRecycler.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        binding.terminalRecycler.adapter = adapter

        binding.btnSend.setOnClickListener {
            if (mainActivity()?.serial?.connected != true) {
                Toast.makeText(context, "Connect device first (STATUS tab)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendInput()
        }
        binding.btnStop.setOnClickListener { mainActivity()?.serial?.sendCtrlC() }
        binding.btnClear.setOnClickListener {
            lines.clear()
            adapter.notifyDataSetChanged()
            appState.clearOutput()
        }
        binding.btnHistory.setOnClickListener { showHistory() }

        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else false
        }

        appState.terminalOutput.observe(viewLifecycleOwner) { text ->
            if (text.isNullOrEmpty()) return@observe
            for (line in text.split("\n")) {
                if (line.isNotEmpty()) lines.add(line)
            }
            if (lines.size > 500) {
                val excess = lines.size - 500
                lines.subList(0, excess).clear()
            }
            adapter.notifyDataSetChanged()
            if (lines.isNotEmpty()) {
                binding.terminalRecycler.scrollToPosition(lines.size - 1)
            }
        }

        appState.connected.observe(viewLifecycleOwner) { connected ->
            binding.statusDot.setBackgroundResource(
                if (connected) R.drawable.dot_green else R.drawable.status_indicator
            )
            binding.statusLabel.text = if (connected) "Connected" else "Terminal"
        }
    }

    private fun sendInput() {
        val cmd = binding.inputField.text.toString().trim()
        if (cmd.isEmpty()) return
        lines.add("> $cmd")
        adapter.notifyDataSetChanged()
        if (lines.isNotEmpty()) binding.terminalRecycler.scrollToPosition(lines.size - 1)
        mainActivity()?.sendCommand(cmd)
        appState.addToHistory(cmd)
        binding.inputField.text.clear()
    }

    private fun showHistory() {
        val history = appState.history
        if (history.isEmpty()) {
            Toast.makeText(context, "No command history yet", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Command History")
            .setItems(history.take(20).toTypedArray()) { _, which ->
                binding.inputField.setText(history[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class TerminalAdapter(private val lines: List<String>) :
        RecyclerView.Adapter<TerminalAdapter.VH>() {

        class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setTextColor(0xFF9CA3AF.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(4, 2, 4, 2)
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val line = lines[position]
            holder.textView.text = line
            holder.textView.setTextColor(
                when {
                    line.startsWith("> ") -> 0xFF00E5FF.toInt()
                    line.startsWith("[") -> 0xFF00FF41.toInt()
                    line.contains("error", ignoreCase = true) -> 0xFFFF1744.toInt()
                    else -> 0xFF7A8BA8.toInt()
                }
            )
        }

        override fun getItemCount() = lines.size
    }
}
