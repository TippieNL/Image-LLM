package com.unfiltered.studio.domain.models

import kotlinx.serialization.Serializable

@Serializable
enum class LoraSource(val displayName: String) {
    BACKEND("Server"),
    CIVITAI("Civitai"),
    HUGGING_FACE("Hugging Face"),
    LOCAL_IMPORT("Imported file"),
    MANUAL("Manual entry"),
}

/** A LoRA in the user's library. Storage location is explicit: device file, server file, remote URL. */
data class Lora(
    val id: String,
    val name: String,
    val description: String = "",
    val previewImageUrl: String? = null,
    val triggerWords: List<String> = emptyList(),
    val architecture: ModelArchitecture? = null,
    /** Free-text base model label as published (e.g. "SDXL 1.0", "Flux.1 D"). */
    val baseModelLabel: String? = null,
    val downloadUrl: String? = null,
    val source: LoraSource = LoraSource.MANUAL,
    /** Civitai model/version id or HF repo id. */
    val sourceId: String? = null,
    val sourceVersionId: String? = null,
    val hfRepoId: String? = null,
    val hfWeightFile: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val sha256: String? = null,
    /** Absolute path of a copy on this device (downloaded or imported). */
    val localFilePath: String? = null,
    val defaultStrength: Float = 0.8f,
    val categoryId: String? = null,
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val nsfw: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Server availability: providerId → backend identifier. */
    val backendLinks: Map<String, String> = emptyMap(),
) {
    val isOnDevice: Boolean get() = localFilePath != null
    val hasRemoteWeights: Boolean get() = hfRepoId != null && hfWeightFile != null
    fun isOnServer(providerId: String?): Boolean = providerId != null && backendLinks.containsKey(providerId)
}

data class LoraCategory(val id: String, val name: String, val sortOrder: Int = 0, val count: Int = 0)

/** A LoRA placed in the generation stack (persisted so the stack survives restarts). */
data class ActiveLora(
    val lora: Lora,
    val strength: Float,
    val clipStrength: Float?,
    val enabled: Boolean,
    val position: Int,
)

data class LoraFilter(
    val query: String = "",
    val categoryId: String? = null,
    val favoritesOnly: Boolean = false,
    val family: ArchitectureFamily? = null,
    val availability: LoraAvailabilityFilter = LoraAvailabilityFilter.ALL,
    val providerId: String? = null,
)

enum class LoraAvailabilityFilter(val label: String) {
    ALL("All"),
    ON_SERVER("On active server"),
    ON_DEVICE("On device"),
    REMOTE_ONLY("Not downloaded"),
}

data class LoraSyncResult(val total: Int, val added: Int, val linked: Int, val removedLinks: Int)

// ---------------------------------------------------------------------------------------------
// Model browser
// ---------------------------------------------------------------------------------------------

enum class ModelSource(val displayName: String) { HUGGING_FACE("Hugging Face"), CIVITAI("Civitai") }

enum class ModelKind(val displayName: String) {
    CHECKPOINT("Checkpoint"),
    LORA("LoRA"),
    TEXTUAL_INVERSION("Textual Inversion"),
    CONTROLNET("ControlNet"),
    VAE("VAE"),
    IMG2IMG("Image-to-image"),
    OTHER("Other"),
}

enum class BrowseSort(val displayName: String) {
    MOST_DOWNLOADED("Most downloaded"),
    MOST_LIKED("Most liked"),
    TRENDING("Trending"),
    NEWEST("Newest"),
}

data class BrowseQuery(
    val source: ModelSource = ModelSource.HUGGING_FACE,
    val text: String = "",
    val kind: ModelKind? = ModelKind.CHECKPOINT,
    val family: ArchitectureFamily? = null,
    val sort: BrowseSort = BrowseSort.MOST_DOWNLOADED,
    val includeNsfw: Boolean = false,
)

data class RemoteModel(
    val source: ModelSource,
    val id: String,
    val name: String,
    val author: String? = null,
    val kind: ModelKind,
    val architecture: ModelArchitecture?,
    val baseModelLabel: String? = null,
    val description: String? = null,
    val descriptionIsHtml: Boolean = false,
    val previewUrls: List<RemotePreview> = emptyList(),
    val license: String? = null,
    val nsfw: Boolean = false,
    val downloads: Long? = null,
    val likes: Long? = null,
    val tags: List<String> = emptyList(),
    val versions: List<RemoteModelVersion> = emptyList(),
    val webUrl: String,
    val gated: Boolean = false,
    val pipelineTag: String? = null,
    val updatedAt: String? = null,
    val inferenceProviders: List<String> = emptyList(),
) {
    val thumbnail: RemotePreview? get() = previewUrls.firstOrNull()
    val primaryFile: RemoteModelFile? get() = versions.firstOrNull()?.files?.let { files -> files.firstOrNull { it.primary } ?: files.firstOrNull() }
}

data class RemotePreview(val url: String, val nsfw: Boolean = false, val width: Int? = null, val height: Int? = null)

data class RemoteModelVersion(
    val id: String,
    val name: String,
    val baseModelLabel: String? = null,
    val triggerWords: List<String> = emptyList(),
    val files: List<RemoteModelFile> = emptyList(),
    val previews: List<RemotePreview> = emptyList(),
)

data class RemoteModelFile(
    val name: String,
    val sizeBytes: Long?,
    val format: String?,
    val downloadUrl: String,
    val primary: Boolean = false,
    val sha256: String? = null,
    val precision: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------------------------

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class DownloadItem(
    val id: String,
    val fileName: String,
    val url: String,
    val kind: ModelKind,
    val source: ModelSource?,
    val modelName: String,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val error: String? = null,
    val filePath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sha256: String? = null,
    val loraId: String? = null,
    val bytesPerSecond: Long? = null,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** Metadata to attach when a download completes (LoRA library entry creation). */
data class DownloadRequest(
    val file: RemoteModelFile,
    val kind: ModelKind,
    val source: ModelSource?,
    val model: RemoteModel?,
    val version: RemoteModelVersion?,
    /** Library entry to attach the downloaded file to (download started from the LoRA screen). */
    val loraId: String? = null,
    val displayName: String? = null,
)
