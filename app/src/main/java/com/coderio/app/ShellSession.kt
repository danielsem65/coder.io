package com.coderio.app

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Manages a persistent /system/bin/sh shell process.
 * No proot — uses Android's native shell directly.
 * Enhanced with a custom PATH that includes our data directory for bundled binaries.
 */
class ShellSession(private val cwd: String = "/data/local/tmp") {

    private var process: Process? = null
    private var stdin: DataOutputStream? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var readerJob: Job? = null

    var onOutput: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    val isRunning: Boolean get() = process != null && isProcessAlive()

    fun start(dataDir: String? = null) {
        if (isRunning) return

        val env = mutableMapOf(
            "TERM" to "xterm-256color",
            "HOME" to cwd,
            "SHELL" to "/system/bin/sh",
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to "/data/local/tmp"
        )

        // Build PATH that includes our app's data directory for bundled binaries
        val customBin = dataDir?.let { "$it/bin" } ?: ""
        val path = if (customBin.isNotEmpty()) {
            "$customBin:/system/bin:/system/xbin:/vendor/bin:$cwd/bin"
        } else {
            "/system/bin:/system/xbin:/vendor/bin:$cwd/bin"
        }
        env["PATH"] = path

        val pb = ProcessBuilder(listOf("/system/bin/sh", "-i"))
        pb.directory(java.io.File(cwd))
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)

        try {
            process = pb.start()
            stdin = DataOutputStream(process!!.outputStream)
            stdoutReader = BufferedReader(InputStreamReader(process!!.inputStream))
            stderrReader = BufferedReader(InputStreamReader(process!!.errorStream))

            // Start reader coroutines
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            readerJob = scope.launch {
                val outJob = launch { readStream(stdoutReader!!) }
                val errJob = launch { readStream(stderrReader!!, isErr = true) }
                joinAll(outJob, errJob)
                // Process ended
                withContext(Dispatchers.Main) {
                    onClosed?.invoke()
                }
            }
        } catch (e: Exception) {
            onOutput?.invoke("ERROR: Failed to start shell: ${e.message}\n")
        }
    }

    fun sendCommand(command: String) {
        if (!isRunning) {
            onOutput?.invoke("Shell not running. Tap to restart.\n")
            return
        }
        try {
            stdin?.writeBytes("$command\n")
            stdin?.flush()
        } catch (e: Exception) {
            onOutput?.invoke("ERROR: ${e.message}\n")
        }
    }

    /**
     * Send a special command that won't produce shell output noise.
     * Useful for PWD checks, env queries, etc.
     */
    fun sendRaw(command: String) {
        sendCommand(command)
    }

    fun stop() {
        try {
            readerJob?.cancel()
            stdin?.close()
            stdoutReader?.close()
            stderrReader?.close()
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        stdin = null
        stdoutReader = null
        stderrReader = null
    }

    fun restart(dataDir: String? = null) {
        stop()
        start(dataDir)
    }

    private suspend fun readStream(reader: BufferedReader, isErr: Boolean = false) {
        try {
            val buf = CharArray(4096)
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = reader.read(buf)
                if (n == -1) break
                val text = String(buf, 0, n)
                withContext(Dispatchers.Main) {
                    onOutput?.invoke(text)
                }
            }
        } catch (_: Exception) {}
    }

    private fun isProcessAlive(): Boolean {
        return try {
            process?.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }
}
