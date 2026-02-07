package com.android.pen15.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TerminalAdapter(lines)
        binding.terminalRecycler.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        binding.terminalRecycler.adapter = adapter

        binding.btnSend.setOnClickListener { sendInput() }
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
            val newLines = text.split("\n")
            for (line in newLines) {
                if (lines.isNotEmpty() && !lines.last().endsWith("\n") && newLines.indexOf(line) == 0) {
                    lines[lines.size - 1] = lines.last() + line
                } else {
                    lines.add(line)
                }
            }
            if (lines.size > 500) {
                val excess = lines.size - 500
                lines.subList(0, excess).clear()
            }
            adapter.notifyDataSetChanged()
            binding.terminalRecycler.scrollToPosition(lines.size - 1)
        }

        appState.connected.observe(viewLifecycleOwner) { connected ->
            binding.btnSend.isEnabled = connected
            binding.btnStop.isEnabled = connected
            binding.inputField.isEnabled = connected
            binding.statusDot.setBackgroundResource(
                if (connected) R.drawable.dot_green else R.drawable.status_indicator
            )
        }
    }

    private fun sendInput() {
        val cmd = binding.inputField.text.toString().trim()
        if (cmd.isEmpty()) return
        appendLine("> $cmd")
        mainActivity()?.sendCommand(cmd)
        appState.addToHistory(cmd)
        binding.inputField.text.clear()
    }

    fun appendLine(text: String) {
        lines.add(text)
        if (lines.size > 500) lines.removeAt(0)
        adapter.notifyDataSetChanged()
        binding.terminalRecycler.scrollToPosition(lines.size - 1)
    }

    private fun showHistory() {
        val history = appState.history
        if (history.isEmpty()) return
        AlertDialog.Builder(requireContext(), R.style.DarkDialog)
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
                setPadding(0, 1, 0, 1)
                setTextIsSelectable(true)
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
                    line.contains("ERROR", ignoreCase = true) -> 0xFFFF1744.toInt()
                    else -> 0xFF9CA3AF.toInt()
                }
            )
        }

        override fun getItemCount() = lines.size
    }
}
