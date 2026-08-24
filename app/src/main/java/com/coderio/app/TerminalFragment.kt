package com.coderio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class TerminalFragment : Fragment() {

    private lateinit var scrollView: ScrollView
    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var statusText: TextView

    private var shell: ShellSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val outputBuffer = StringBuilder()
    private var commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_terminal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scrollView = view.findViewById(R.id.scroll_view)
        terminalOutput = view.findViewById(R.id.terminal_output)
        terminalInput = view.findViewById(R.id.terminal_input)
        btnSend = view.findViewById(R.id.btn_send)
        statusText = view.findViewById(R.id.term_status_text)

        btnSend.setOnClickListener { executeCommand() }

        val quickCommands = mapOf(
            R.id.chip_ls to "ls",
            R.id.chip_pwd to "pwd",
            R.id.chip_whoami to "whoami",
            R.id.chip_uname to "uname -a",
            R.id.chip_clear to "clear"
        )
        for ((chipId, command) in quickCommands) {
            view.findViewById<View>(chipId).setOnClickListener {
                terminalInput.setText(command)
                executeCommand()
            }
        }

        terminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                executeCommand()
                true
            } else false
        }

        // Long-press to copy all output
        terminalOutput.setOnLongClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("terminal output", terminalOutput.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Terminal output copied", Toast.LENGTH_SHORT).show()
            true
        }

        startShell()
    }

    private fun startShell() {
        val cwd = WorkspaceConfig.WORKSPACE_PATH

        shell = ShellSession().apply {
            onOutput = { text ->
                outputBuffer.append(text)
                terminalOutput.text = outputBuffer.toString()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
            onClosed = {
                handler.post {
                    appendOutput("\n--- Shell session ended. Tap ⏎ to restart ---\n")
                    statusText.setText(R.string.terminal_status_ended)
                    statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
                }
            }
        }

        // Set initial CWD
        shell!!.start(cwd)

        appendOutput("coder.io terminal — powered by Android shell\n")
        appendOutput("Type commands below. Long-press output to copy.\n")
        appendOutput("─".repeat(40) + "\n")

        // Set working directory
        handler.postDelayed({
            shell?.sendCommand("cd $cwd && pwd")
        }, 300)

        statusText.setText(R.string.terminal_status_ready)
        statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_green))
    }

    private fun executeCommand() {
        val cmd = terminalInput.text.toString().trim()
        if (cmd.isEmpty()) return

        commandHistory.add(cmd)
        historyIndex = commandHistory.size

        // Handle built-in commands
        when {
            cmd == "clear" -> {
                outputBuffer.clear()
                terminalOutput.text = ""
                terminalInput.text.clear()
                return
            }
            cmd == "exit" -> {
                shell?.stop()
                appendOutput("Shell stopped. Tap ⏎ to restart.\n")
                terminalInput.text.clear()
                return
            }
            cmd == "restart" -> {
                outputBuffer.clear()
                terminalOutput.text = ""
                shell?.stop()
                startShell()
                terminalInput.text.clear()
                return
            }
        }

        appendOutput("$ $cmd\n")
        shell?.sendCommand(cmd)
        terminalInput.text.clear()
    }

    private fun appendOutput(text: String) {
        outputBuffer.append(text)
        terminalOutput.text = outputBuffer.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell?.stop()
    }

    fun restartShell() {
        outputBuffer.clear()
        terminalOutput.text = ""
        shell?.stop()
        startShell()
    }
}
