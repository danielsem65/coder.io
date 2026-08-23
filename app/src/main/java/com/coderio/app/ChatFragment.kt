package com.coderio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var btnOpenDrawer: ImageButton
    private lateinit var sessionTitle: TextView
    private lateinit var modelLabel: TextView

    private var isStreaming = false
    private var showingWelcome = false

    companion object {
        private const val TAG_WELCOME = "welcome"
        private const val TAG_THINKING = "thinking"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_chat, container, false)

        messagesContainer = root.findViewById(R.id.chat_messages)
        scroll = root.findViewById(R.id.chat_scroll)
        input = root.findViewById(R.id.chat_input)
        sendBtn = root.findViewById(R.id.btn_chat_send)
        btnOpenDrawer = root.findViewById(R.id.btn_open_drawer)
        sessionTitle = root.findViewById(R.id.chat_session_title)
        modelLabel = root.findViewById(R.id.chat_model_label)

        btnOpenDrawer.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        sendBtn.setOnClickListener { sendMessage() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // Register for session changes
        (activity as? MainActivity)?.onSessionChanged = { sessionId ->
            loadSessionMessages(sessionId)
        }

        // Restore active session messages
        val activeSessionId = (activity as? MainActivity)?.activeSessionId
        if (activeSessionId != null) {
            loadSessionMessages(activeSessionId)
        } else if (messagesContainer.childCount == 0) {
            showWelcome()
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        val settings = requireContext().getSharedPreferences("coder_io_settings", 0)
        val model = settings.getString("model", null)
        modelLabel.text = model ?: "big-pickle"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.onSessionChanged = null
    }

    // ── Session management ──────────────────────────────────────

    private fun loadSessionMessages(sessionId: String?) {
        messagesContainer.removeAllViews()
        showingWelcome = false

        if (sessionId == null) {
            showWelcome()
            sessionTitle.text = getString(R.string.tab_chat)
            return
        }

        val mainActivity = (activity as? MainActivity) ?: return
        val chatHistoryManager = mainActivity.chatHistoryManager
        val session = chatHistoryManager.getSession(sessionId)
        if (session == null) {
            showWelcome()
            sessionTitle.text = getString(R.string.tab_chat)
            return
        }

        sessionTitle.text = session.title

        val messages = chatHistoryManager.getMessages(sessionId)
        if (messages.isEmpty()) {
            showWelcome()
            return
        }

        messages.forEach { msg ->
            if (msg.role == "user") {
                appendMessage(text = msg.content, isUser = true, animate = false)
            } else {
                appendAiMessage(text = msg.content, animate = false)
            }
        }
        scrollToBottom()
    }

    // ── Welcome message ─────────────────────────────────────────

    private fun showWelcome() {
        val welcomeView = layoutInflater.inflate(R.layout.message_ai, messagesContainer, false)
        welcomeView.tag = TAG_WELCOME
        val bubble = welcomeView.findViewById<TextView>(R.id.tv_ai_message)
        MarkdownRenderer.renderInto(getString(R.string.welcome_message), bubble)
        messagesContainer.addView(welcomeView)
        showingWelcome = true
    }

    // ── Send message ────────────────────────────────────────────

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || isStreaming) return

        val mainActivity = (activity as? MainActivity) ?: return
        val chatHistoryManager = mainActivity.chatHistoryManager

        // Auto-create session if none active
        if (mainActivity.activeSessionId == null) {
            val title = if (text.length > 40) text.take(40) + "…" else text
            val newId = chatHistoryManager.createSession(title = title)
            mainActivity.activeSessionId = newId
            sessionTitle.text = title
        }

        val sessionId = mainActivity.activeSessionId ?: return

        // Hide welcome if present
        if (showingWelcome) {
            removeViewByTag(TAG_WELCOME)
            showingWelcome = false
        }

        // Remove any stale thinking indicators
        removeViewByTag(TAG_THINKING)

        // Show user message bubble
        appendMessage(text, isUser = true)

        // Save user message to history
        chatHistoryManager.addMessage(sessionId, "user", text)

        // Auto-update session title from first message
        val messages = chatHistoryManager.getMessages(sessionId)
        if (messages.size <= 1) {
            val newTitle = if (text.length > 40) text.take(40) + "…" else text
            chatHistoryManager.renameSession(sessionId, newTitle)
            sessionTitle.text = newTitle
        }

        input.setText("")

        // Show thinking indicator
        val thinkingView = layoutInflater.inflate(R.layout.message_ai_thinking, messagesContainer, false)
        thinkingView.tag = TAG_THINKING
        messagesContainer.addView(thinkingView)
        scrollToBottom()

        // Stream AI response
        isStreaming = true
        sendBtn.isEnabled = false

        // Build conversation history (excluding current user message — service adds it)
        val historyMessages = chatHistoryManager.getMessages(sessionId).dropLast(1)

        viewLifecycleOwner.lifecycleScope.launch {
            var aiText = ""
            var aiView: View? = null

            AiChatService.streamChat(
                context = requireContext(),
                userMessage = text,
                conversationHistory = historyMessages,
                onToken = { token ->
                    if (aiView == null) {
                        // Remove thinking indicator, create real bubble
                        removeViewByTag(TAG_THINKING)
                        aiView = layoutInflater.inflate(R.layout.message_ai, messagesContainer, false)
                        messagesContainer.addView(aiView)
                    }
                    aiText += token
                    aiView?.findViewById<TextView>(R.id.tv_ai_message)?.let { tv ->
                        MarkdownRenderer.renderInto(aiText, tv)
                    }
                    scrollToBottom()
                },
                onDone = {
                    isStreaming = false
                    sendBtn.isEnabled = true

                    // Save assistant message
                    chatHistoryManager.addMessage(sessionId, "assistant", aiText)
                    chatHistoryManager.touchSession(sessionId)
                },
                onError = { error ->
                    removeViewByTag(TAG_THINKING)
                    val errorView = layoutInflater.inflate(R.layout.message_ai, messagesContainer, false)
                    errorView.findViewById<TextView>(R.id.tv_ai_message).apply {
                        MarkdownRenderer.renderInto(getString(R.string.error_message, error), this)
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                    }
                    messagesContainer.addView(errorView)
                    scrollToBottom()
                    isStreaming = false
                    sendBtn.isEnabled = true

                    // Save error as assistant message so context isn't lost
                    chatHistoryManager.addMessage(sessionId, "assistant", getString(R.string.error_message, error))
                    chatHistoryManager.touchSession(sessionId)
                }
            )
        }
    }

    // ── UI helpers ──────────────────────────────────────────────

    private fun appendMessage(text: String, isUser: Boolean, animate: Boolean = true) {
        val layoutRes = if (isUser) R.layout.message_user else R.layout.message_ai
        val view = layoutInflater.inflate(layoutRes, messagesContainer, false)
        val tv = view.findViewById<TextView>(R.id.tv_ai_message)
            ?: view.findViewById<TextView>(R.id.tv_user_message)
        tv.text = text
        if (animate) {
            view.alpha = 0f
            view.translationY = 30f
            view.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
        messagesContainer.addView(view)
    }

    private fun appendAiMessage(text: String, animate: Boolean = true) {
        val view = layoutInflater.inflate(R.layout.message_ai, messagesContainer, false)
        val tv = view.findViewById<TextView>(R.id.tv_ai_message)
        MarkdownRenderer.renderInto(text, tv)
        if (animate) {
            view.alpha = 0f
            view.translationY = 30f
            view.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
        messagesContainer.addView(view)
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun removeViewByTag(tag: String) {
        val toRemove = mutableListOf<View>()
        for (i in 0 until messagesContainer.childCount) {
            if (messagesContainer.getChildAt(i)?.tag == tag) {
                toRemove.add(messagesContainer.getChildAt(i))
            }
        }
        toRemove.forEach { messagesContainer.removeView(it) }
    }
}
