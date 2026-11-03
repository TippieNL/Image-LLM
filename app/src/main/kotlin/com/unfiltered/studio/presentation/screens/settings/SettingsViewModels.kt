package com.unfiltered.studio.presentation.screens.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.data.providers.comfyui.ComfyWorkflowBuilder
import com.unfiltered.studio.data.providers.comfyui.WorkflowAnalysis
import com.unfiltered.studio.data.providers.custom.CustomRestPresets
import com.unfiltered.studio.data.repositories.ModelStorage
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.A1111Settings
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.AuthConfig
import com.unfiltered.studio.domain.models.AuthType
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.CustomRestSettings
import com.unfiltered.studio.domain.models.HeaderEntry
import com.unfiltered.studio.domain.models.HfMode
import com.unfiltered.studio.domain.models.HuggingFaceSettings
import com.unfiltered.studio.domain.models.OpenAiImagesSettings
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderSettings
import com.unfiltered.studio.domain.models.ProviderStatus
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.repositories.AccountRepository
import com.unfiltered.studio.domain.repositories.AccountsState
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.repositories.ProviderSecretsUpdate
import com.unfiltered.studio.domain.repositories.SecretChange
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.presentation.navigation.ProviderEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class StorageInfo(val imagesBytes: Long, val cacheBytes: Long, val modelsBytes: Long, val freeBytes: Long)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val providers: ProviderRepository,
    private val accounts: AccountRepository,
    private val imageStorage: ImageStorage,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val providerList: StateFlow<List<ProviderConfig>> = providers.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val active: StateFlow<ProviderConfig?> = providers.observeActiveProvider().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val statuses: StateFlow<Map<String, ProviderStatus>> = providers.statuses
    val accountState: StateFlow<AccountsState> = accounts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsState())

    private val _storage = MutableStateFlow<StorageInfo?>(null)
    val storage: StateFlow<StorageInfo?> = _storage.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init { refreshStorage() }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }
    fun setActive(id: String) = viewModelScope.launch { providers.setActiveProvider(id) }
    fun test(id: String) = viewModelScope.launch { providers.testConnection(id) }

    private fun task(block: suspend () -> String?) = viewModelScope.launch {
        _busy.value = true
        try {
            block()?.let { _messages.send(it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _messages.send(e.displayMessage)
        } finally {
            _busy.value = false
        }
    }

    fun saveHfToken(token: String) = task {
        val account = accounts.setHfToken(token)
        account?.let { a ->
            "Signed in as ${a.username}" + when (a.canUseInference) {
                false -> ". This fine-grained token lacks the \"Make calls to Inference Providers\" permission."
                else -> "."
            }
        }
    }

    fun removeHfToken() = task { accounts.setHfToken(null); "Hugging Face token removed." }
    fun validateHf() = task { accounts.validateHfToken().let { "Token valid — ${it.username}" } }
    fun saveCivitaiKey(key: String) = task { accounts.setCivitaiKey(key); "Civitai API key saved." }
    fun removeCivitaiKey() = task { accounts.setCivitaiKey(null); "Civitai API key removed." }

    fun refreshStorage() = viewModelScope.launch {
        _storage.value = withContext(Dispatchers.IO) {
            val modelsDir = ModelStorage.root(context)
            StorageInfo(
                imagesBytes = imageStorage.appStorageBytes(),
                cacheBytes = imageStorage.cacheBytes(),
                modelsBytes = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                freeBytes = runCatching { android.os.StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L),
            )
        }
    }

    fun clearCache() = task {
        imageStorage.clearCaches()
        refreshStorage()
        "Cache cleared."
    }
}

data class ProviderEditorState(
    val config: ProviderConfig,
    val loaded: Boolean = false,
    val token: String = "",
    val clearToken: Boolean = false,
    val hasSavedToken: Boolean = false,
    val headerValues: Map<String, String> = emptyMap(),
    val savedHeaderSecrets: Set<String> = emptySet(),
    val testing: Boolean = false,
    val testResult: ConnectionInfo? = null,
    val testError: String? = null,
    val saving: Boolean = false,
    val analysis: WorkflowAnalysis? = null,
    val workflowError: String? = null,
)

@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val providers: ProviderRepository,
    accounts: AccountRepository,
) : ViewModel() {
    private val route = savedState.toRoute<ProviderEditRoute>()
    val isNew = route.providerId == null

    private val _state = MutableStateFlow(ProviderEditorState(config = defaultConfig(route.type?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() } ?: ProviderType.COMFYUI)))
    val state: StateFlow<ProviderEditorState> = _state.asStateFlow()
    val accountState: StateFlow<AccountsState> = accounts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val existing = route.providerId?.let { providers.getProvider(it) }
            if (existing != null) {
                val savedHeaders = existing.auth.extraHeaders.filter { it.secret && providers.hasHeaderSecret(existing.id, it.name) }.map { it.name }.toSet()
                _state.update { it.copy(config = existing, hasSavedToken = providers.hasToken(existing.id), savedHeaderSecrets = savedHeaders, loaded = true) }
            } else {
                _state.update { it.copy(loaded = true) }
            }
            analyzeWorkflow()
        }
    }

    private fun defaultConfig(type: ProviderType): ProviderConfig {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        return when (type) {
            ProviderType.A1111 -> ProviderConfig(id, "Stable Diffusion WebUI", type, "http://192.168.1.100:7860", AuthConfig(), A1111Settings(), now)
            ProviderType.COMFYUI -> ProviderConfig(id, "ComfyUI", type, "http://192.168.1.100:8188", AuthConfig(), ComfySettings(), now)
            ProviderType.HUGGING_FACE -> ProviderConfig(
                id, "Hugging Face", type, HF_ROUTER, AuthConfig(type = AuthType.BEARER, useAccountToken = true),
                HuggingFaceSettings(defaultModelId = "black-forest-labs/FLUX.1-dev"), now,
            )
            ProviderType.OPENAI_COMPATIBLE -> ProviderConfig(id, "OpenAI-compatible", type, "https://api.together.xyz/v1", AuthConfig(type = AuthType.BEARER), OpenAiImagesSettings(sendExtendedParams = true), now)
            ProviderType.CUSTOM_REST -> ProviderConfig(id, "Custom API", type, "https://", AuthConfig(), CustomRestSettings(), now)
        }
    }

    fun update(transform: (ProviderConfig) -> ProviderConfig) = _state.update { it.copy(config = transform(it.config), testResult = null, testError = null) }

    fun updateAuth(transform: (AuthConfig) -> AuthConfig) = update { it.copy(auth = transform(it.auth)) }

    fun updateSettings(transform: (ProviderSettings) -> ProviderSettings) {
        update { it.copy(settings = transform(it.settings)) }
        analyzeWorkflow()
    }

    fun setHfMode(mode: HfMode) = update { c ->
        val s = c.settings as? HuggingFaceSettings ?: HuggingFaceSettings()
        c.copy(
            settings = s.copy(mode = mode),
            baseUrl = when (mode) {
                HfMode.INFERENCE_PROVIDERS -> HF_ROUTER
                else -> c.baseUrl.takeUnless { it == HF_ROUTER } ?: "https://"
            },
        )
    }

    fun setToken(value: String) = _state.update { it.copy(token = value, clearToken = false) }
    fun clearToken() = _state.update { it.copy(token = "", clearToken = true) }
    fun setHeaderValue(name: String, value: String) = _state.update { it.copy(headerValues = it.headerValues + (name to value)) }

    fun addHeader() = updateAuth { it.copy(extraHeaders = it.extraHeaders + HeaderEntry("", "", secret = false)) }
    fun removeHeader(index: Int) = updateAuth { it.copy(extraHeaders = it.extraHeaders.filterIndexed { i, _ -> i != index }) }
    fun updateHeader(index: Int, entry: HeaderEntry) = updateAuth { a -> a.copy(extraHeaders = a.extraHeaders.mapIndexed { i, h -> if (i == index) entry else h }) }

    fun applyPreset(preset: CustomRestPresets.Preset) {
        update { it.copy(name = if (it.name == "Custom API") preset.name else it.name, baseUrl = preset.baseUrl, auth = preset.auth, settings = preset.settings) }
        viewModelScope.launch { _messages.send("Applied ${preset.name}. ${preset.description}") }
    }

    fun setWorkflowJson(json: String, name: String?) {
        updateSettings { s -> (s as? ComfySettings ?: ComfySettings()).copy(customWorkflowJson = json, customWorkflowName = name ?: (s as? ComfySettings)?.customWorkflowName) }
    }

    private fun analyzeWorkflow() {
        val comfy = _state.value.config.settings as? ComfySettings ?: return
        val json = comfy.customWorkflowJson
        if (json.isNullOrBlank()) {
            _state.update { it.copy(analysis = null, workflowError = null) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val analysis = ComfyWorkflowBuilder.analyze(json)
                _state.update { it.copy(analysis = analysis, workflowError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(analysis = null, workflowError = e.displayMessage) }
            }
        }
    }

    private fun secrets(): ProviderSecretsUpdate {
        val s = _state.value
        val token = when {
            s.clearToken -> SecretChange.Clear
            s.token.isNotBlank() -> SecretChange.Set(s.token)
            else -> SecretChange.Keep
        }
        val headers = s.config.auth.extraHeaders.filter { it.secret && it.name.isNotBlank() }
            .mapNotNull { h -> s.headerValues[h.name]?.takeIf { it.isNotBlank() }?.let { h.name to SecretChange.Set(it) } }
            .toMap()
        return ProviderSecretsUpdate(token, headers)
    }

    fun test() = viewModelScope.launch {
        _state.update { it.copy(testing = true, testResult = null, testError = null) }
        try {
            val info = providers.testUnsaved(_state.value.config, secrets())
            _state.update { it.copy(testResult = info) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(testError = e.displayMessage) }
        } finally {
            _state.update { it.copy(testing = false) }
        }
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(saving = true) }
        try {
            providers.saveProvider(_state.value.config, secrets())
            onDone()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _messages.send(e.displayMessage)
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        providers.deleteProvider(_state.value.config.id)
        onDone()
    }

    fun resumeEndpoint() = viewModelScope.launch {
        try {
            providers.resumeHfEndpoint(_state.value.config.id)
            _messages.send("Resume requested — the endpoint takes a few minutes to start.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _messages.send(e.displayMessage)
        }
    }

    companion object {
        const val HF_ROUTER = "https://router.huggingface.co"
    }
}
