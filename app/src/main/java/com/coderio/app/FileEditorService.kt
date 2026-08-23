package com.coderio.app

import java.io.File

/**
 * Provides mutable file system operations for the AI agent:
 * creating, writing, editing, and deleting files and directories.
 *
 * All paths are resolved via [FileManagerService.resolvePath] for security.
 */
class FileEditorService(private val fileManager: FileManagerService) {

    data class EditResult(
        val filePath: String,
        val success: Boolean,
        val message: String,
        val linesChanged: Int = 0
    )

    // ── Write / Create ──────────────────────────────────────────

    /**
     * Write [content] to a file, creating it (and parent dirs) if needed.
     * If the file exists, it is overwritten entirely.
     */
    fun writeFile(path: String, content: String): Result<String> {
        val file = fileManager.resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        return try {
            // Create parent directories
            file.parentFile?.mkdirs()
            file.writeText(content)
            Result.success("File written successfully: ${file.name} (${content.length} chars)")
        } catch (e: Exception) {
            Result.failure(Exception("Failed to write file: ${e.message}"))
        }
    }

    /**
     * Append [content] to the end of a file.
     * Creates the file (and parent dirs) if it doesn't exist.
     */
    fun appendFile(path: String, content: String): Result<String> {
        val file = fileManager.resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        return try {
            file.parentFile?.mkdirs()
            file.appendText(content)
            Result.success("Content appended to: ${file.name}")
        } catch (e: Exception) {
            Result.failure(Exception("Failed to append to file: ${e.message}"))
        }
    }

    // ── Edit (find & replace) ───────────────────────────────────

    /**
     * Replace the first occurrence of [oldString] with [newString] in the file.
     *
     * @param path File path
     * @param oldString Exact string to find (must be unique if replaceAll=false)
     * @param newString Replacement string
     * @param replaceAll If true, replace all occurrences
     */
    fun editFile(path: String, oldString: String, newString: String, replaceAll: Boolean = false): Result<EditResult> {
        val file = fileManager.resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!file.exists()) return Result.failure(Exception("File does not exist: $path"))
        if (!file.canRead() || !file.canWrite()) return Result.failure(Exception("File is not read/write: $path"))

        return try {
            val content = file.readText()
            if (!content.contains(oldString)) {
                return Result.success(EditResult(
                    filePath = path,
                    success = false,
                    message = "String not found in file. Make sure old_string matches exactly."
                ))
            }

            val newContent: String
            val count: Int
            if (replaceAll) {
                val occurrences = content.split(oldString).size - 1
                newContent = content.replace(oldString, newString)
                count = occurrences
            } else {
                // Verify uniqueness
                val firstIndex = content.indexOf(oldString)
                val secondIndex = content.indexOf(oldString, firstIndex + 1)
                if (secondIndex != -1) {
                    return Result.success(EditResult(
                        filePath = path,
                        success = false,
                        message = "old_string is not unique in the file. It appears at least twice. " +
                                "Use replace_all=true or provide more context to make it unique."
                    ))
                }
                newContent = content.replaceFirst(oldString, newString)
                count = 1
            }

            file.writeText(newContent)
            Result.success(EditResult(
                filePath = path,
                success = true,
                message = "Replaced $count occurrence(s) in ${file.name}",
                linesChanged = count
            ))
        } catch (e: Exception) {
            Result.failure(Exception("Edit failed: ${e.message}"))
        }
    }

    /**
     * Insert [content] at a specific line number (1-based).
     * If line is 0, inserts at the beginning.
     * If line exceeds file length, appends at end.
     */
    fun insertAtLine(path: String, lineNumber: Int, content: String): Result<String> {
        val file = fileManager.resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!file.exists()) return Result.failure(Exception("File does not exist: $path"))

        return try {
            val lines = file.readText().lines().toMutableList()
            val insertIndex = (lineNumber - 1).coerceIn(0, lines.size)
            val newLines = content.lines()
            lines.addAll(insertIndex, newLines)
            file.writeText(lines.joinToString("\n"))
            Result.success("Inserted ${newLines.size} line(s) at line $lineNumber in ${file.name}")
        } catch (e: Exception) {
            Result.failure(Exception("Insert failed: ${e.message}"))
        }
    }

    // ── Delete ──────────────────────────────────────────────────

    /**
     * Delete a file or empty directory.
     */
    fun deletePath(path: String): Result<String> {
        val file = fileManager.resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!file.exists()) return Result.failure(Exception("Path does not exist: $path"))
        if (file.path == File(fileManager.basePath).canonicalPath) {
            return Result.failure(Exception("Cannot delete workspace root!"))
        }

        return try {
            if (file.isDirectory) {
                val children = file.listFiles()?.size ?: 0
                if (children > 0) {
                    return Result.failure(Exception("Directory is not empty ($children items). Use recursive delete or delete contents first."))
                }
                file.delete()
                Result.success("Directory deleted: ${file.name}")
            } else {
                val name = file.name
                file.delete()
                Result.success("File deleted: $name")
            }
        } catch (e: Exception) {
            Result.failure(Exception("Delete failed: ${e.message}"))
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     * **Dangerous** — refuses to delete workspace root.
     */
    fun deleteRecursive(path: String): Result<String> {
        val file = fileManager.resolvePath(path) ?: return Result.failure(Exception("Invalid path: $path"))
        if (!file.exists()) return Result.failure(Exception("Path does not exist: $path"))
        if (file.path == File(fileManager.basePath).canonicalPath) {
            return Result.failure(Exception("Cannot delete workspace root!"))
        }

        return try {
            val count = countFiles(file)
            file.deleteRecursively()
            Result.success("Deleted ${file.name} and all contents ($count items)")
        } catch (e: Exception) {
            Result.failure(Exception("Recursive delete failed: ${e.message}"))
        }
    }

    private fun countFiles(dir: File): Int {
        if (dir.isFile) return 1
        return (dir.listFiles()?.sumOf { countFiles(it) } ?: 0) + 1
    }

    // ── Rename / Move ───────────────────────────────────────────

    fun moveOrRename(source: String, destination: String): Result<String> {
        val srcFile = fileManager.resolvePath(source) ?: return Result.failure(Exception("Invalid source path: $source"))
        val dstFile = fileManager.resolvePath(destination) ?: return Result.failure(Exception("Invalid destination path: $destination"))
        if (!srcFile.exists()) return Result.failure(Exception("Source does not exist: $source"))
        if (dstFile.exists()) return Result.failure(Exception("Destination already exists: $destination"))

        return try {
            srcFile.copyRecursively(dstFile)
            srcFile.deleteRecursively()
            Result.success("Moved ${srcFile.name} → ${dstFile.name}")
        } catch (e: Exception) {
            Result.failure(Exception("Move failed: ${e.message}"))
        }
    }

    // ── Copy ────────────────────────────────────────────────────

    fun copyPath(source: String, destination: String): Result<String> {
        val srcFile = fileManager.resolvePath(source) ?: return Result.failure(Exception("Invalid source path: $source"))
        val dstFile = fileManager.resolvePath(destination) ?: return Result.failure(Exception("Invalid destination path: $destination"))
        if (!srcFile.exists()) return Result.failure(Exception("Source does not exist: $source"))

        return try {
            srcFile.copyRecursively(dstFile)
            Result.success("Copied ${srcFile.name} → ${dstFile.name}")
        } catch (e: Exception) {
            Result.failure(Exception("Copy failed: ${e.message}"))
        }
    }
}
