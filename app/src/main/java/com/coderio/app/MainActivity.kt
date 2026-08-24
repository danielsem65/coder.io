package com.coderio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var drawerLayout: DrawerLayout

    private val terminalFragment = TerminalFragment()
    private val chatFragment = ChatFragment()
    private val settingsFragment = SettingsFragment()
    private val fragments = listOf(terminalFragment, chatFragment, settingsFragment)

    // Chat history
    lateinit var chatHistoryManager: ChatHistoryManager
        private set
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var rvSessions: RecyclerView
    private lateinit var tvEmptyState: TextView

    // Active chat session ID (exposed for ChatFragment)
    var activeSessionId: String? = null
        set(value) {
            field = value
            chatHistoryManager.setActiveSessionId(value)
        }

    /** Callbacks that ChatFragment can register for session events */
    var onSessionChanged: ((sessionId: String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_nav)
        drawerLayout = findViewById(R.id.drawer_layout)

        // Chat history manager
        chatHistoryManager = ChatHistoryManager(this)
        activeSessionId = chatHistoryManager.getActiveSessionId()

        // Setup ViewPager with fragments
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        viewPager.isUserInputEnabled = false // Disable swipe — nav only

        // Bottom nav -> ViewPager
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_terminal -> {
                    viewPager.currentItem = 0
                    drawerLayout.closeDrawer(GravityCompat.START, true)
                    true
                }
                R.id.nav_chat -> {
                    viewPager.currentItem = 1
                    true
                }
                R.id.nav_settings -> {
                    viewPager.currentItem = 2
                    drawerLayout.closeDrawer(GravityCompat.START, true)
                    true
                }
                else -> false
            }
        }

        // ViewPager -> Bottom nav
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
            }
        })

        setupDrawer()
        ensureWorkspace()
    }

    // ── Workspace & permissions ───────────────────────────────

    private fun ensureWorkspace() {
        if (!WorkspaceConfig.hasStoragePermission()) {
            WorkspaceConfig.requestStoragePermission(this)
            // We'll check again in onResume
            return
        }
        val dir = WorkspaceConfig.ensureWorkspace(this)
        if (!dir.exists()) {
            Toast.makeText(this, "Cannot create workspace at ${WorkspaceConfig.WORKSPACE_PATH}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after user returns from settings
        if (WorkspaceConfig.hasStoragePermission()) {
            WorkspaceConfig.ensureWorkspace(this)
        }
    }

    // ── Drawer ──────────────────────────────────────────────────

    private fun setupDrawer() {
        val btnNewChat = findViewById<MaterialButton>(R.id.btn_new_chat)
        val btnCloseDrawer = findViewById<ImageButton>(R.id.btn_close_drawer)
        rvSessions = findViewById(R.id.rv_sessions)
        tvEmptyState = findViewById(R.id.tv_empty_state)

        sessionAdapter = SessionAdapter(
            onItemClick = { session ->
                activeSessionId = session.id
                chatHistoryManager.setActiveSessionId(session.id)
                drawerLayout.closeDrawer(GravityCompat.START, true)
                onSessionChanged?.invoke(session.id)
            },
            onDeleteClick = { session ->
                AlertDialog.Builder(this, R.style.Theme_CoderIO)
                    .setTitle("Delete chat")
                    .setMessage("Delete \"${session.title}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        chatHistoryManager.deleteSession(session.id)
                        if (activeSessionId == session.id) {
                            activeSessionId = null
                            chatHistoryManager.setActiveSessionId(null)
                            onSessionChanged?.invoke(null)
                        }
                        refreshSessionList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = sessionAdapter

        btnNewChat.setOnClickListener {
            activeSessionId = chatHistoryManager.createSession("New chat")
            drawerLayout.closeDrawer(GravityCompat.START, true)
            onSessionChanged?.invoke(activeSessionId)
            refreshSessionList()
        }

        btnCloseDrawer.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START, true)
        }

        refreshSessionList()
    }

    fun openDrawer() {
        refreshSessionList()
        drawerLayout.openDrawer(GravityCompat.START, true)
    }

    fun refreshSessionList() {
        val sessions = chatHistoryManager.getAllSessions()
        sessionAdapter.submitList(sessions)
        tvEmptyState.visibility = if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        rvSessions.visibility = if (sessions.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ── Navigation helpers ──────────────────────────────────────

    fun navigateToChat() {
        viewPager.currentItem = 1
        bottomNav.menu.getItem(1).isChecked = true
    }

    // ── Back press ──────────────────────────────────────────────

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START, true)
            return
        }
        if (viewPager.currentItem > 0) {
            viewPager.currentItem = 0
            bottomNav.menu.getItem(0).isChecked = true
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Share terminal output as a text file.
     */
    fun shareOutput(text: String) {
        try {
            val dir = File(cacheDir, "shares")
            dir.mkdirs()
            val file = File(dir, "coder_io_${timestamp()}.txt")
            file.writeText(text)

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "coder.io output")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share output"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
