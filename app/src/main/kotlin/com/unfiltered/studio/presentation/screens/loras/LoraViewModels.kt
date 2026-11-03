package com.unfiltered.studio.presentation.screens.loras

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.DownloadRequest
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraAvailabilityFilter
import com.unfiltered.studio.domain.models.LoraCategory
import com.unfiltered.studio.domain.models.LoraFilter
import com.unfiltered.studio.domain.models.LoraSource
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.models.RemoteModelFile
import com.unfiltered.studio.domain.repositories.DownloadRepository
import com.unfiltered.studio.domain.repositories.LoraRepository
import com.unfiltered.studio.domain.repositories.ModelRepository
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.presentation.navigation.LoraEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LoraLibraryViewModel @Inject constructor(
    private val loras: LoraRepository,
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(LoraFilter())
    val filter: StateFlow<LoraFilter> = _filter.asStateFlow()
    val activeProvider: StateFlow<ProviderConfig?> = providers.observeActiveProvider().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val items: StateFlow<List<Lora>?> = combine(_filter, activeProvider) { f, p -> f.copy(providerId = p?.id) }
        .flatMapLatest { loras.observeLoras(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val categories: StateFlow<List<LoraCategory>> = loras.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val stack: StateFlow<List<ActiveLora>> = loras.observeStack().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun setQuery(q: String) = _filter.update { it.copy(query = q) }
    fun setCategory(id: String?) = _filter.update { it.copy(categoryId = id) }
    fun setFavoritesOnly(v: Boolean) = _filter.update { it.copy(favoritesOnly = v) }
    fun setFamily(f: ArchitectureFamily?) = _filter.update { it.copy(family = f) }
    fun setAvailability(a: LoraAvailabilityFilter) = _filter.update { it.copy(availability = a) }

    fun toggleStack(id: String) = viewModelScope.launch {
        if (stack.value.any { it.lora.id == id }) loras.removeFromStack(id) else loras.addToStack(id)
    }

    fun toggleFavorite(lora: Lora) = viewModelScope.launch { loras.setFavorite(lora.id, !lora.isFavorite) }
    fun delete(lora: Lora, deleteFile: Boolean) = viewModelScope.launch { loras.delete(lora.id, deleteFile) }

    private fun run(label: String, block: suspend () -> String) = viewModelScope.launch {
        _busy.value = label
        try {
            _messages.send(block())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _messages.send(e.displayMessage)
        } finally {
            _busy.value = null
        }
    }

    fun sync() {
        val provider = activeProvider.value
        if (provider == null || (provider.type != ProviderType.A1111 && provider.type != ProviderType.COMFYUI)) {
            viewModelScope.launch { _messages.send("Select a ComfyUI or WebUI/Forge provider to sync its LoRA folder.") }
            return
        }
        run("Syncing with ${provider.name}…") {
            val r = loras.syncFromServer(provider.id)
            "${r.total} LoRA(s) on ${provider.name}: ${r.added} added, ${r.linked} matched to existing entries" + if (r.removedLinks > 0) ", ${r.removedLinks} no longer on the server." else "."
        }
    }

    fun import(uri: Uri) = run("Importing…") {
        val lora = loras.importFile(uri)
        "Imported ${lora.name}" + (lora.architecture?.let { " (${it.displayName})" } ?: "") +
            ". It's stored on this device; copy it to your server to use it there."
    }

    fun addByUrl(text: String) = run("Looking up…") {
        val input = text.trim()
        val civitai = Regex("""civitai\.com/models/(\d+)""").find(input)
        val versionId = Regex("""modelVersionId=(\d+)""").find(input)?.groupValues?.get(1)
        val hf = Regex("""huggingface\.co/([\w.-]+/[\w.-]+)""").find(input)?.groupValues?.get(1)
            ?: input.takeIf { Regex("""^[\w.-]+/[\w.-]+$""").matches(it) }
        val model = when {
            civitai != null -> models.details(ModelSource.CIVITAI, civitai.groupValues[1])
            input.toLongOrNull() != null -> models.details(ModelSource.CIVITAI, input)
            hf != null -> models.details(ModelSource.HUGGING_FACE, hf)
            else -> throw AppError.Configuration("Paste a Civitai model URL, a Hugging Face URL or an owner/repo id.")
        }
        if (model.kind != ModelKind.LORA) throw AppError.Configuration("${model.name} is a ${model.kind.displayName}, not a LoRA.")
        val index = versionId?.let { v -> model.versions.indexOfFirst { it.id == v }.takeIf { it >= 0 } } ?: 0
        val lora = loras.addFromRemote(model, index)
        "Added ${lora.name} to the library."
    }

    fun createCategory(name: String) = run("Saving…") { "Category \"${loras.createCategory(name).name}\" created." }
    fun renameCategory(id: String, name: String) = viewModelScope.launch { loras.renameCategory(id, name) }
    fun deleteCategory(id: String) = viewModelScope.launch {
        loras.deleteCategory(id)
        if (_filter.value.categoryId == id) setCategory(null)
    }
}

@HiltViewModel
class LoraEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val loras: LoraRepository,
    private val providers: ProviderRepository,
    private val downloads: DownloadRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val routeId = savedState.toRoute<LoraEditRoute>().loraId
    val isNew = routeId == null
    private val id = routeId ?: UUID.randomUUID().toString()

    private val _draft = MutableStateFlow<Lora?>(null)
    val draft: StateFlow<Lora?> = _draft.asStateFlow()
    val links: StateFlow<Map<String, String>> = (if (routeId != null) loras.observeLora(routeId).map { it?.backendLinks.orEmpty() } else flowOf(emptyMap()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val serverProviders: StateFlow<List<ProviderConfig>> = providers.observeProviders()
        .map { list -> list.filter { it.type == ProviderType.A1111 || it.type == ProviderType.COMFYUI } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories: StateFlow<List<LoraCategory>> = loras.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            _draft.value = routeId?.let { loras.getLora(it) } ?: Lora(id = id, name = "", source = LoraSource.MANUAL)
        }
    }

    fun catalog(providerId: String): Flow<BackendCatalog?> = providers.observeCatalog(providerId)

    fun update(transform: (Lora) -> Lora) = _draft.update { it?.let(transform) }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val lora = _draft.value ?: return@launch
        try {
            loras.save(lora)
            onDone()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _messages.send(e.displayMessage)
        }
    }

    fun delete(deleteFile: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        loras.delete(id, deleteFile)
        onDone()
    }

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

    /** Persists edits first so server actions operate on the stored entry. */
    private suspend fun persist() { _draft.value?.let { loras.save(it) } }

    fun setLink(providerId: String, backendName: String?) = task {
        persist()
        loras.setLink(id, providerId, backendName)
        if (backendName == null) "Unlinked." else "Linked to $backendName."
    }

    fun refreshCatalog(providerId: String) = task {
        providers.refreshCatalog(providerId)
        null
    }

    fun readServerMetadata(providerId: String) = task {
        persist()
        loras.refreshServerMetadata(id, providerId)?.let { updated -> _draft.value = updated }
        "Metadata read from the server file."
    }

    fun lookupCivitai() = task {
        persist()
        loras.lookupOnCivitai(id)?.let { updated -> _draft.value = updated }
        "Matched on Civitai by file hash."
    }

    fun downloadToDevice() = task {
        val lora = _draft.value ?: return@task null
        val url = lora.downloadUrl ?: throw AppError.Configuration("Set a download URL first.")
        persist()
        downloads.enqueue(
            DownloadRequest(
                file = RemoteModelFile(
                    name = lora.fileName ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "${lora.name}.safetensors" },
                    sizeBytes = lora.fileSizeBytes,
                    format = null,
                    downloadUrl = url,
                    sha256 = lora.sha256,
                ),
                kind = ModelKind.LORA,
                source = when (lora.source) {
                    LoraSource.CIVITAI -> ModelSource.CIVITAI
                    LoraSource.HUGGING_FACE -> ModelSource.HUGGING_FACE
                    else -> null
                },
                model = null,
                version = null,
                loraId = lora.id,
                displayName = lora.name,
            ),
        )
        "Download started."
    }

    fun setPreviewFromDevice(uri: Uri) = task {
        val target = File(context.filesDir, "lora_previews").apply { mkdirs() }.resolve("$id.img")
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw AppError.Storage("Couldn't read the image.")
        }
        update { it.copy(previewImageUrl = target.absolutePath) }
        null
    }

    fun deleteLocalFile() = task {
        val lora = _draft.value ?: return@task null
        lora.localFilePath?.let { File(it).delete() }
        update { it.copy(localFilePath = null) }
        persist()
        "Device copy deleted."
    }

    fun shareUri(): Uri? = _draft.value?.localFilePath?.let { path ->
        File(path).takeIf { it.exists() }?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    }

    fun createCategory(name: String) = task {
        val category = loras.createCategory(name)
        update { it.copy(categoryId = category.id) }
        null
    }

    suspend fun hasCatalog(providerId: String): Boolean = providers.observeCatalog(providerId).first() != null
}
