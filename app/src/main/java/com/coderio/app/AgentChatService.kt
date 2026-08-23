package com.coderio.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Agentic chat service that implements the tool-calling loop.
 *
 * Flow:
 * 1. Send user message (with tool definitions) to AI
 * 2. If AI responds with tool_calls → execute them, append results, go to step 1
 * 3. If AI responds with content → return the final answer
 *
 * Supports streaming for the final text response while tool calls are executed silently.
 */
class AgentChatService(
    private val context: android.content.Context,
    private val toolExecutor: ToolExecutor
) {
    companion object {
        private const val TAG = "AgentChatService"
        private const val MAX_ITERATIONS = 20  // Safety limit to prevent infinite loops
    }

    data class AgentResult(
        val finalResponse: String,
        val toolInvocations: List<ToolExecutor.ToolInvocation>,
        val iterations: Int
    )

    /** Callback for streaming the final response text */
    interface AgentCallback {
        fun onStatusUpdate(status: String)
        fun onToolStarted(invocation: ToolExecutor.ToolInvocation)
        fun onToolCompleted(invocation: ToolExecutor.ToolInvocation)
        fun onToken(token: String)
        fun onDone(result: AgentResult)
        fun onError(error: String)
    }

    private fun getHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Run the full agentic loop.
     * This is the main entry point. Call this when the user sends a message.
     */
    suspend fun runAgent(
        userMessage: String,
        conversationHistory: List<AiChatService.ChatMessage>,
        callback: AgentCallback,
        workspacePath: String
    ) = withContext(Dispatchers.IO) {
        val service = AiChatService(context)
        val apiKey = service.getApiKey()
        val apiUrl = service.getApiUrl()
        val model = service.getModel()

        if (apiKey.isBlank()) {
            callback.onError("API key not configured. Go to Settings.")
            return@withContext
        }

        val allToolInvocations = mutableListOf<ToolExecutor.ToolInvocation>()

        // Build initial messages
        val messagesArray = buildMessagesWithTools(conversationHistory, userMessage)
        val toolsJson = toolExecutor.getToolDefinitions()

        var iterations = 0

        try {
            while (iterations < MAX_ITERATIONS) {
                iterations++
                callback.onStatusUpdate("Thinking... (step $iterations)")

                Log.d(TAG, "Iteration $iterations - sending ${messagesArray.length()} messages")

                // Build request
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesArray)
                    put("temperature", 0.7)
                    put("max_tokens", 4096)
                    put("tools", toolsJson)
                    put("tool_choice", "auto")
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

                val response = getHttpClient().newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errJson = JSONObject(responseBody)
                        errJson.getJSONObject("error").getString("message")
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $responseBody"
                    }
                    callback.onError("API error: $errorMsg")
                    return@withContext
                }

                val json = JSONObject(responseBody)
                val choices = json.getJSONArray("choices")
                if (choices.length() == 0) {
                    callback.onError("No response from AI")
                    return@withContext
                }

                val message = choices.getJSONObject(0).getJSONObject("message")
                val finishReason = choices.getJSONObject(0).optString("finish_reason", "")

                // Check if there are tool calls
                val toolCalls = message.optJSONArray("tool_calls")

                if (toolCalls != null && toolCalls.length() > 0) {
                    // AI wants to use tools — add the assistant message with tool_calls
                    messagesArray.put(JSONObject().apply {
                        put("role", "assistant")
                        put("tool_calls", toolCalls)
                    })

                    // Execute each tool call
                    for (i in 0 until toolCalls.length()) {
                        val toolCall = toolCalls.getJSONObject(i)
                        val function = toolCall.getJSONObject("function")
                        val toolName = function.getString("name")
                        val toolId = toolCall.getString("id")
                        val arguments = try {
                            JSONObject(function.getString("arguments"))
                        } catch (_: Exception) {
                            JSONObject()
                        }

                        callback.onStatusUpdate("Executing: $toolName")

                        // Execute the tool
                        val invocation = toolExecutor.executeTool(toolName, arguments)
                        allToolInvocations.add(invocation)
                        callback.onToolCompleted(invocation)

                        // Add tool result to messages
                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", toolId)
                            put("content", invocation.result)
                        })

                        Log.d(TAG, "Tool $toolName: ${if (invocation.success) "success" else "failed"}")
                    }

                    // Continue loop — AI will process tool results and either call more tools or respond
                    continue
                }

                // No tool calls — we have the final text response
                val content = message.optString("content", "")
                if (content.isNotEmpty()) {
                    callback.onDone(AgentResult(
                        finalResponse = content,
                        toolInvocations = allToolInvocations,
                        iterations = iterations
                    ))
                } else {
                    callback.onError("Empty response from AI after $iterations iterations")
                }
                return@withContext
            }

            // If we hit the iteration limit
            callback.onError("Agent reached maximum iterations ($MAX_ITERATIONS). Last response may be incomplete.")

        } catch (e: Exception) {
            Log.e(TAG, "Agent error", e)
            callback.onError("Agent error: ${e.message}")
        }
    }

    /**
     * Build messages array with tool definitions baked into the system prompt.
     * This is a fallback for models that don't support the `tools` parameter.
     */
    private fun buildMessagesWithTools(
        history: List<AiChatService.ChatMessage>,
        userMessage: String
    ): JSONArray {
        val messages = JSONArray()

        // System prompt with tool descriptions
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", """
You are an expert programming assistant embedded in a mobile terminal app called coder.io.

You have access to tools for interacting with the user's filesystem and running commands.
All file paths are relative to the workspace root.

### Available Tools
- **list_directory(path)**: List directory contents
- **read_file(path, start_line?, end_line?)**: Read file contents (line numbers optional)
- **write_file(path, content)**: Create or overwrite a file
- **edit_file(path, old_text, new_text)**: Find and replace text
- **search_files(query)**: Search for files by name
- **search_in_files(query, file_pattern?)**: Search file contents (grep)
- **get_file_info(path)**: Get file metadata
- **create_directory(path)**: Create a directory
- **delete_path(path, recursive?)**: Delete a file or directory
- **run_command(command, timeout?)**: Execute a shell command

### Guidelines
1. Read before edit: Always read a file before editing it.
2. Verify changes: After editing, verify the change was applied.
3. Be precise: Provide enough context in old_text for uniqueness.
4. Explain actions: Tell the user what you're doing and why.
5. Think step by step for complex tasks.
""".trimIndent())
        })

        // History
        for (msg in history) {
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        // User message
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        return messages
    }
}
