package com.coderio.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Central workspace configuration.
 * All services (FileManager, ToolExecutor, ShellSession, AgentChat) use this path.
 */
object WorkspaceConfig {

    /** The main workspace directory — user-visible, persistent. */
    const val WORKSPACE_PATH = "/storage/emulated/0/.Coder.io"

    /** Ensure the workspace directory exists. Call from Application or MainActivity.onCreate(). */
    fun ensureWorkspace(context: Context): File {
        val dir = File(WORKSPACE_PATH)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Returns true if the app has permission to access the workspace directory.
     * On Android 11+ this requires MANAGE_EXTERNAL_STORAGE.
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // On older versions, WRITE/READ_EXTERNAL_STORAGE are enough (declared in manifest)
            true
        }
    }

    /**
     * Launch the system settings page where the user can grant
     * "All files access" for this app (Android 11+).
     */
    fun requestStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
