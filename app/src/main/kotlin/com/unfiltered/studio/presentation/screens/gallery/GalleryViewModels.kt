package com.unfiltered.studio.presentation.screens.gallery

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.GalleryFacets
import com.unfiltered.studio.domain.models.GalleryFilter
import com.unfiltered.studio.domain.models.GalleryImage
import com.unfiltered.studio.domain.models.GallerySort
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.repositories.GenerationHistoryRepository
import com.unfiltered.studio.domain.repositories.GenerationRepository
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.presentation.navigation.GalleryFilterHolder
import com.unfiltered.studio.presentation.navigation.GenerateActions
import com.unfiltered.studio.presentation.navigation.GenerationDetailRoute
import com.unfiltered.studio.presentation.navigation.ImageViewerRoute
import com.unfiltered.studio.presentation.navigation.PendingGenerate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared image actions for gallery, viewer and generation detail. */
abstract class ImageActionsViewModel(
    protected val history: GenerationHistoryRepository,
    protected val generations: GenerationRepository,
    protected val actions: GenerateActions,
) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    protected fun say(text: String) = viewModelScope.launch { _messages.send(text) }

    fun setFavorite(ids: Collection<String>, favorite: Boolean) = viewModelScope.launch { history.setFavorite(ids, favorite) }

    fun delete(ids: Collection<String>, onDone: () -> Unit = {}) = viewModelScope.launch {
        history.deleteImages(ids)
        say(if (ids.size == 1) "Image deleted." else "${ids.size} images deleted.")
        onDone()
    }

    fun export(ids: Collection<String>) = viewModelScope.launch {
        try {
            val n = history.export(ids)
            say(if (n == 1) "Saved to device." else "$n images saved to device.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            say(e.displayMessage)
        }
    }

    fun share(ids: Collection<String>, onUris: (List<Uri>) -> Unit) = viewModelScope.launch {
        try {
            onUris(history.shareUris(ids))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            say(e.displayMessage)
        }
    }

    fun reuse(generation: Generation, seed: Long?) {
        actions.request(PendingGenerate(params = generation.params.copy(seed = seed ?: generation.params.seed), providerId = generation.providerId, message = "Settings loaded from history."))
    }

    fun useAsInit(image: GalleryImage) {
        actions.request(PendingGenerate(initImagePath = image.filePath, message = "Image set as img2img input."))
    }

    fun regenerate(generationId: String, newSeed: Boolean) = viewModelScope.launch {
        generations.regenerate(generationId, newSeed)?.let { say(if (newSeed) "Variation queued." else "Regenerating with the same seed.") }
    }

    fun retry(generationId: String) = viewModelScope.launch {
        generations.retry(generationId)?.let { say("Queued again.") }
    }

    fun deleteGeneration(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        history.deleteGeneration(id)
        onDone()
    }
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    history: GenerationHistoryRepository,
    generations: GenerationRepository,
    actions: GenerateActions,
    private val filterHolder: GalleryFilterHolder,
    settings: SettingsRepository,
) : ImageActionsViewModel(history, generations, actions) {

    val filter: StateFlow<GalleryFilter> = filterHolder.filter.asStateFlow()
    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val images: Flow<PagingData<GalleryImage>> = filterHolder.filter.flatMapLatest { history.pagedImages(it) }.cachedIn(viewModelScope)

    private val historyQuery = MutableStateFlow("")
    private val historyStatus = MutableStateFlow<GenerationStatus?>(null)
    val historyQueryState = historyQuery.asStateFlow()
    val historyStatusState = historyStatus.asStateFlow()
    val generationsPaged: Flow<PagingData<Generation>> = combine(historyQuery, historyStatus) { q, s -> q to s }
        .flatMapLatest { (q, s) -> history.pagedGenerations(q, s) }
        .cachedIn(viewModelScope)

    val facets: StateFlow<GalleryFacets> = history.observeFacets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GalleryFacets())

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private fun updateFilter(transform: (GalleryFilter) -> GalleryFilter) = filterHolder.filter.update(transform)

    fun setQuery(q: String) = updateFilter { it.copy(query = q) }
    fun setFavoritesOnly(v: Boolean) = updateFilter { it.copy(favoritesOnly = v) }
    fun setModel(v: String?) = updateFilter { it.copy(model = v) }
    fun setLora(v: String?) = updateFilter { it.copy(loraName = v) }
    fun setProvider(v: String?) = updateFilter { it.copy(providerId = v) }
    fun setDateRange(from: Long?, to: Long?) = updateFilter { it.copy(fromMs = from, toMs = to?.let { t -> t + 86_400_000L - 1 }) }
    fun setSort(sort: GallerySort) = updateFilter { it.copy(sort = sort) }
    fun clearFilters() = updateFilter { GalleryFilter(sort = it.sort) }

    fun setHistoryQuery(q: String) { historyQuery.value = q }
    fun setHistoryStatus(s: GenerationStatus?) { historyStatus.value = s }

    fun toggleSelection(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { _selection.value = emptySet() }
}

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    history: GenerationHistoryRepository,
    generations: GenerationRepository,
    actions: GenerateActions,
    filterHolder: GalleryFilterHolder,
) : ImageActionsViewModel(history, generations, actions) {
    val initialId: String = savedState.toRoute<ImageViewerRoute>().imageId

    val ids: StateFlow<List<String>?> = history.observeImageIds(filterHolder.filter.value)
        .map { list -> if (initialId in list || list.isEmpty()) list.ifEmpty { listOf(initialId) } else listOf(initialId) + list }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun image(id: String): Flow<GalleryImage?> = history.observeImage(id)
    fun generation(id: String): Flow<Generation?> = history.observeGeneration(id)
}

@HiltViewModel
class GenerationDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    history: GenerationHistoryRepository,
    generations: GenerationRepository,
    actions: GenerateActions,
) : ImageActionsViewModel(history, generations, actions) {
    private val id = savedState.toRoute<GenerationDetailRoute>().generationId
    val generation: StateFlow<Generation?> = history.observeGeneration(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
