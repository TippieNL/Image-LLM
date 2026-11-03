package com.unfiltered.studio.presentation.navigation

import com.unfiltered.studio.domain.models.GalleryFilter
import com.unfiltered.studio.domain.models.GenerationParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data object GenerateRoute
@Serializable data object ModelsRoute
@Serializable data object LorasRoute
@Serializable data object GalleryRoute
@Serializable data object SettingsRoute
@Serializable data object DownloadsRoute
@Serializable data class ImageViewerRoute(val imageId: String)
@Serializable data class GenerationDetailRoute(val generationId: String)
@Serializable data class ModelDetailRoute(val source: String, val modelId: String)
@Serializable data class LoraEditRoute(val loraId: String? = null)
@Serializable data class ProviderEditRoute(val providerId: String? = null, val type: String? = null)

/** Something another screen wants the Generate screen to apply (reuse settings, use a model). */
data class PendingGenerate(
    val params: GenerationParameters? = null,
    val checkpoint: String? = null,
    val providerId: String? = null,
    val message: String? = null,
    val initImagePath: String? = null,
)

@Singleton
class GenerateActions @Inject constructor() {
    private val _pending = MutableStateFlow<PendingGenerate?>(null)
    val pending: StateFlow<PendingGenerate?> = _pending.asStateFlow()
    fun request(action: PendingGenerate) { _pending.value = action }
    fun consume() { _pending.value = null }
}

/** The gallery's current filter, shared with the full-screen viewer so swiping follows the same list. */
@Singleton
class GalleryFilterHolder @Inject constructor() {
    val filter = MutableStateFlow(GalleryFilter())
}
