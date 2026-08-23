package com.coderio.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI chat service compatible with OpenAI-format APIs (OpenCode Zen, Groq, OpenAI, etc.)
 * Supports both blocking and streaming (SSE) completions.
 */
class AiChatService(context: Context) {

    data class ChatMessage(val role: String, val content: String)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("coder_io_settings", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """You are an expert programming assistant embedded in a mobile terminal app called coder.io. You help users with:
- Writing and explaining code
- Debugging and fixing issues
- Command-line usage and shell commands
- System administration
- Android development

Be concise, practical, and provide code examples when relevant. Use markdown formatting for code blocks."""

    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""
    fun getApiUrl(): String = prefs.getString("api_url", "https://opencode.ai/zen/v1") ?: ""
    fun getModel(): String = prefs.getString("model", "big-pickle") ?: ""

    fun saveSettings(apiKey: String, apiUrl: String, model: String) {
        prefs.edit()
            .putString("api_key", apiKey)
            .putString("api_url", apiUrl)
            .putString("model", model)
            .apply()
    }

    // ── Build messages array ───────────────────────────────────

    private fun buildMessagesArray(
        userMessage: String,
        conversationHistory: List<ChatMessage>? = null
    ): JSONArray {
        val messagesArray = JSONArray()

        // System prompt
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // Conversation history
        if (conversationHistory != null) {
            for (msg in conversationHistory) {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        // Current user message
        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        return messagesArray
    }

    private fun buildRequestBody(
        model: String,
        messagesArray: JSONArray,
        stream: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 2048)
            if (stream) put("stream", true)
        }
    }

    // ── Blocking chat ──────────────────────────────────────────

    suspend fun chat(messages: List<ChatMessage>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = getApiKey()
            val apiUrl = getApiUrl()
            val model = getModel()

            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key not configured. Go to Settings tab."))
            }

            val messagesArray = JSONArray()

            // System prompt
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            // Conversation history
            for (msg in messages) {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("temperature", 0.7)
                put("max_tokens", 2048)
            }

            val url = apiUrl.trimEnd('/') + "/chat/completions"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (apiUrl.contains("opencode.ai")) {
                        addHeader("User-Agent", "opencode/1.18.16")
                    }
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errJson = JSONObject(responseBody)
                    errJson.getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP ${response.code}: $responseBody"
                }
                return@withContext Result.failure(Exception("API error: $errorMsg"))
            }

            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) {
                return@withContext Result.failure(Exception("No response from AI"))
            }

            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    // ── Companion: streamChat ──────────────────────────────────

    companion object {

        /**
         * Streaming chat using Server-Sent Events (SSE).
         * Calls [onToken] for each content delta, [onDone] when complete,
         * or [onError] on failure.
         */
        suspend fun streamChat(
            context: Context,
            userMessage: String,
            conversationHistory: List<ChatHistoryManager.ChatMessage>? = null,
            onToken: (String) -> Unit,
            onDone: () -> Unit,
            onError: (String) -> Unit
        ) = withContext(Dispatchers.IO) {
            try {
                val service = AiChatService(context)
                val apiKey = service.getApiKey()
                val apiUrl = service.getApiUrl()
                val model = service.getModel()

                if (apiKey.isBlank()) {
                    onError("API key not configured. Open Settings to add one.")
                    return@withContext
                }

                // Convert ChatHistoryManager.ChatMessage → AiChatService.ChatMessage
                val history = conversationHistory?.map {
                    AiChatService.ChatMessage(role = it.role, content = it.content)
                }

                val messagesArray = service.buildMessagesArray(userMessage, history)
                val body = service.buildRequestBody(model, messagesArray, stream = true)

                val url = apiUrl.trimEnd('/') + "/chat/completions"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .apply {
                        if (apiUrl.contains("opencode.ai")) {
                            addHeader("User-Agent", "opencode/1.18.16")
                        }
                    }
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = service.client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorMsg = try {
                        JSONObject(errorBody).getJSONObject("error").getString("message")
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $errorBody"
                    }
                    onError("API error: $errorMsg")
                    return@withContext
                }

                // Read SSE stream
                val source = response.body?.source() ?: run {
                    onError("Empty response body")
                    return@withContext
                }

                val reader = source.buffer()
                var completeResponse = StringBuilder()

                try {
                    while (!reader.exhausted()) {
                        val line = reader.readUtf8Line() ?: continue

                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") break

                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices") ?: continue
                                if (choices.length() == 0) continue

                                val delta = choices.getJSONObject(0)
                                    .optJSONObject("delta") ?: continue

                                if (delta.has("content") && !delta.isNull("content")) {
                                    val token = delta.getString("content")
                                    completeResponse.append(token)
                                    onToken(token)
                                }
                            } catch (_: Exception) {
                                // Skip malformed SSE chunks
                            }
                        }
                    }
                } catch (e: IOException) {
                    // Connection may close before [DONE] — treat accumulated as complete
                } finally {
                    response.close()
                }

                if (completeResponse.isNotEmpty()) {
                    onDone()
                } else {
                    // Fallback: try non-streaming if SSE produced nothing
                    val fallbackResult = service.chat(
                        listOf(AiChatService.ChatMessage("user", userMessage))
                    )
                    fallbackResult.fold(
                        onSuccess = { text ->
                            onToken(text)
                            onDone()
                        },
                        onFailure = { e ->
                            onError(e.message ?: "Unknown error")
                        }
                    )
                }

            } catch (e: Exception) {
                onError("Network error: ${e.message}")
            }
        }
    }
}
