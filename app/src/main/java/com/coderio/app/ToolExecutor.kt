package com.coderio.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Executes tool calls from the AI and manages the agentic loop.
 *
 * Architecture:
 * - Receives tool_call JSON from the AI response
 * - Dispatches to the appropriate service (FileManager, FileEditor, Shell)
 * - Returns tool results formatted for the AI conversation
 * - Tracks tool invocations for UI display
 */
class ToolExecutor(
    private val fileManager: FileManagerService,
    private val fileEditor: FileEditorService,
    private val shellSession: ShellSession,
    private val workspacePath: String
) {
    companion object {
        private const val TAG = "ToolExecutor"
        const val MAX_TOOL_HISTORY = 20  // Keep last N tool invocations for context
    }

    /** A single tool invocation record for UI display */
    data class ToolInvocation(
        val toolName: String,
        val arguments: Map<String, Any>,
        val result: String,
        val success: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Get tool definitions in OpenAI function calling format */
    fun getToolDefinitions(): JSONArray {
        val tools = JSONArray()

        // list_directory
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "list_directory")
                put("description", "List contents of a directory. Returns file and folder names with sizes.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directory path relative to workspace root. Use empty string for root.")
                        })
                        put("max_depth", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Max subdirectory depth to show (default 1)")
                        })
                    })
                })
            })
        })

        // read_file
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "read_file")
                put("description", "Read the full contents of a file.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "File path relative to workspace root")
                        })
                        put("start_line", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Start reading from this line (1-based, optional)")
                        })
                        put("end_line", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Stop reading at this line (inclusive, optional)")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // write_file
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "write_file")
                put("description", "Write content to a file, creating it if it doesn't exist. Overwrites existing content.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "File path relative to workspace root")
                        })
                        put("content", JSONObject().apply {
                            put("type", "string")
                            put("description", "The full content to write to the file")
                        })
                    })
                    put("required", JSONArray().put("path").put("content"))
                })
            })
        })

        // edit_file
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "edit_file")
                put("description", "Edit a file by finding and replacing text. The old_text must appear exactly in the file.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "File path relative to workspace root")
                        })
                        put("old_text", JSONObject().apply {
                            put("type", "string")
                            put("description", "Exact text to find (must match uniquely)")
                        })
                        put("new_text", JSONObject().apply {
                            put("type", "string")
                            put("description", "Text to replace with")
                        })
                    })
                    put("required", JSONArray().put("path").put("old_text").put("new_text"))
                })
            })
        })

        // search_files
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "search_files")
                put("description", "Search for files by name pattern (case-insensitive substring match). Skips common build/cache directories.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "File name search query")
                        })
                        put("max_results", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Maximum results to return (default 50)")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })
        })

        // search_in_files (grep)
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "search_in_files")
                put("description", "Search inside file contents for text (like grep). Returns matching lines with file and line numbers.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "Text to search for in file contents")
                        })
                        put("file_pattern", JSONObject().apply {
                            put("type", "string")
                            put("description", "Optional file extension filter (e.g. 'kt', 'java', 'xml')")
                        })
                        put("max_results", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Maximum results to return (default 50)")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })
        })

        // get_file_info
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "get_file_info")
                put("description", "Get metadata about a file or directory (size, permissions, modification time, child count).")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Path relative to workspace root")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // create_directory
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "create_directory")
                put("description", "Create a new directory (including any necessary parent directories).")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directory path relative to workspace root")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // delete_path
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "delete_path")
                put("description", "Delete a file or empty directory. Use recursive=true to delete non-empty directories.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Path relative to workspace root to delete")
                        })
                        put("recursive", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "If true, delete directory and all contents (default false)")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // run_command
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "run_command")
                put("description", "Execute a shell command in the terminal. Use this for running builds, tests, installing packages, etc.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "The shell command to execute")
                        })
                        put("timeout", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Timeout in seconds (default 30)")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })
        })

        return tools
    }

    /** Get system prompt section describing available tools */
    fun getToolsSystemPrompt(): String {
        return """
## Available Tools

You have access to tools for interacting with the user's filesystem and running commands.
All file paths are relative to the workspace root unless specified otherwise.

### File Tools
- **list_directory(path)**: List directory contents
- **read_file(path, start_line?, end_line?)**: Read file contents
- **write_file(path, content)**: Create or overwrite a file
- **edit_file(path, old_text, new_text)**: Find and replace text in a file
- **search_files(query)**: Search for files by name
- **search_in_files(query, file_pattern?)**: Search file contents (grep)
- **get_file_info(path)**: Get file metadata
- **create_directory(path)**: Create a directory
- **delete_path(path, recursive?)**: Delete a file or directory
- **run_command(command, timeout?)**: Execute a shell command

### Tool Use Guidelines
1. **Read before edit**: Always read a file before editing it, to understand its structure.
2. **Verify changes**: After editing, verify the change was applied correctly.
3. **Be precise**: When editing, provide enough context in old_text to ensure uniqueness.
4. **Explain your actions**: Tell the user what you're doing and why.
5. **Handle errors**: If a tool call fails, analyze the error and try again with corrected parameters.
6. **Think step by step**: For complex tasks, break them into smaller steps.
""".trimIndent()
    }

    /**
     * Execute a tool call and return the result string.
     */
    suspend fun executeTool(toolName: String, arguments: JSONObject): ToolInvocation {
        Log.d(TAG, "Executing tool: $toolName with args: $arguments")

        val result = try {
            when (toolName) {
                "list_directory" -> executeListDirectory(arguments)
                "read_file" -> executeReadFile(arguments)
                "write_file" -> executeWriteFile(arguments)
                "edit_file" -> executeEditFile(arguments)
                "search_files" -> executeSearchFiles(arguments)
                "search_in_files" -> executeSearchInFiles(arguments)
                "get_file_info" -> executeGetFileInfo(arguments)
                "create_directory" -> executeCreateDirectory(arguments)
                "delete_path" -> executeDeletePath(arguments)
                "run_command" -> executeRunCommand(arguments)
                else -> "Error: Unknown tool '$toolName'"
            }
        } catch (e: Exception) {
            "Error executing $toolName: ${e.message}"
        }

        val success = !result.startsWith("Error")

        return ToolInvocation(
            toolName = toolName,
            arguments = jsonToMap(arguments),
            result = result,
            success = success
        )
    }

    // ── Individual tool implementations ─────────────────────────

    private suspend fun executeListDirectory(args: JSONObject): String {
        val path = args.optString("path", "")
        val maxDepth = args.optInt("max_depth", 1)

        return withContext(Dispatchers.IO) {
            val result = fileManager.listDirectory(path)
            result.fold(
                onSuccess = { entries ->
                    if (maxDepth <= 1) {
                        fileManager.formatDirectoryListing(entries)
                    } else {
                        // For deeper listing, format with indentation
                        val sb = StringBuilder()
                        for (entry in entries) {
                            val size = if (entry.isDirectory) "" else " (${fileManager.formatSize(entry.sizeBytes)})"
                            sb.appendLine("${entry.name}$size")
                            if (entry.isDirectory && maxDepth > 1) {
                                val subResult = fileManager.listDirectory(entry.path)
                                subResult.onSuccess { subEntries ->
                                    for (sub in subEntries.take(20)) {
                                        val subSize = if (sub.isDirectory) "/" else ""
                                        sb.appendLine("  └─ ${sub.name}$subSize")
                                    }
                                }
                            }
                        }
                        sb.toString().trimEnd()
                    }
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeReadFile(args: JSONObject): String {
        val path = args.getString("path")
        val startLine = args.optInt("start_line", -1)
        val endLine = args.optInt("end_line", -1)

        return withContext(Dispatchers.IO) {
            val result = fileManager.readFile(path, maxChars = 100_000)
            result.fold(
                onSuccess = { content ->
                    if (startLine > 0 || endLine > 0) {
                        val lines = content.lines()
                        val start = (startLine - 1).coerceAtLeast(0)
                        val end = if (endLine > 0) endLine.coerceAtMost(lines.size) else lines.size
                        val selected = lines.subList(start, end)
                        // Add line numbers
                        selected.mapIndexed { idx, line ->
                            "${start + idx + 1}│ $line"
                        }.joinToString("\n")
                    } else {
                        content
                    }
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeWriteFile(args: JSONObject): String {
        val path = args.getString("path")
        val content = args.getString("content")

        return withContext(Dispatchers.IO) {
            val result = fileEditor.writeFile(path, content)
            result.fold(
                onSuccess = { it },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeEditFile(args: JSONObject): String {
        val path = args.getString("path")
        val oldText = args.getString("old_text")
        val newText = args.getString("new_text")

        return withContext(Dispatchers.IO) {
            val result = fileEditor.editFile(path, oldText, newText)
            result.fold(
                onSuccess = { editResult ->
                    if (editResult.success) {
                        editResult.message
                    } else {
                        "Edit failed: ${editResult.message}"
                    }
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeSearchFiles(args: JSONObject): String {
        val query = args.getString("query")
        val maxResults = args.optInt("max_results", 50)

        return withContext(Dispatchers.IO) {
            val result = fileManager.searchFiles(query, maxResults)
            result.fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        "No files found matching '$query'"
                    } else {
                        entries.joinToString("\n") { entry ->
                            val icon = if (entry.isDirectory) "📁" else "📄"
                            "$icon ${entry.path}"
                        }
                    }
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeSearchInFiles(args: JSONObject): String {
        val query = args.getString("query")
        val filePattern = args.optString("file_pattern", "")
        val maxResults = args.optInt("max_results", 50)

        return withContext(Dispatchers.IO) {
            val result = fileManager.searchContent(query, maxResults)
            result.fold(
                onSuccess = { matches ->
                    val filtered = if (filePattern.isNotEmpty()) {
                        matches.filter { it.filePath.endsWith(".$filePattern", ignoreCase = true) }
                    } else {
                        matches
                    }

                    if (filtered.isEmpty()) {
                        "No matches found for '$query'"
                    } else {
                        filtered.joinToString("\n") { match ->
                            "${match.filePath}:${match.lineNumber}: ${match.lineContent}"
                        }
                    }
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeGetFileInfo(args: JSONObject): String {
        val path = args.getString("path")

        return withContext(Dispatchers.IO) {
            val result = fileManager.getFileInfo(path)
            result.fold(
                onSuccess = { info ->
                    buildString {
                        appendLine("Name: ${info.name}")
                        appendLine("Path: ${info.path}")
                        appendLine("Type: ${if (info.isDirectory) "directory" else "file"}")
                        appendLine("Size: ${fileManager.formatSize(info.sizeBytes)}")
                        appendLine("Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(info.modifiedMs))}")
                        appendLine("Readable: ${info.readable}")
                        appendLine("Writable: ${info.writable}")
                        if (info.extension.isNotEmpty()) appendLine("Extension: .${info.extension}")
                        if (info.children != null) appendLine("Children: ${info.children}")
                    }.trimEnd()
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeCreateDirectory(args: JSONObject): String {
        val path = args.getString("path")

        return withContext(Dispatchers.IO) {
            val file = fileManager.resolvePath(path)
                ?: return@withContext "Error: Invalid path: $path"

            try {
                if (file.exists()) {
                    if (file.isDirectory) "Directory already exists: $path"
                    else "Error: A file already exists at this path: $path"
                } else {
                    file.mkdirs()
                    "Directory created: $path"
                }
            } catch (e: Exception) {
                "Error creating directory: ${e.message}"
            }
        }
    }

    private suspend fun executeDeletePath(args: JSONObject): String {
        val path = args.getString("path")
        val recursive = args.optBoolean("recursive", false)

        return withContext(Dispatchers.IO) {
            val result = if (recursive) {
                fileEditor.deleteRecursive(path)
            } else {
                fileEditor.deletePath(path)
            }
            result.fold(
                onSuccess = { it },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    private suspend fun executeRunCommand(args: JSONObject): String {
        val command = args.getString("command")
        val timeout = args.optInt("timeout", 30)

        return withContext(Dispatchers.IO) {
            try {
                val result = shellSession.sendCommand(command, timeout * 1000L)
                result.trimEnd()
            } catch (e: Exception) {
                "Error running command: ${e.message}"
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> value.toString()
                is JSONArray -> value.toString()
                else -> value.toString()
            }
        }
        return map
    }
}
