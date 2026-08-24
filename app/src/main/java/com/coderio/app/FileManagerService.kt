package com.coderio.app

import java.io.File

/**
 * Provides read-only file system operations for the AI agent:
 * directory listing, file info, content reading, and file search.
 *
 * All paths are resolved relative to [basePath] (the configured workspace).
 * Security: paths are canonicalized to prevent traversal outside basePath.
 */
class FileManagerService(val basePath: String) {

    data class FileEntry(
        val name: String,
        val path: String,       // relative to basePath
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val extension: String
    )

    data class FileInfo(
        val name: String,
        val path: String,
        val absolutePath: String,
        val isDirectory: Boolean,
        val isFile: Boolean,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val readable: Boolean,
        val writable: Boolean,
        val extension: String,
        val mimeType: String?,
        val children: Int?       // non-null only for directories
    )

    // ── Path resolution ─────────────────────────────────────────

    /**
     * Resolve a user-supplied path to an absolute [File], ensuring
     * it stays within [basePath]. Returns null if path escapes root.
     */
    fun resolvePath(input: String): File? {
        val trimmed = input.trim()
        val raw = if (trimmed.startsWith("~")) {
            trimmed.replaceFirst("~", basePath)
        } else if (trimmed.startsWith("/")) {
            trimmed
        } else {
            "$basePath/$trimmed"
        }
        val canonical = try {
            File(raw).canonicalFile
        } catch (_: Exception) {
            return null
        }
        // Security: must be under basePath (or basePath itself)
        val baseCanonical = File(basePath).canonicalFile
        if (canonical.path == baseCanonical.path || canonical.path.startsWith(baseCanonical.path + "/")) {
            return canonical
        }
        return null  // path escapes workspace
    }

    fun resolveRelative(path: String, relativeTo: String): File? {
        return resolvePath("$relativeTo/$path")
    }

    // ── Directory listing ───────────────────────────────────────

    /**
     * List directory contents, sorted: directories first, then files, alphabetically.
     * Returns empty list if path is not a directory or doesn't exist.
     */
    fun listDirectory(path: String = ""): Result<List<FileEntry>> {
        val dir = resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!dir.exists()) return Result.failure(Exception("Path does not exist: $path"))
        if (!dir.isDirectory) return Result.failure(Exception("Not a directory: $path"))

        val entries = try {
            dir.listFiles()?.map { f ->
                val relPath = if (path.isEmpty()) f.name else "$path/${f.name}"
                FileEntry(
                    name = f.name,
                    path = relPath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0,
                    modifiedMs = f.lastModified(),
                    extension = if (f.isFile) f.extension else ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            return Result.failure(Exception("Cannot list directory: ${e.message}"))
        }

        val sorted = entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        return Result.success(sorted)
    }

    // ── File info ───────────────────────────────────────────────

    fun getFileInfo(path: String): Result<FileInfo> {
        val file = resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!file.exists()) return Result.failure(Exception("Path does not exist: $path"))

        val childCount = if (file.isDirectory) file.listFiles()?.size else null
        return Result.success(FileInfo(
            name = file.name,
            path = file.path.removePrefix(File(basePath).canonicalPath).trimStart('/'),
            absolutePath = file.canonicalPath,
            isDirectory = file.isDirectory,
            isFile = file.isFile,
            sizeBytes = file.length(),
            modifiedMs = file.lastModified(),
            readable = file.canRead(),
            writable = file.canWrite(),
            extension = file.extension,
            mimeType = null,  // Android MIME detection not available without context here
            children = childCount
        ))
    }

    // ── File content reading ────────────────────────────────────

    /**
     * Read a file's content as text. Returns truncated content if file is very large.
     */
    fun readFile(path: String, maxChars: Int = 50_000): Result<String> {
        val file = resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!file.exists()) return Result.failure(Exception("File does not exist: $path"))
        if (!file.isFile) return Result.failure(Exception("Not a file: $path"))
        if (!file.canRead()) return Result.failure(Exception("File is not readable: $path"))

        return try {
            val text = file.readText()
            if (text.length > maxChars) {
                Result.success(text.substring(0, maxChars) + "\n\n... [truncated at $maxChars chars, total ${text.length}]")
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to read file: ${e.message}"))
        }
    }

    // ── File search by name ─────────────────────────────────────

    /**
     * Recursively search for files whose name matches [query] (case-insensitive substring).
     * Returns up to [maxResults] entries.
     */
    fun searchFiles(query: String, maxResults: Int = 50): Result<List<FileEntry>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Result.failure(Exception("Search query is empty"))

        val baseDir = File(basePath)
        val results = mutableListOf<FileEntry>()

        try {
            searchRecursive(baseDir, q, results, maxResults, basePath)
        } catch (e: Exception) {
            return Result.failure(Exception("Search failed: ${e.message}"))
        }

        return Result.success(results)
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<FileEntry>, max: Int, basePath: String) {
        if (results.size >= max) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (results.size >= max) return
            // Skip hidden dirs and common build/cache dirs
            if (child.isDirectory && (child.name.startsWith(".") || child.name in listOf("node_modules", "build", "__pycache__", ".git", "bin", "obj"))) {
                continue
            }
            if (child.name.lowercase().contains(query)) {
                val relPath = child.path.removePrefix(basePath).trimStart('/')
                results.add(FileEntry(
                    name = child.name,
                    path = relPath,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0,
                    modifiedMs = child.lastModified(),
                    extension = if (child.isFile) child.extension else ""
                ))
            }
            if (child.isDirectory && results.size < max) {
                searchRecursive(child, query, results, max, basePath)
            }
        }
    }

    // ── Content search (grep-like) ──────────────────────────────

    data class ContentMatch(
        val filePath: String,
        val lineNumber: Int,
        val lineContent: String
    )

    /**
     * Search file contents for lines containing [query] (case-insensitive).
     * Searches common text files; skips binaries, .git, node_modules, etc.
     */
    fun searchContent(query: String, maxResults: Int = 100): Result<List<ContentMatch>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Result.failure(Exception("Search query is empty"))

        val baseDir = File(basePath)
        val results = mutableListOf<ContentMatch>()

        try {
            searchContentRecursive(baseDir, q, results, maxResults, basePath)
        } catch (e: Exception) {
            return Result.failure(Exception("Content search failed: ${e.message}"))
        }

        return Result.success(results)
    }

    private val textExtensions = setOf(
        "kt", "java", "xml", "json", "gradle", "properties", "txt", "md",
        "py", "js", "ts", "jsx", "tsx", "html", "css", "scss", "less",
        "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php", "swift",
        "m", "r", "sql", "sh", "bash", "zsh", "yaml", "yml", "toml",
        "ini", "cfg", "conf", "env", "gitignore", "dockerfile",
        "csv", "log", "cfg", "makefile", "cmake"
    )

    private fun searchContentRecursive(dir: File, query: String, results: MutableList<ContentMatch>, max: Int, basePath: String) {
        if (results.size >= max) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (results.size >= max) return
            if (child.isDirectory) {
                if (child.name.startsWith(".") || child.name in listOf("node_modules", "build", "__pycache__", ".git")) continue
                searchContentRecursive(child, query, results, max, basePath)
            } else if (child.isFile && child.length() < 1_000_000) {
                val ext = child.extension.lowercase()
                if (ext in textExtensions || ext.isEmpty()) {
                    try {
                        child.readLines().forEachIndexed { idx, line ->
                            if (results.size < max && line.lowercase().contains(query)) {
                                val relPath = child.path.removePrefix(basePath).trimStart('/')
                                results.add(ContentMatch(
                                    filePath = relPath,
                                    lineNumber = idx + 1,
                                    lineContent = line.trimEnd().take(200)
                                ))
                            }
                        }
                    } catch (_: Exception) {
                        // Skip unreadable files
                    }
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Format a list of FileEntries for display in a tool response.
     */
    fun formatDirectoryListing(entries: List<FileEntry>): String {
        if (entries.isEmpty()) return "(empty directory)"
        val sb = StringBuilder()
        for (e in entries) {
            val icon = if (e.isDirectory) "📁" else "📄"
            val size = if (e.isDirectory) "" else "  ${formatSize(e.sizeBytes)}"
            sb.appendLine("$icon  ${e.name}$size")
        }
        return sb.toString().trimEnd()
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))}MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))}GB"
        }
    }
}
