package com.unfiltered.studio.presentation.screens.generate

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.data.network.NetworkMonitor
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.A1111Settings
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.AspectRatioPreset
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.models.HiresFixParams
import com.unfiltered.studio.domain.models.HuggingFaceSettings
import com.unfiltered.studio.domain.models.LiveProgress
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraFilter
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.OpenAiImagesSettings
import com.unfiltered.studio.domain.models.PreparedGeneration
import com.unfiltered.studio.domain.models.PromptEntry
import com.unfiltered.studio.domain.models.PromptTemplate
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderStatus
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.providers.ProviderRegistry
import com.unfiltered.studio.domain.repositories.GenerationRepository
import com.unfiltered.studio.domain.repositories.LoraRepository
import com.unfiltered.studio.domain.repositories.PromptRepository
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.domain.usecases.GenerationPreparer
import com.unfiltered.studio.domain.usecases.RandomPromptGenerator
import com.unfiltered.studio.domain.usecases.TemplateApplier
import com.unfiltered.studio.presentation.navigation.GenerateActions
import com.unfiltered.studio.presentation.navigation.PendingGenerate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

data class GenerateUiState(
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val aspect: AspectRatioPreset = AspectRatioPreset.SQUARE,
    val preflight: PreparedGeneration? = null,
    val errors: List<String> = emptyList(),
    val enqueuing: Boolean = false,
    val importingImage: Boolean = false,
)

@HiltViewModel
class GenerateViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val registry: ProviderRegistry,
    private val generations: GenerationRepository,
    private val loras: LoraRepository,
    private val prompts: PromptRepository,
    private val settingsRepository: SettingsRepository,
    private val imageStorage: ImageStorage,
    private val actions: GenerateActions,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val providerList: StateFlow<List<ProviderConfig>> = providers.observeProviders().state(emptyList())
    val activeProvider: StateFlow<ProviderConfig?> = providers.observeActiveProvider().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val statuses: StateFlow<Map<String, ProviderStatus>> = providers.statuses
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val settings: StateFlow<AppSettings> = settingsRepository.settings.state(AppSettings())

    private val _params = MutableStateFlow(GenerationParameters())
    val params: StateFlow<GenerationParameters> = _params.asStateFlow()

    private val _ui = MutableStateFlow(GenerateUiState())
    val ui: StateFlow<GenerateUiState> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val catalog: StateFlow<BackendCatalog?> = activeProvider
        .flatMapLatest { p -> if (p == null) flowOf(null) else providers.observeCatalog(p.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val providerInstance = activeProvider
        .mapLatest { p -> p?.let { runCatching { registry.get(it.id) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val capabilities: StateFlow<ProviderCapabilities> = combine(
        providerInstance,
        catalog,
        _params.map { it.copy(prompt = "", negativePrompt = "") }.distinctUntilChanged(),
    ) { provider, catalog, p -> provider?.capabilities(catalog, p) ?: ProviderCapabilities() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProviderCapabilities())

    val stack: StateFlow<List<ActiveLora>> = loras.observeStack().state(emptyList())
    val library: StateFlow<List<Lora>> = loras.observeLoras(LoraFilter()).state(emptyList())
    val active: StateFlow<List<Generation>> = generations.observeActive().state(emptyList())
    val live: StateFlow<Map<String, LiveProgress>> = generations.observeLive()
    val latest: StateFlow<Generation?> = generations.observeLatestFinished().state(null)
    val history: StateFlow<List<PromptEntry>> = prompts.observeHistory(80).state(emptyList())
    val favorites: StateFlow<List<PromptEntry>> = prompts.observeFavorites().state(emptyList())
    val templates: StateFlow<List<PromptTemplate>> = prompts.observeTemplates().state(emptyList())

    private val randomPrompts = RandomPromptGenerator()
    private val lastCheckpoint = mutableMapOf<String, String?>()
    private var suppressPreflight = false
    private var draftLoaded = false
    private val draftReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var firstProviderEvent = true

    init {
        viewModelScope.launch {
            val draft = settingsRepository.draftParams.first()
            val s = settingsRepository.current()
            _params.value = draft ?: defaultsFrom(s)
            _ui.update { it.copy(aspect = AspectRatioPreset.matching(_params.value.width, _params.value.height)) }
            draftLoaded = true
            draftReady.complete(Unit)
        }
        viewModelScope.launch {
            _params.drop(1).debounce(800).collect { if (draftLoaded) settingsRepository.saveDraft(it) }
        }
        viewModelScope.launch {
            activeProvider.filterNotNull().distinctUntilChanged { a, b -> a.id == b.id && a.baseUrl == b.baseUrl && a.settings == b.settings }
                .collect {
                    draftReady.await()
                    onProviderChanged(it)
                }
        }
        viewModelScope.launch {
            combine(activeProvider, _params.map { it.checkpoint }.distinctUntilChanged()) { p, c -> p to c }
                .debounce(700)
                .collect { (p, checkpoint) ->
                    if (p?.type == ProviderType.HUGGING_FACE) runCatching { providers.refreshModelInfo(p.id, checkpoint) }
                }
        }
        viewModelScope.launch {
            actions.pending.filterNotNull().collect { pending ->
                applyPending(pending)
                actions.consume()
            }
        }
    }

    private fun defaultsFrom(s: AppSettings) = GenerationParameters(
        width = s.defaults.width,
        height = s.defaults.height,
        steps = s.defaults.steps,
        cfgScale = s.defaults.cfgScale,
        batchSize = s.defaults.batchSize,
        negativePrompt = s.defaults.negativePrompt,
        sampler = s.defaults.sampler,
        scheduler = s.defaults.scheduler,
        clipSkip = s.defaults.clipSkip,
    )

    private suspend fun onProviderChanged(provider: ProviderConfig) {
        val cached = providers.observeCatalog(provider.id).first()
        val stale = cached == null || System.currentTimeMillis() - cached.fetchedAt > 60 * 60_000
        if (stale) refreshCatalog(silent = cached != null)
        if (statuses.value[provider.id] == null) viewModelScope.launch { providers.testConnection(provider.id) }
        if (firstProviderEvent) {
            // The restored draft was made with this same (persisted) active provider: keep its model.
            firstProviderEvent = false
            if (_params.value.checkpoint != null) {
                lastCheckpoint[provider.id] = _params.value.checkpoint
                return
            }
        }
        val remembered = lastCheckpoint[provider.id]
        val current = _params.value.checkpoint
        val catalogNow = cached ?: catalog.value
        val keepCurrent = current != null && catalogNow?.checkpoints?.any { it.name == current } == true
        val next = when {
            remembered != null -> remembered
            keepCurrent -> current
            else -> defaultModelOf(provider) ?: autoCheckpoint(provider, catalogNow)
        }
        setCheckpoint(next, remember = false)
    }

    private fun defaultModelOf(provider: ProviderConfig): String? = when (val s = provider.settings) {
        is A1111Settings -> s.defaultCheckpoint
        is ComfySettings -> s.defaultCheckpoint
        is HuggingFaceSettings -> s.defaultModelId
        is OpenAiImagesSettings -> s.defaultModel
        else -> null
    }

    /** ComfyUI built-in templates need a checkpoint; pick the first one when nothing is selected. */
    private fun autoCheckpoint(provider: ProviderConfig, catalog: BackendCatalog?): String? =
        if (provider.type == ProviderType.COMFYUI) catalog?.checkpoints?.firstOrNull()?.name else null

    fun refreshCatalog(silent: Boolean = false) {
        val provider = activeProvider.value ?: return
        viewModelScope.launch {
            _ui.update { it.copy(catalogLoading = true, catalogError = null) }
            try {
                val catalog = providers.refreshCatalog(provider.id)
                if (_params.value.checkpoint == null) autoCheckpoint(provider, catalog)?.let { setCheckpoint(it) }
                catalog.warnings.firstOrNull()?.let { if (!silent) _messages.send(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(catalogError = e.displayMessage) }
                if (!silent) _messages.send(e.displayMessage)
            } finally {
                _ui.update { it.copy(catalogLoading = false) }
            }
        }
    }

    fun testConnection() {
        val provider = activeProvider.value ?: return
        viewModelScope.launch { providers.testConnection(provider.id) }
    }

    private suspend fun applyPending(pending: PendingGenerate) {
        pending.providerId?.let { if (it != activeProvider.value?.id) providers.setActiveProvider(it) }
        pending.params?.let { p ->
            _params.value = p.copy(loras = emptyList())
            _ui.update { it.copy(aspect = AspectRatioPreset.matching(p.width, p.height)) }
            val entries = p.loras.mapNotNull { sel -> sel.loraId?.let { Triple(it, sel.strength, sel.enabled) } }
            loras.replaceStack(entries)
            val missing = p.loras.count { it.loraId == null }
            if (missing > 0) _messages.send("$missing LoRA(s) from that generation aren't in your library and were not restored.")
        }
        pending.checkpoint?.let { setCheckpoint(it) }
        pending.initImagePath?.let { useImageAsInit(it) }
        pending.message?.let { _messages.send(it) }
    }

    // ------------------------------------------------------------------ parameter setters

    private inline fun edit(transform: (GenerationParameters) -> GenerationParameters) = _params.update(transform)

    fun setPrompt(value: String) = edit { it.copy(prompt = value) }
    fun setNegative(value: String) = edit { it.copy(negativePrompt = value) }
    fun setSteps(value: Int) = edit { it.copy(steps = value) }
    fun setCfg(value: Float) = edit { it.copy(cfgScale = (value * 2).toInt() / 2f) }
    fun setGuidance(value: Float) = edit { it.copy(distilledGuidance = (value * 10).toInt() / 10f) }
    fun setSampler(value: String?) = edit { it.copy(sampler = value) }
    fun setScheduler(value: String?) = edit { it.copy(scheduler = value?.ifBlank { null }) }
    fun setBatch(value: Int) = edit { it.copy(batchSize = value.coerceIn(1, 4)) }
    fun setClipSkip(value: Int) = edit { it.copy(clipSkip = value.coerceIn(1, 12)) }
    fun setDenoise(value: Float) = edit { it.copy(denoisingStrength = value) }
    fun setVae(value: String?) = edit { it.copy(vae = value?.ifBlank { null }) }
    fun setComponent(slot: String, value: String?) = edit {
        it.copy(components = if (value.isNullOrBlank()) it.components - slot else it.components + (slot to value))
    }
    fun setAutoTrigger(value: Boolean) = edit { it.copy(autoTriggerWords = value) }
    fun setHires(transform: (HiresFixParams) -> HiresFixParams) = edit { it.copy(hiresFix = transform(it.hiresFix)) }

    fun setSeedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "-1") edit { it.copy(seed = -1) }
        else trimmed.toLongOrNull()?.let { seed -> edit { it.copy(seed = seed.coerceIn(0, GenerationPreparer.MAX_SEED)) } }
    }

    fun randomizeSeed() = edit { it.copy(seed = -1) }

    fun reuseLastSeed() {
        val seed = latest.value?.images?.firstOrNull()?.seed ?: latest.value?.params?.seed?.takeIf { it >= 0 }
        if (seed != null) edit { it.copy(seed = seed) } else viewModelScope.launch { _messages.send("No previous seed yet.") }
    }

    fun setWidth(value: Int) {
        edit { it.copy(width = value) }
        _ui.update { it.copy(aspect = AspectRatioPreset.CUSTOM) }
    }

    fun setHeight(value: Int) {
        edit { it.copy(height = value) }
        _ui.update { it.copy(aspect = AspectRatioPreset.CUSTOM) }
    }

    fun swapDimensions() {
        edit { it.copy(width = it.height, height = it.width) }
        _ui.update { it.copy(aspect = AspectRatioPreset.matching(_params.value.width, _params.value.height)) }
    }

    fun setAspect(preset: AspectRatioPreset) {
        _ui.update { it.copy(aspect = preset) }
        if (preset == AspectRatioPreset.CUSTOM) return
        val constraints = capabilities.value.size
        val (w, h) = preset.dimensions(_params.value.architecture.nativeResolution, multiple = 64, min = constraints?.min?.coerceAtLeast(256) ?: 512, max = constraints?.max ?: 2048)
        edit { it.copy(width = w, height = h) }
    }

    fun setCheckpoint(value: String?, remember: Boolean = true) {
        val name = value?.trim()?.ifBlank { null }
        val detected = name?.let { n ->
            catalog.value?.checkpoints?.firstOrNull { it.name == n }?.architecture ?: ModelArchitecture.detect(n)
        } ?: (catalog.value?.hfModel?.takeIf { it.modelId == name }?.baseModel?.let { ModelArchitecture.detect(it) })
        val previous = _params.value.architecture
        edit { it.copy(checkpoint = name) }
        if (detected != null && detected != previous) setArchitecture(detected, applyDefaults = true, announce = true)
        if (remember) activeProvider.value?.let { lastCheckpoint[it.id] = name }
    }

    fun setArchitecture(architecture: ModelArchitecture, applyDefaults: Boolean = true, announce: Boolean = false) {
        val before = _params.value
        edit { p ->
            if (!applyDefaults) p.copy(architecture = architecture)
            else {
                val aspect = _ui.value.aspect.takeIf { it != AspectRatioPreset.CUSTOM } ?: AspectRatioPreset.matching(p.width, p.height)
                val (w, h) = if (aspect == AspectRatioPreset.CUSTOM) p.width to p.height else aspect.dimensions(architecture.nativeResolution)
                p.copy(
                    architecture = architecture,
                    width = w,
                    height = h,
                    steps = architecture.defaultSteps,
                    cfgScale = architecture.defaultCfg,
                    distilledGuidance = if (architecture.usesDistilledGuidance) architecture.defaultGuidance else p.distilledGuidance,
                    clipSkip = if (architecture == ModelArchitecture.PONY) 2 else if (architecture.supportsClipSkip) p.clipSkip else 1,
                )
            }
        }
        if (announce && before.architecture != architecture) {
            viewModelScope.launch { _messages.send("Detected ${architecture.displayName} — applied its recommended size, steps and CFG.") }
        }
    }

    fun selectProvider(id: String) = viewModelScope.launch { providers.setActiveProvider(id) }

    fun setInitImage(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(importingImage = true) }
        try {
            val path = imageStorage.importInitImage(uri)
            edit { it.copy(initImagePath = path) }
        } catch (e: Exception) {
            _messages.send(e.displayMessage)
        } finally {
            _ui.update { it.copy(importingImage = false) }
        }
    }

    fun useImageAsInit(path: String) = edit { it.copy(initImagePath = path) }
    fun clearInitImage() = edit { it.copy(initImagePath = null) }

    fun resetToDefaults() = viewModelScope.launch {
        val s = settingsRepository.current()
        val keep = _params.value
        _params.value = defaultsFrom(s).copy(prompt = keep.prompt, checkpoint = keep.checkpoint, architecture = keep.architecture)
        _messages.send("Settings reset to your defaults.")
    }

    // ------------------------------------------------------------------ LoRAs

    fun toggleLora(loraId: String) = viewModelScope.launch {
        if (stack.value.any { it.lora.id == loraId }) loras.removeFromStack(loraId) else loras.addToStack(loraId)
    }

    fun setLoraStrength(loraId: String, value: Float) = viewModelScope.launch { loras.updateStackEntry(loraId, strength = (value * 100).toInt() / 100f) }
    fun setLoraClipStrength(loraId: String, value: Float) = viewModelScope.launch { loras.updateStackEntry(loraId, clipStrength = (value * 100).toInt() / 100f) }
    fun setLoraEnabled(loraId: String, enabled: Boolean) = viewModelScope.launch { loras.updateStackEntry(loraId, enabled = enabled) }
    fun removeLora(loraId: String) = viewModelScope.launch { loras.removeFromStack(loraId) }
    fun clearLoras() = viewModelScope.launch { loras.clearStack() }

    fun syncLorasFromServer() {
        val provider = activeProvider.value ?: return
        viewModelScope.launch {
            try {
                val result = loras.syncFromServer(provider.id)
                _messages.send("Synced ${result.total} LoRA(s) from ${provider.name}: ${result.added} new, ${result.linked} linked.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.displayMessage)
            }
        }
    }

    fun insertText(text: String) = edit { p ->
        if (p.prompt.contains(text, ignoreCase = true)) p
        else p.copy(prompt = listOf(p.prompt.trimEnd().trimEnd(','), text).filter { it.isNotBlank() }.joinToString(", "))
    }

    // ------------------------------------------------------------------ prompt tools

    fun randomPrompt() = edit { it.copy(prompt = randomPrompts.generate(it.architecture)) }

    fun applyTemplate(template: PromptTemplate) = edit { p ->
        val (prompt, negative) = TemplateApplier.apply(template, p.prompt)
        p.copy(prompt = prompt, negativePrompt = negative.ifBlank { p.negativePrompt })
    }

    fun loadPrompt(entry: PromptEntry) = edit { it.copy(prompt = entry.prompt, negativePrompt = entry.negativePrompt) }

    fun saveFavorite() = viewModelScope.launch {
        val p = _params.value
        if (p.prompt.isBlank()) _messages.send("Nothing to save yet.")
        else {
            prompts.saveFavorite(p.prompt, p.negativePrompt, null)
            _messages.send("Prompt saved to favorites.")
        }
    }

    fun setPromptFavorite(id: String, favorite: Boolean) = viewModelScope.launch { prompts.setFavorite(id, favorite) }
    fun deletePrompt(id: String) = viewModelScope.launch { prompts.delete(id) }
    fun clearPromptHistory() = viewModelScope.launch { prompts.clearHistory() }

    fun saveTemplate(name: String) = viewModelScope.launch {
        val p = _params.value
        prompts.saveTemplate(PromptTemplate("", name, "Custom", p.prompt, p.negativePrompt, builtIn = false))
        _messages.send("Template \"$name\" saved.")
    }

    fun deleteTemplate(id: String) = viewModelScope.launch { prompts.deleteTemplate(id) }

    // ------------------------------------------------------------------ generation

    fun generate(force: Boolean = false) {
        val provider = activeProvider.value
        if (provider == null) {
            viewModelScope.launch { _messages.send("Add a provider in Settings first.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(enqueuing = true, errors = emptyList()) }
            try {
                val prepared = generations.prepare(provider.id, _params.value, stack.value)
                when {
                    !prepared.canRun -> _ui.update { it.copy(errors = prepared.errors) }
                    prepared.warnings.isNotEmpty() && !force && !suppressPreflight -> _ui.update { it.copy(preflight = prepared) }
                    else -> enqueue(provider, prepared)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.displayMessage)
            } finally {
                _ui.update { it.copy(enqueuing = false) }
            }
        }
    }

    fun confirmPreflight(dontAskAgain: Boolean) {
        val prepared = _ui.value.preflight ?: return
        val provider = activeProvider.value ?: return
        suppressPreflight = suppressPreflight || dontAskAgain
        _ui.update { it.copy(preflight = null) }
        viewModelScope.launch { enqueue(provider, prepared) }
    }

    fun dismissPreflight() = _ui.update { it.copy(preflight = null) }
    fun dismissErrors() = _ui.update { it.copy(errors = emptyList()) }

    private suspend fun enqueue(provider: ProviderConfig, prepared: PreparedGeneration) {
        generations.enqueue(provider.id, prepared.params, prepared.warnings)
        val queued = active.value.count { it.status == GenerationStatus.QUEUED || it.status == GenerationStatus.RUNNING }
        if (queued > 0) _messages.send("Added to queue (${queued + 1} jobs).")
    }

    fun cancel(generationId: String) = viewModelScope.launch { generations.cancel(generationId) }
    fun cancelAll() = viewModelScope.launch { generations.cancelAll() }

    fun retryLatest() = viewModelScope.launch {
        latest.value?.let { generations.retry(it.id) }
    }

    fun variation() = viewModelScope.launch {
        latest.value?.let { generations.regenerate(it.id, newSeed = true) }
    }

    fun nextRandomSeed(): Long = Random.nextLong(0, GenerationPreparer.MAX_SEED)

    fun checkpointRequiresList(): Boolean = capabilities.value.checkpointSelection is CheckpointSelection.FromList
}
