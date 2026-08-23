package com.coderio.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI chat service compatible with OpenAI-format APIs (OpenCode Zen, Groq, OpenAI, etc.)
 */
class AiChatService(context: Context) {

    data class ChatMessage(val role: String, val content: String)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("coder_io_settings", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """You are an expert programming assistant embedded in a mobile terminal app called coder.io. You help users with:
- Writing and explaining code
- Debugging and fixing issues
- Command-line usage and shell commands
- System administration
- Android development

Be concise, practical, and provide code examples when relevant. Use markdown formatting for code blocks.""""

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

    suspend fun chat(messages: List<ChatMessage>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = getApiKey()
            val apiUrl = getApiUrl()
            val model = getModel()

            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key not configured. Go to Settings tab."))
            }

            // Build request body
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
                // Zen gates its free models to official OpenCode clients by User-Agent
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
}
