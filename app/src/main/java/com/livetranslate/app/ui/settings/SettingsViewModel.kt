package com.livetranslate.app.ui.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.data.SupportedLanguages
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.data.UserSettingsRepository
import com.livetranslate.app.live.LiveTranslateClient
import com.livetranslate.app.util.AppStrings
import com.livetranslate.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val initialKeys = apiKeyStore.getApiKeys().ifEmpty { listOf("") }
    private val _apiKeyFields = MutableStateFlow(
        initialKeys.map { TextFieldValue(it, TextRange(it.length)) },
    )
    val apiKeyFields: StateFlow<List<TextFieldValue>> = _apiKeyFields.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun updateApiKeyField(index: Int, value: TextFieldValue) {
        val list = _apiKeyFields.value.toMutableList()
        if (index in list.indices) {
            list[index] = value
            _apiKeyFields.value = list
        }
    }

    fun addApiKeyField() {
        val list = _apiKeyFields.value
        if (list.size >= ApiKeyStore.MAX_KEYS) return
        _apiKeyFields.value = list + TextFieldValue("")
    }

    fun removeApiKeyField(index: Int) {
        val list = _apiKeyFields.value.toMutableList()
        if (list.size <= 1) return
        if (index in 1 until list.size) {
            list.removeAt(index)
            _apiKeyFields.value = list
        }
    }

    fun saveApiKeys() {
        val keys = _apiKeyFields.value.map { it.text.trim() }.filter { it.isNotEmpty() }
        apiKeyStore.setApiKeys(keys)
        val next = keys.ifEmpty { listOf("") }.map { TextFieldValue(it, TextRange(it.length)) }
        _apiKeyFields.value = next
        _testResult.value = "✅"
    }

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun resetSubtitleAppearance() {
        viewModelScope.launch { settingsRepository.resetSubtitleAppearance() }
    }

    fun testConnection() {
        viewModelScope.launch {
            _testing.value = true
            _testResult.value = AppStrings.get(R.string.settings_test_running)
            val s = settings.value
            // Persist drafts first
            val keys = _apiKeyFields.value.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (keys.isEmpty()) {
                _testResult.value = AppStrings.get(R.string.settings_test_key_empty_fail)
                _testing.value = false
                return@launch
            }
            apiKeyStore.setApiKeys(keys)

            val endpoint = s.endpoint.trim().ifBlank { UserSettings.Defaults.ENDPOINT }
            val modelId = s.modelId.trim().ifBlank { UserSettings.Defaults.MODEL_ID }
            val targetLang = SupportedLanguages.canonicalOrDefault(s.targetLanguageCode)

            val lines = mutableListOf<String>()
            var okCount = 0
            for ((i, key) in keys.withIndex()) {
                val n = i + 1
                _testResult.value = AppStrings.get(R.string.settings_test_running_key, n, keys.size)
                if (key.length < 16) {
                    lines += AppStrings.get(R.string.settings_test_key_too_short, n)
                    continue
                }
                val client = LiveTranslateClient()
                val result = client.testConnection(
                    LiveTranslateClient.SessionConfig(
                        endpoint = endpoint,
                        apiKey = key,
                        modelId = modelId,
                        targetLanguageCode = targetLang,
                    ),
                )
                client.destroy()
                if (result.isSuccess) {
                    okCount++
                    lines += AppStrings.get(R.string.settings_test_key_ok, n, result.getOrNull().orEmpty())
                } else {
                    lines += AppStrings.get(
                        R.string.settings_test_key_fail,
                        n,
                        result.exceptionOrNull()?.message.orEmpty(),
                    )
                }
            }

            _testResult.value = buildString {
                append(lines.joinToString("\n"))
                append('\n')
                when {
                    okCount == keys.size -> append(AppStrings.get(R.string.settings_test_all_ok, keys.size))
                    okCount > 0 -> append(AppStrings.get(R.string.settings_test_some_ok, okCount, keys.size))
                    else -> append(AppStrings.get(R.string.settings_test_hint))
                }
            }
            _testing.value = false
        }
    }
}

class SettingsViewModelFactory(
    private val settingsRepository: UserSettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(settingsRepository, apiKeyStore) as T
    }
}
