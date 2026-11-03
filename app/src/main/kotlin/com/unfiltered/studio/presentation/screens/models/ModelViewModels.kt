package com.unfiltered.studio.presentation.screens.models

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.DownloadItem
import com.unfiltered.studio.domain.models.DownloadRequest
import com.unfiltered.studio.domain.models.DownloadStatus
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.models.RemoteModel
import com.unfiltered.studio.domain.models.RemoteModelFile
import com.unfiltered.studio.domain.repositories.AccountRepository
import com.unfiltered.studio.domain.repositories.AccountsState
import com.unfiltered.studio.domain.repositories.DownloadRepository
import com.unfiltered.studio.domain.repositories.LoraRepository
import com.unfiltered.studio.domain.repositories.ModelRepository
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.presentation.navigation.GenerateActions
import com.unfiltered.studio.presentation.navigation.ModelDetailRoute
import com.unfiltered.studio.presentation.navigation.PendingGenerate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ModelBrowserViewModel @Inject constructor(
    private val models: ModelRepository,
    private val providers: ProviderRepository,
    private val downloads: DownloadRepository,
    private val loras: LoraRepository,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _query = MutableStateFlow(BrowseQuery())
    val query: StateFlow<BrowseQuery> = _query.asStateFlow()

    val results: Flow<PagingData<RemoteModel>> = _query
        .debounce(350)
        .distinctUntilChanged()
        .flatMapLatest { models.browse(it) }
        .cachedIn(viewModelScope)

    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val activeProvider: StateFlow<ProviderConfig?> = providers.observeActiveProvider().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val catalog: StateFlow<BackendCatalog?> = providers.observeActiveProvider()
        .flatMapLatest { p -> if (p == null) flowOf(null) else providers.observeCatalog(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val downloadsList: StateFlow<List<DownloadItem>> = downloads.observeDownloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeDownloads: StateFlow<Int> = downloads.observeDownloads()
        .map { list -> list.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun update(transform: (BrowseQuery) -> BrowseQuery) = _query.update(transform)

    fun refreshServer() {
        val provider = activeProvider.value ?: return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                providers.refreshCatalog(provider.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.displayMessage)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun syncLoras() {
        val provider = activeProvider.value ?: return
        viewModelScope.launch {
            try {
                val r = loras.syncFromServer(provider.id)
                _messages.send("Synced ${r.total} LoRA(s): ${r.added} new, ${r.linked} linked to existing entries.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.displayMessage)
            }
        }
    }

    fun shareUri(item: DownloadItem): Uri? = item.filePath?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it)) }

    fun deleteDownload(item: DownloadItem) = viewModelScope.launch { downloads.remove(item.id, deleteFile = true) }

    fun availableBytes(): Long = downloads.availableBytes()
}

data class DownloadPrompt(val model: RemoteModel, val file: RemoteModelFile, val versionIndex: Int)

@HiltViewModel
class ModelDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val models: ModelRepository,
    private val downloads: DownloadRepository,
    private val loras: LoraRepository,
    private val providers: ProviderRepository,
    private val actions: GenerateActions,
    accounts: AccountRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val route = savedState.toRoute<ModelDetailRoute>()
    val source: ModelSource = ModelSource.valueOf(route.source)

    private val _model = MutableStateFlow<RemoteModel?>(null)
    val model: StateFlow<RemoteModel?> = _model.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _version = MutableStateFlow(0)
    val versionIndex: StateFlow<Int> = _version.asStateFlow()
    private val _downloadPrompt = MutableStateFlow<DownloadPrompt?>(null)
    val downloadPrompt: StateFlow<DownloadPrompt?> = _downloadPrompt.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val accounts: StateFlow<AccountsState> = accounts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsState())
    val providerList: StateFlow<List<ProviderConfig>> = providers.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { load() }

    fun load() = viewModelScope.launch {
        _error.value = null
        try {
            _model.value = models.details(source, route.modelId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _error.value = e.displayMessage
        }
    }

    fun selectVersion(index: Int) { _version.value = index }

    fun askDownload(file: RemoteModelFile) {
        val m = _model.value ?: return
        _downloadPrompt.value = DownloadPrompt(m, file, _version.value)
    }

    fun dismissDownload() { _downloadPrompt.value = null }

    fun availableBytes(): Long = downloads.availableBytes()

    fun confirmDownload() {
        val prompt = _downloadPrompt.value ?: return
        _downloadPrompt.value = null
        viewModelScope.launch {
            try {
                val version = prompt.model.versions.getOrNull(prompt.versionIndex)
                val loraId = if (prompt.model.kind == ModelKind.LORA) loras.addFromRemote(prompt.model, prompt.versionIndex).id else null
                downloads.enqueue(DownloadRequest(prompt.file, prompt.model.kind, prompt.model.source, prompt.model, version, loraId = loraId))
                _messages.send("Download started — see Downloads for progress.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.displayMessage)
            }
        }
    }

    fun addToLibrary() = viewModelScope.launch {
        val m = _model.value ?: return@launch
        try {
            val lora = loras.addFromRemote(m, _version.value)
            loras.addToStack(lora.id)
            _messages.send(
                if (lora.hasRemoteWeights) "Added to your LoRA library and the generation stack. Cloud providers can load it directly from Hugging Face."
                else "Added to your LoRA library. To use it on your own server, put the file in the server's LoRA folder and sync.",
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _messages.send(e.displayMessage)
        }
    }

    /** Uses an HF repo as the model of a Hugging Face provider (switching to one if needed). */
    fun useWithHuggingFace(): Boolean {
        val m = _model.value ?: return false
        val hf = providerList.value.firstOrNull { it.type == ProviderType.HUGGING_FACE }
        if (hf == null) {
            viewModelScope.launch { _messages.send("Add a Hugging Face provider in Settings first.") }
            return false
        }
        actions.request(PendingGenerate(checkpoint = m.id, providerId = hf.id, message = "Using ${m.id} via ${hf.name}."))
        return true
    }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val items: StateFlow<List<DownloadItem>> = downloads.observeDownloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun pause(id: String) = viewModelScope.launch { downloads.pause(id) }
    fun resume(id: String) = viewModelScope.launch { downloads.resume(id) }
    fun cancel(id: String) = viewModelScope.launch { downloads.cancel(id) }
    fun remove(id: String, deleteFile: Boolean) = viewModelScope.launch { downloads.remove(id, deleteFile) }
    fun shareUri(item: DownloadItem): Uri? = item.filePath?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it)) }
    fun availableBytes() = downloads.availableBytes()
}
