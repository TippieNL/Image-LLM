package com.unfiltered.studio.domain.models

/** One generation job (queued, running or finished) with its outputs. */
data class Generation(
    val id: String,
    val createdAt: Long,
    val providerId: String,
    val providerName: String,
    val providerType: ProviderType,
    val status: GenerationStatus,
    val params: GenerationParameters,
    val error: String? = null,
    val errorDetails: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val remoteJobId: String? = null,
    val warnings: List<String> = emptyList(),
    val images: List<GalleryImage> = emptyList(),
) {
    val durationMs: Long? get() = if (startedAt != null && finishedAt != null) finishedAt - startedAt else null
    val modelLabel: String get() = params.checkpoint?.substringAfterLast('/')?.ifBlank { null } ?: "Default model"
}

data class GalleryImage(
    val id: String,
    val generationId: String,
    val filePath: String,
    val thumbnailPath: String,
    val width: Int,
    val height: Int,
    val seed: Long?,
    val mimeType: String,
    val sizeBytes: Long,
    val isFavorite: Boolean,
    val exportedUri: String?,
    val createdAt: Long,
    val indexInBatch: Int,
    // Denormalized from the generation for fast grids / search results.
    val prompt: String = "",
    val modelName: String? = null,
    val providerName: String? = null,
)

enum class GallerySort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first") }

data class GalleryFilter(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val providerId: String? = null,
    val model: String? = null,
    val loraName: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val sort: GallerySort = GallerySort.NEWEST,
) {
    val isActive: Boolean get() = query.isNotBlank() || favoritesOnly || providerId != null || model != null ||
        loraName != null || fromMs != null || toMs != null
}

/** Distinct values available for gallery filter chips. */
data class GalleryFacets(
    val models: List<String> = emptyList(),
    val loras: List<String> = emptyList(),
    val providers: List<Pair<String, String>> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Prompts
// ---------------------------------------------------------------------------------------------

data class PromptEntry(
    val id: String,
    val prompt: String,
    val negativePrompt: String,
    val isFavorite: Boolean,
    val title: String? = null,
    val useCount: Int,
    val lastUsedAt: Long,
)

data class PromptTemplate(
    val id: String,
    val name: String,
    val category: String,
    /** May contain `{subject}` which is replaced with the current prompt text. */
    val prompt: String,
    val negativePrompt: String,
    val builtIn: Boolean,
)
