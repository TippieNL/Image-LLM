package com.unfiltered.studio.data.network.huggingface

import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.arrOrNull
import com.unfiltered.studio.core.obj
import com.unfiltered.studio.core.objOrNull
import com.unfiltered.studio.core.str
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.BrowseSort
import com.unfiltered.studio.domain.models.HfModelInfo
import com.unfiltered.studio.domain.models.HfProviderMapping
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.RemoteModel
import com.unfiltered.studio.domain.models.RemoteModelFile
import com.unfiltered.studio.domain.models.RemoteModelVersion
import com.unfiltered.studio.domain.models.RemotePreview
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface HfHubApi {
    @GET("api/whoami-v2")
    suspend fun whoAmI(@Header("Authorization") authorization: String): HfWhoAmIDto

    /** Full URL (first page built by [HfQueries], next pages from the `Link` header). */
    @GET
    suspend fun listModels(@Url url: String): Response<List<HfModelDto>>

    @GET("api/models/{repoId}")
    suspend fun model(
        @Path("repoId", encoded = true) repoId: String,
        @Query("expand[]") expand: List<String>,
    ): HfModelDto

    @GET("api/models/{repoId}/tree/main")
    suspend fun tree(
        @Path("repoId", encoded = true) repoId: String,
        @Query("recursive") recursive: Boolean = true,
    ): List<HfTreeEntryDto>

    @GET
    suspend fun raw(@Url url: String): ResponseBody
}

@Serializable
data class HfModelDto(
    val id: String,
    val author: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    @SerialName("library_name") val libraryName: String? = null,
    val tags: List<String> = emptyList(),
    val cardData: JsonObject? = null,
    val lastModified: String? = null,
    val createdAt: String? = null,
    val gated: JsonElement? = null,
    val inferenceProviderMapping: JsonElement? = null,
    val siblings: List<HfSiblingDto>? = null,
)

@Serializable
data class HfSiblingDto(val rfilename: String, val size: Long? = null)

@Serializable
data class HfLfsDto(val oid: String? = null, val size: Long? = null)

@Serializable
data class HfTreeEntryDto(val type: String, val path: String, val size: Long? = null, val lfs: HfLfsDto? = null)

@Serializable
data class HfWhoAmIDto(val name: String, val fullname: String? = null, val auth: HfAuthDto? = null)

@Serializable
data class HfAuthDto(val type: String? = null, val accessToken: HfAccessTokenDto? = null)

@Serializable
data class HfAccessTokenDto(val displayName: String? = null, val role: String? = null, val fineGrained: JsonObject? = null)

object HfQueries {
    const val BASE = "https://huggingface.co/"
    private val LIST_EXPAND = listOf(
        "downloads", "likes", "pipeline_tag", "tags", "cardData", "lastModified", "gated", "inferenceProviderMapping", "library_name",
    )
    val DETAIL_EXPAND = LIST_EXPAND + listOf("siblings", "author", "createdAt")

    fun firstPageUrl(query: BrowseQuery, pageSize: Int): String {
        val b = "${BASE}api/models".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("direction", "-1")
            .addQueryParameter(
                "sort",
                when (query.sort) {
                    BrowseSort.MOST_DOWNLOADED -> "downloads"
                    BrowseSort.MOST_LIKED -> "likes"
                    BrowseSort.TRENDING -> "trendingScore"
                    BrowseSort.NEWEST -> "createdAt"
                },
            )
        LIST_EXPAND.forEach { b.addQueryParameter("expand[]", it) }
        var search = query.text.trim()
        val baseRepo = query.family?.let { ModelArchitecture.huggingFaceBaseRepo(it) }
        when (query.kind) {
            ModelKind.LORA -> {
                b.addQueryParameter("filter", "lora")
                baseRepo?.let { b.addQueryParameter("filter", "base_model:adapter:$it") }
            }
            ModelKind.TEXTUAL_INVERSION -> b.addQueryParameter("filter", "textual_inversion")
            ModelKind.CONTROLNET -> b.addQueryParameter("filter", "controlnet")
            ModelKind.VAE -> search = listOf(search, "vae").filter { it.isNotBlank() }.joinToString(" ")
            ModelKind.IMG2IMG -> b.addQueryParameter("pipeline_tag", "image-to-image")
            ModelKind.CHECKPOINT, null -> {
                b.addQueryParameter("pipeline_tag", "text-to-image")
                baseRepo?.let { b.addQueryParameter("filter", "base_model:finetune:$it") }
            }
            ModelKind.OTHER -> Unit
        }
        if (search.isNotBlank()) b.addQueryParameter("search", search)
        return b.build().toString()
    }

    /** Parses `Link: <url>; rel="next"`. */
    fun nextLink(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return header.split(",").map { it.trim() }.firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter('<')?.substringBefore('>')
    }

    fun resolveUrl(repoId: String, path: String): String =
        if (path.startsWith("http")) path else "${BASE}$repoId/resolve/main/${path.removePrefix("/")}"
}

object HfMapper {
    private val WEIGHT_EXTENSIONS = listOf(".safetensors", ".ckpt", ".pt", ".pth", ".bin", ".gguf", ".sft")
    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif")

    fun kindOf(dto: HfModelDto): ModelKind {
        val tags = dto.tags.map { it.lowercase() }
        return when {
            "lora" in tags || tags.any { it.startsWith("base_model:adapter:") } -> ModelKind.LORA
            "textual_inversion" in tags -> ModelKind.TEXTUAL_INVERSION
            "controlnet" in tags -> ModelKind.CONTROLNET
            dto.pipelineTag == "image-to-image" -> ModelKind.IMG2IMG
            dto.id.lowercase().let { "vae" in it.substringAfter('/') } -> ModelKind.VAE
            else -> ModelKind.CHECKPOINT
        }
    }

    fun baseModel(dto: HfModelDto): String? {
        val card = dto.cardData?.get("base_model")
        val fromCard = when (card) {
            is JsonPrimitive -> card.stringOrNull
            is JsonArray -> card.firstOrNull()?.stringOrNull
            else -> null
        }
        return fromCard ?: dto.tags.firstOrNull { it.startsWith("base_model:") }?.substringAfterLast(':')
    }

    fun architecture(dto: HfModelDto): ModelArchitecture? =
        ModelArchitecture.detect(baseModel(dto)) ?: ModelArchitecture.detect(dto.id)

    fun mappings(element: JsonElement?): List<HfProviderMapping> = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            val o = item.objOrNull ?: return@mapNotNull null
            HfProviderMapping(
                provider = o.str("provider") ?: return@mapNotNull null,
                providerId = o.str("providerId") ?: return@mapNotNull null,
                status = o.str("status") ?: "unknown",
                task = o.str("task") ?: "",
                adapter = o.str("adapter"),
                adapterWeightsPath = o.str("adapterWeightsPath"),
            )
        }
        is JsonObject -> element.mapNotNull { (provider, value) ->
            val o = value.objOrNull ?: return@mapNotNull null
            HfProviderMapping(
                provider = provider,
                providerId = o.str("providerId") ?: return@mapNotNull null,
                status = o.str("status") ?: "unknown",
                task = o.str("task") ?: "",
                adapter = o.str("adapter"),
                adapterWeightsPath = o.str("adapterWeightsPath"),
            )
        }
        else -> emptyList()
    }

    fun isGated(dto: HfModelDto): Boolean = when (val g = dto.gated) {
        null -> false
        is JsonPrimitive -> g.booleanOrNull ?: (g.stringOrNull?.let { it != "false" } ?: false)
        else -> true
    }

    fun previews(dto: HfModelDto): List<RemotePreview> {
        val fromWidget = dto.cardData?.get("widget")?.arrOrNull?.mapNotNull { w ->
            w.objOrNull?.obj("output")?.str("url")
        }.orEmpty()
        val fromFiles = dto.siblings.orEmpty().map { it.rfilename }
            .filter { name -> IMAGE_EXTENSIONS.any { name.lowercase().endsWith(it) } }
        return (fromWidget + fromFiles).distinct().take(8).map { RemotePreview(HfQueries.resolveUrl(dto.id, it)) }
    }

    fun toRemote(dto: HfModelDto, description: String? = null, tree: List<HfTreeEntryDto>? = null): RemoteModel {
        val files = (tree?.filter { it.type == "file" }?.map { Triple(it.path, it.size, it.lfs?.oid) }
            ?: dto.siblings.orEmpty().map { Triple(it.rfilename, it.size, null) })
            .filter { (path, _, _) -> WEIGHT_EXTENSIONS.any { path.lowercase().endsWith(it) } }
            .map { (path, size, sha) ->
                RemoteModelFile(
                    name = path,
                    sizeBytes = size,
                    format = path.substringAfterLast('.', "").uppercase().ifBlank { null },
                    downloadUrl = HfQueries.resolveUrl(dto.id, path),
                    primary = false,
                    sha256 = sha,
                    precision = Regex("""(fp8|fp16|bf16|fp32|q[2-8]_[0-9k_ms]+)""", RegexOption.IGNORE_CASE).find(path)?.value,
                )
            }
            .sortedWith(compareByDescending<RemoteModelFile> { it.name.endsWith(".safetensors") }.thenBy { it.name.count { c -> c == '/' } })
        val primaryIndex = files.indexOfFirst { it.name.endsWith(".safetensors") && '/' !in it.name }.takeIf { it >= 0 } ?: 0
        val markedFiles = files.mapIndexed { i, f -> if (i == primaryIndex) f.copy(primary = true) else f }
        val kind = kindOf(dto)
        val instancePrompt = dto.cardData?.get("instance_prompt")?.stringOrNull
        val license = dto.cardData?.str("license")?.let { lic ->
            if (lic == "other") dto.cardData.str("license_name") ?: "other" else lic
        }
        val mappings = mappings(dto.inferenceProviderMapping)
        return RemoteModel(
            source = ModelSource.HUGGING_FACE,
            id = dto.id,
            name = dto.id.substringAfter('/'),
            author = dto.author ?: dto.id.substringBefore('/', ""),
            kind = kind,
            architecture = architecture(dto),
            baseModelLabel = baseModel(dto),
            description = description,
            previewUrls = previews(dto),
            license = license,
            nsfw = dto.tags.any { it.equals("not-for-all-audiences", ignoreCase = true) },
            downloads = dto.downloads,
            likes = dto.likes,
            tags = dto.tags.filterNot { t -> listOf("base_model:", "license:", "region:", "endpoints_compatible", "diffusers:").any { t.startsWith(it) } }.take(20),
            versions = listOf(
                RemoteModelVersion(
                    id = "main",
                    name = "main",
                    baseModelLabel = baseModel(dto),
                    triggerWords = listOfNotNull(instancePrompt),
                    files = markedFiles,
                ),
            ),
            webUrl = "https://huggingface.co/${dto.id}",
            gated = isGated(dto),
            pipelineTag = dto.pipelineTag,
            updatedAt = dto.lastModified,
            inferenceProviders = mappings.filter { it.isLive }.map { it.provider }.distinct(),
        )
    }

    fun toModelInfo(dto: HfModelDto): HfModelInfo = HfModelInfo(
        modelId = dto.id,
        pipelineTag = dto.pipelineTag,
        gated = isGated(dto),
        mappings = mappings(dto.inferenceProviderMapping),
        baseModel = baseModel(dto),
        isLora = kindOf(dto) == ModelKind.LORA,
    )

    /** Removes YAML front matter from a model card README. */
    fun stripFrontMatter(readme: String): String {
        val trimmed = readme.trimStart()
        if (!trimmed.startsWith("---")) return readme.trim()
        val end = trimmed.indexOf("\n---", 3)
        return if (end > 0) trimmed.substring(end + 4).trim() else readme.trim()
    }

    /** Family helper for HF base filtering in the browser. */
    fun familyLabel(family: ArchitectureFamily?): String? = family?.let { ModelArchitecture.huggingFaceBaseRepo(it) }
}

internal fun parseJsonOrNull(text: String): JsonElement? = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
