package com.coderio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private lateinit var chatScroll: ScrollView
    private lateinit var chatMessages: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var btnChatSend: ImageButton

    private lateinit var aiService: AiChatService
    private val conversationHistory = mutableListOf<AiChatService.ChatMessage>()
    private var isWaiting = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatScroll = view.findViewById(R.id.chat_scroll)
        chatMessages = view.findViewById(R.id.chat_messages)
        chatInput = view.findViewById(R.id.chat_input)
        btnChatSend = view.findViewById(R.id.btn_chat_send)

        aiService = AiChatService(requireContext())

        btnChatSend.setOnClickListener { sendMessage() }

        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // Show welcome message
        addMessage(
            "assistant",
            "👋 Hi! I'm your coding assistant powered by **${aiService.getModel()}**.\n\n" +
                "I can help with:\n" +
                "• Code writing and explanation\n" +
                "• Debugging and fixes\n" +
                "• Shell commands\n" +
                "• System admin tasks\n\n" +
                "Configure your API key in the **Settings** tab first!"
        )
    }

    private fun sendMessage() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty() || isWaiting) return

        if (aiService.getApiKey().isBlank()) {
            Toast.makeText(context, "Configure API key in Settings tab first", Toast.LENGTH_LONG).show()
            return
        }

        addMessage("user", text)
        conversationHistory.add(AiChatService.ChatMessage("user", text))
        chatInput.text.clear()

        isWaiting = true
        btnChatSend.isEnabled = false

        val loadingView = addLoadingIndicator()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = aiService.chat(conversationHistory)

            loadingView.remove()

            result.onSuccess { response ->
                conversationHistory.add(AiChatService.ChatMessage("assistant", response))
                addMessage("assistant", response)
            }.onFailure { error ->
                addMessage("assistant", "⚠️ Error: ${error.message}")
            }

            isWaiting = false
            btnChatSend.isEnabled = true
        }
    }

    private fun addMessage(role: String, text: String) {
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16.dpToPx()
            }
        }

        if (role == "assistant") {
            // Add margin on the left for assistant
            wrapper.setPadding(0, 0, 48.dpToPx(), 0)
        } else {
            wrapper.setPadding(48.dpToPx(), 0, 0, 0)
            wrapper.gravity = Gravity.END
        }

        val bubble = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val bg = if (role == "user") R.drawable.bubble_user else R.drawable.bubble_ai
            background = ContextCompat.getDrawable(requireContext(), bg)
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (role == "user") {
                gravity = Gravity.END
            }
        }

        // Role label
        val label = TextView(requireContext()).apply {
            this.text = if (role == "user") "You" else "AI"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 4.dpToPx())
        }
        bubble.addView(label)

        // Message content (supports basic markdown code blocks)
        val content = TextView(requireContext()).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            textSize = 14f
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            setOnLongClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ai message", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                true
            }
            // Handle code blocks with monospace
            if (text.contains("```")) {
                typeface = Typeface.MONOSPACE
            }
        }
        bubble.addView(content)

        wrapper.addView(bubble)
        chatMessages.addView(wrapper)

        chatScroll.post {
            chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun addLoadingIndicator(): View {
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16.dpToPx()
            }
        }

        val bubble = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bubble_ai)
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            gravity = Gravity.CENTER_VERTICAL
        }

        val dots = TextView(requireContext()).apply {
            text = "Thinking..."
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 13f
        }
        bubble.addView(dots)
        wrapper.addView(bubble)
        chatMessages.addView(wrapper)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return wrapper
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
