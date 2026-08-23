package com.coderio.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages per-session chat history backed by JSON files in app internal storage.
 *
 * Structure:
 *   chat_sessions/index.json  — ordered list of session metadata
 *   chat_sessions/{id}.json   — full message list for one session
 */
class ChatHistoryManager(context: Context) {

    data class ChatSession(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    data class ChatMessage(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sessionsDir = File(context.filesDir, "chat_sessions")
    private val indexFile = File(sessionsDir, "index.json")
    private val prefs: SharedPreferences = context.getSharedPreferences("coder_io_settings", Context.MODE_PRIVATE)

    init {
        sessionsDir.mkdirs()
        if (!indexFile.exists()) {
            indexFile.writeText(JSONArray().toString())
        }
    }

    // ── Active session tracking ──────────────────────────────────

    fun getActiveSessionId(): String? = prefs.getString("active_session_id", null)

    fun setActiveSessionId(id: String?) {
        prefs.edit().putString("active_session_id", id).apply()
    }

    // ── Index operations ─────────────────────────────────────────

    private fun readIndex(): MutableList<JSONObject> {
        return try {
            val arr = JSONArray(indexFile.readText())
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeIndex(entries: List<JSONObject>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e)
        indexFile.writeText(arr.toString())
    }

    fun getAllSessions(): List<ChatSession> {
        return readIndex().map { obj ->
            ChatSession(
                id = obj.getString("id"),
                title = obj.getString("title"),
                createdAt = obj.getLong("created_at"),
                updatedAt = obj.getLong("updated_at")
            )
        }.sortedByDescending { it.updatedAt }
    }

    // ── Session CRUD ─────────────────────────────────────────────

    fun createSession(title: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val resolvedTitle = title ?: "New chat"

        // Write session file
        val sessionFile = File(sessionsDir, "$id.json")
        sessionFile.writeText(JSONArray().toString())

        // Update index
        val entries = readIndex()
        entries.add(JSONObject().apply {
            put("id", id)
            put("title", resolvedTitle)
            put("created_at", now)
            put("updated_at", now)
        })
        writeIndex(entries)

        setActiveSessionId(id)
        return id
    }

    fun getMessages(sessionId: String): List<ChatMessage> {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ChatMessage(
                    role = obj.getString("role"),
                    content = obj.getString("content"),
                    timestamp = obj.optLong("timestamp", 0L)
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun appendMessage(sessionId: String, role: String, content: String) {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return

        try {
            val arr = JSONArray(file.readText())
            arr.put(JSONObject().apply {
                put("role", role)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
            })
            file.writeText(arr.toString())

            // Update index timestamp and title if first user message
            val entries = readIndex()
            val entry = entries.find { it.getString("id") == sessionId }
            if (entry != null) {
                entry.put("updated_at", System.currentTimeMillis())
                // Auto-title from first user message
                if (role == "user" && entry.getString("title") == "New chat") {
                    val autoTitle = if (content.length > 40) content.substring(0, 40) + "…" else content
                    entry.put("title", autoTitle)
                }
                writeIndex(entries)
            }
        } catch (_: Exception) {}
    }

    fun deleteSession(sessionId: String) {
        val file = File(sessionsDir, "$sessionId.json")
        if (file.exists()) file.delete()

        val entries = readIndex().filter { it.getString("id") != sessionId }
        writeIndex(entries)

        if (getActiveSessionId() == sessionId) {
            setActiveSessionId(null)
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val entries = readIndex()
        val entry = entries.find { it.getString("id") == sessionId }
        if (entry != null) {
            entry.put("title", newTitle)
            entry.put("updated_at", System.currentTimeMillis())
            writeIndex(entries)
        }
    }

    fun getSessionTitle(sessionId: String): String {
        val entries = readIndex()
        return entries.find { it.getString("id") == sessionId }
            ?.getString("title") ?: "Chat"
    }

    /**
     * Returns a ChatSession object with messages loaded, or null if session doesn't exist.
     */
    fun getSession(sessionId: String): ChatSession? {
        val entries = readIndex()
        val entry = entries.find { it.getString("id") == sessionId } ?: return null
        return ChatSession(
            id = entry.getString("id"),
            title = entry.getString("title"),
            createdAt = entry.getLong("created_at"),
            updatedAt = entry.getLong("updated_at")
        )
    }

    /**
     * Touches the session to update its updatedAt timestamp.
     */
    fun touchSession(sessionId: String) {
        val entries = readIndex()
        val entry = entries.find { it.getString("id") == sessionId }
        if (entry != null) {
            entry.put("updated_at", System.currentTimeMillis())
            writeIndex(entries)
        }
    }

    /**
     * Alias for appendMessage for cleaner API usage.
     */
    fun addMessage(sessionId: String, role: String, content: String) {
        appendMessage(sessionId, role, content)
    }
}
