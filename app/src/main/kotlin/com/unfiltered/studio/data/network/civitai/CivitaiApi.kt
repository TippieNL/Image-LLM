package com.unfiltered.studio.data.network.civitai

import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.BrowseSort
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.RemoteModel
import com.unfiltered.studio.domain.models.RemoteModelFile
import com.unfiltered.studio.domain.models.RemoteModelVersion
import com.unfiltered.studio.domain.models.RemotePreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

interface CivitaiApi {
    @GET
    suspend fun models(@Url url: String): CivitaiPageDto

    @GET("api/v1/models/{id}")
    suspend fun model(@Path("id") id: Long): CivitaiModelDto

    @GET("api/v1/model-versions/by-hash/{hash}")
    suspend fun versionByHash(@Path("hash") hash: String): CivitaiVersionDto
}

@Serializable
data class CivitaiPageDto(val items: List<CivitaiModelDto> = emptyList(), val metadata: CivitaiPageMeta? = null)

@Serializable
data class CivitaiPageMeta(val nextCursor: String? = null, val nextPage: String? = null)

@Serializable
data class CivitaiModelDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val type: String? = null,
    val nsfw: Boolean = false,
    val tags: List<String> = emptyList(),
    val creator: CivitaiCreatorDto? = null,
    val stats: CivitaiStatsDto? = null,
    val allowCommercialUse: JsonElement? = null,
    val allowDerivatives: Boolean? = null,
    val allowNoCredit: Boolean? = null,
    val modelVersions: List<CivitaiVersionDto> = emptyList(),
)

@Serializable
data class CivitaiCreatorDto(val username: String? = null)

@Serializable
data class CivitaiStatsDto(val downloadCount: Long? = null, val thumbsUpCount: Long? = null)

@Serializable
data class CivitaiVersionDto(
    val id: Long,
    val modelId: Long? = null,
    val name: String? = null,
    val baseModel: String? = null,
    val trainedWords: List<String> = emptyList(),
    val files: List<CivitaiFileDto> = emptyList(),
    val images: List<CivitaiImageDto> = emptyList(),
    val downloadUrl: String? = null,
    val description: String? = null,
    val model: CivitaiVersionModelDto? = null,
)

@Serializable
data class CivitaiVersionModelDto(val name: String? = null, val type: String? = null, val nsfw: Boolean? = null)

@Serializable
data class CivitaiFileDto(
    val id: Long? = null,
    val name: String,
    val sizeKB: Double? = null,
    val type: String? = null,
    val downloadUrl: String,
    val primary: Boolean? = null,
    val hashes: Map<String, String> = emptyMap(),
    val metadata: CivitaiFileMetaDto? = null,
)

@Serializable
data class CivitaiFileMetaDto(val format: String? = null, val fp: String? = null, val size: String? = null)

@Serializable
data class CivitaiImageDto(
    val url: String,
    val nsfwLevel: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val type: String? = null,
)

object CivitaiQueries {
    const val BASE = "https://civitai.com/"

    fun firstPageUrl(query: BrowseQuery, pageSize: Int): String {
        val b = "${BASE}api/v1/models".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter(
                "sort",
                when (query.sort) {
                    BrowseSort.MOST_DOWNLOADED, BrowseSort.TRENDING -> "Most Downloaded"
                    BrowseSort.MOST_LIKED -> "Highest Rated"
                    BrowseSort.NEWEST -> "Newest"
                },
            )
        if (query.sort == BrowseSort.TRENDING) b.addQueryParameter("period", "Week")
        if (!query.includeNsfw) b.addQueryParameter("nsfw", "false")
        if (query.text.isNotBlank()) b.addQueryParameter("query", query.text.trim())
        typesFor(query.kind).forEach { b.addQueryParameter("types", it) }
        query.family?.let { family -> ModelArchitecture.civitaiBaseModels(family).forEach { b.addQueryParameter("baseModels", it) } }
        return b.build().toString()
    }

    fun typesFor(kind: ModelKind?): List<String> = when (kind) {
        ModelKind.CHECKPOINT -> listOf("Checkpoint")
        ModelKind.LORA -> listOf("LORA", "LoCon", "DoRA")
        ModelKind.TEXTUAL_INVERSION -> listOf("TextualInversion")
        ModelKind.CONTROLNET -> listOf("Controlnet")
        ModelKind.VAE -> listOf("VAE")
        ModelKind.IMG2IMG -> listOf("Checkpoint")
        ModelKind.OTHER, null -> emptyList()
    }
}

object CivitaiMapper {
    /** Civitai image nsfwLevel is a bitmask-style level: 1 PG, 2 PG-13, 4 R, 8 X, 16 XXX. */
    private fun isNsfw(level: Int?) = (level ?: 0) >= 4

    fun kindOf(type: String?): ModelKind = when (type?.lowercase()) {
        "checkpoint" -> ModelKind.CHECKPOINT
        "lora", "locon", "dora" -> ModelKind.LORA
        "textualinversion" -> ModelKind.TEXTUAL_INVERSION
        "controlnet" -> ModelKind.CONTROLNET
        "vae" -> ModelKind.VAE
        else -> ModelKind.OTHER
    }

    fun version(v: CivitaiVersionDto): RemoteModelVersion = RemoteModelVersion(
        id = v.id.toString(),
        name = v.name ?: "v${v.id}",
        baseModelLabel = v.baseModel,
        triggerWords = v.trainedWords.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        files = v.files.filter { it.type == null || it.type.equals("Model", true) || it.type.equals("Pruned Model", true) || it.primary == true }
            .ifEmpty { v.files }
            .map { f ->
                RemoteModelFile(
                    name = f.name,
                    sizeBytes = f.sizeKB?.let { (it * 1024).toLong() },
                    format = f.metadata?.format,
                    downloadUrl = f.downloadUrl,
                    primary = f.primary == true,
                    sha256 = f.hashes.entries.firstOrNull { it.key.equals("SHA256", true) }?.value?.lowercase(),
                    precision = f.metadata?.fp,
                )
            },
        previews = v.images.filter { it.type == null || it.type == "image" }.map { RemotePreview(it.url, isNsfw(it.nsfwLevel), it.width, it.height) },
    )

    fun toRemote(dto: CivitaiModelDto): RemoteModel {
        val versions = dto.modelVersions.map { version(it) }
        val first = versions.firstOrNull()
        return RemoteModel(
            source = ModelSource.CIVITAI,
            id = dto.id.toString(),
            name = dto.name,
            author = dto.creator?.username,
            kind = kindOf(dto.type),
            architecture = ModelArchitecture.fromCivitaiBaseModel(first?.baseModelLabel),
            baseModelLabel = first?.baseModelLabel,
            description = dto.description,
            descriptionIsHtml = true,
            previewUrls = first?.previews.orEmpty(),
            license = license(dto),
            nsfw = dto.nsfw,
            downloads = dto.stats?.downloadCount,
            likes = dto.stats?.thumbsUpCount,
            tags = dto.tags,
            versions = versions,
            webUrl = "https://civitai.com/models/${dto.id}",
        )
    }

    private fun license(dto: CivitaiModelDto): String {
        val commercial = when (val c = dto.allowCommercialUse) {
            is JsonArray -> c.mapNotNull { it.stringOrNull }.joinToString(", ").ifBlank { "None" }
            is JsonPrimitive -> c.stringOrNull ?: "Unknown"
            else -> "Unknown"
        }
        return buildString {
            append("Commercial use: ").append(commercial)
            dto.allowDerivatives?.let { append(" · Derivatives: ").append(if (it) "allowed" else "not allowed") }
            dto.allowNoCredit?.let { append(" · Credit: ").append(if (it) "not required" else "required") }
        }
    }
}
