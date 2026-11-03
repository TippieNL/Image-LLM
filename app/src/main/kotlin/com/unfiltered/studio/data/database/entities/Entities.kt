package com.unfiltered.studio.data.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val authJson: String,
    val settingsJson: String,
    val createdAt: Long,
    val sortOrder: Int,
)

@Entity(
    tableName = "generations",
    indices = [Index("createdAt"), Index("status"), Index("providerId"), Index("model")],
)
data class GenerationEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val providerId: String,
    val providerName: String,
    val providerType: String,
    val status: String,
    val paramsJson: String,
    // Denormalized for search and filtering without decoding JSON.
    val prompt: String,
    val negativePrompt: String,
    val model: String?,
    val seed: Long,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val sampler: String?,
    val error: String? = null,
    val errorDetails: String? = null,
    val warningsJson: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val remoteJobId: String? = null,
)

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = GenerationEntity::class,
            parentColumns = ["id"],
            childColumns = ["generationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("generationId"), Index("createdAt"), Index("isFavorite")],
)
data class ImageEntity(
    @PrimaryKey val id: String,
    val generationId: String,
    val filePath: String,
    val thumbnailPath: String,
    val width: Int,
    val height: Int,
    val seed: Long?,
    val mimeType: String,
    val sizeBytes: Long,
    val isFavorite: Boolean = false,
    val exportedUri: String? = null,
    val createdAt: Long,
    val indexInBatch: Int,
)

@Entity(
    tableName = "generation_loras",
    primaryKeys = ["generationId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = GenerationEntity::class,
            parentColumns = ["id"],
            childColumns = ["generationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("name")],
)
data class GenerationLoraEntity(
    val generationId: String,
    val name: String,
    val loraId: String?,
    val strength: Float,
)

@Entity(tableName = "lora_categories")
data class LoraCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "loras",
    indices = [Index("name"), Index("categoryId"), Index("sha256")],
)
data class LoraEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val previewImageUrl: String?,
    val triggerWordsJson: String,
    val architecture: String?,
    val baseModelLabel: String?,
    val downloadUrl: String?,
    val source: String,
    val sourceId: String?,
    val sourceVersionId: String?,
    val hfRepoId: String?,
    val hfWeightFile: String?,
    val fileName: String?,
    val fileSizeBytes: Long?,
    val sha256: String?,
    val localFilePath: String?,
    val defaultStrength: Float,
    val categoryId: String?,
    val tagsJson: String,
    val isFavorite: Boolean,
    val nsfw: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "lora_links",
    primaryKeys = ["loraId", "providerId"],
    foreignKeys = [
        ForeignKey(entity = LoraEntity::class, parentColumns = ["id"], childColumns = ["loraId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("providerId")],
)
data class LoraLinkEntity(
    val loraId: String,
    val providerId: String,
    val backendName: String,
    val linkedAt: Long,
)

@Entity(
    tableName = "active_loras",
    foreignKeys = [
        ForeignKey(entity = LoraEntity::class, parentColumns = ["id"], childColumns = ["loraId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ActiveLoraEntity(
    @PrimaryKey val loraId: String,
    val strength: Float,
    val clipStrength: Float?,
    val enabled: Boolean,
    val position: Int,
)

@Entity(
    tableName = "prompts",
    indices = [Index(value = ["prompt", "negativePrompt"], unique = true), Index("lastUsedAt")],
)
data class PromptEntity(
    @PrimaryKey val id: String,
    val prompt: String,
    val negativePrompt: String,
    val title: String?,
    val isFavorite: Boolean,
    val useCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
)

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val prompt: String,
    val negativePrompt: String,
    val createdAt: Long,
)

@Entity(tableName = "downloads", indices = [Index("status")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val url: String,
    val kind: String,
    val source: String?,
    val modelName: String,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val status: String,
    val error: String?,
    val filePath: String?,
    val sha256: String?,
    /** JSON metadata used to create the LoRA library entry on completion. */
    val metaJson: String?,
    val loraId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "catalog_cache")
data class CatalogCacheEntity(
    @PrimaryKey val providerId: String,
    val json: String,
    val fetchedAt: Long,
)

// ---------------------------------------------------------------------------------------------
// Query result types
// ---------------------------------------------------------------------------------------------

data class GenerationWithImages(
    @Embedded val generation: GenerationEntity,
    @Relation(parentColumn = "id", entityColumn = "generationId")
    val images: List<ImageEntity>,
)

data class ImageRow(
    @Embedded val image: ImageEntity,
    val prompt: String,
    val modelName: String?,
    val providerName: String,
)

data class ProviderFacetRow(val providerId: String, val providerName: String)
