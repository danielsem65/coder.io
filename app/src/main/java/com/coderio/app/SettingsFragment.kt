package com.coderio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var apiUrlInput: TextInputEditText
    private lateinit var modelInput: TextInputEditText
    private lateinit var btnSave: Button
    private lateinit var statusText: TextView

    private lateinit var aiService: AiChatService

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        apiKeyInput = view.findViewById(R.id.api_key_input)
        apiUrlInput = view.findViewById(R.id.api_url_input)
        modelInput = view.findViewById(R.id.model_input)
        btnSave = view.findViewById(R.id.btn_save_settings)
        statusText = view.findViewById(R.id.settings_status)

        aiService = AiChatService(requireContext())

        // Load saved settings
        loadSettings()

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun loadSettings() {
        val key = aiService.getApiKey()
        val url = aiService.getApiUrl()
        val model = aiService.getModel()

        apiKeyInput.setText(key)
        apiUrlInput.setText(url)
        modelInput.setText(model)

        if (key.isNotBlank()) {
            statusText.text = "✓ Configured — model: $model"
        } else {
            statusText.text = "⚠ No API key set"
        }
    }

    private fun saveSettings() {
        val key = apiKeyInput.text.toString().trim()
        val url = apiUrlInput.text.toString().trim().ifBlank { "https://api.openai.com/v1" }
        val model = modelInput.text.toString().trim().ifBlank { "gpt-3.5-turbo" }

        aiService.saveSettings(key, url, model)
        statusText.text = "✓ Saved — model: $model"
        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
    }
}
