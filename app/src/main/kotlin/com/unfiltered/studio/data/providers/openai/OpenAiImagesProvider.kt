package com.unfiltered.studio.data.providers.openai

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.arr
import com.unfiltered.studio.core.objOrNull
import com.unfiltered.studio.core.str
import com.unfiltered.studio.core.toJsonElement
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.data.network.trimBaseUrl
import com.unfiltered.studio.data.providers.ImagePayloads
import com.unfiltered.studio.data.providers.ProviderAdapterFactory
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CancelSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.GeneratedImageData
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.OpenAiImagesSettings
import com.unfiltered.studio.domain.models.OpenAiResponseFormat
import com.unfiltered.studio.domain.models.ProgressSupport
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.SizeConstraints
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** `POST /v1/images/generations` (OpenAI format) — Together, DeepInfra, LocalAI and many self-hosted servers. */
class OpenAiImagesProvider(
    override val config: ProviderConfig,
    private val http: ProviderHttp,
) : ImageGenerationProvider {

    private val settings = config.settings as? OpenAiImagesSettings ?: OpenAiImagesSettings()
    private val base: String = config.baseUrl.trimBaseUrl().let { if (it.endsWith("/v1")) it else "$it/v1" }

    override suspend fun testConnection(): ConnectionInfo {
        val models = runCatching { listModels() }
        models.exceptionOrNull()?.let { if (it is AppError.Unauthorized || it is AppError.Forbidden || it is AppError.Unreachable || it is AppError.Tls) throw it }
        return ConnectionInfo(
            serverName = "OpenAI-compatible API",
            details = listOf(models.getOrNull()?.let { "${it.size} model(s) listed" } ?: "Reachable (no /v1/models listing)"),
        )
    }

    private suspend fun listModels(): List<String> =
        http.api.getJson("$base/models").objOrNull?.arr("data")?.mapNotNull { it.objOrNull?.str("id") }.orEmpty()

    override suspend fun fetchCatalog(): BackendCatalog = BackendCatalog(
        providerId = config.id,
        fetchedAt = System.currentTimeMillis(),
        serverLabel = "OpenAI-compatible",
        models = runCatching { listModels() }.getOrDefault(emptyList()),
    )

    override fun capabilities(catalog: BackendCatalog?, params: GenerationParameters): ProviderCapabilities {
        val extended = settings.sendExtendedParams
        return ProviderCapabilities(
            negativePrompt = extended,
            size = SizeConstraints(256, 2048, 8),
            steps = if (extended) 1..100 else null,
            cfg = if (extended) 0f..20f else null,
            seed = extended,
            batch = if (settings.supportsBatch) BatchSupport.Native(4) else BatchSupport.Sequential(4),
            checkpointSelection = CheckpointSelection.FreeText("model id", catalog?.models.orEmpty()),
            loras = LoraSupport.None,
            cancellation = CancelSupport.CLIENT_ONLY,
            progress = ProgressSupport.NONE,
            notes = listOfNotNull(
                "Some servers only accept specific sizes (e.g. 1024x1024).",
                if (!extended) "Enable \"extended parameters\" in the provider settings if your server accepts steps/seed/negative prompt." else null,
            ),
        )
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val p = request.params
        val model = p.checkpoint?.ifBlank { null } ?: settings.defaultModel
        val rounds = if (settings.supportsBatch) listOf(p.batchSize) else List(p.batchSize) { 1 }
        send(GenerationEvent.Submitted(null))
        rounds.forEachIndexed { index, count ->
            val seed = if (p.seed >= 0) p.seed + index else p.seed
            val body = buildMap<String, Any?> {
                model?.let { put("model", it) }
                put("prompt", p.prompt)
                put("n", count)
                put("size", "${p.width}x${p.height}")
                put("response_format", if (settings.responseFormat == OpenAiResponseFormat.URL) "url" else "b64_json")
                if (settings.sendExtendedParams) {
                    p.negativePrompt.ifBlank { null }?.let { put("negative_prompt", it) }
                    put("width", p.width)
                    put("height", p.height)
                    put("steps", p.steps)
                    put("guidance_scale", if (p.architecture.usesDistilledGuidance) p.distilledGuidance else p.cfgScale)
                    if (seed >= 0) put("seed", seed)
                }
            }.toJsonElement() as JsonObject
            val merged = settings.extraJson?.takeIf { it.isNotBlank() }?.let { extra ->
                val extraObject = runCatching { AppJson.parseToJsonElement(extra) as JsonObject }.getOrElse {
                    throw AppError.Configuration("\"Extra JSON\" in the provider settings must be a JSON object.")
                }
                JsonObject(body + extraObject)
            } ?: body
            if (rounds.size > 1) send(GenerationEvent.Progress(index.toFloat() / rounds.size, message = "Image ${index + 1} of ${rounds.size}"))
            val response = http.api.postJson("$base/images/generations", merged).objOrNull
                ?: throw AppError.Parse(config.name, "empty response")
            val values = response.arr("data")?.mapNotNull { it.objOrNull?.let { d -> d.str("b64_json") ?: d.str("url") } }.orEmpty()
            if (values.isEmpty()) throw AppError.GenerationFailed("${config.name} returned no images.")
            values.forEach { value ->
                val (bytes, mime) = ImagePayloads.resolve(value, http, config.host)
                send(GenerationEvent.Image(GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 && settings.sendExtendedParams })))
            }
        }
        send(GenerationEvent.Completed())
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(remoteJobId: String?) = Unit
}

class OpenAiImagesProviderFactory @Inject constructor() : ProviderAdapterFactory {
    override fun create(config: ProviderConfig, http: ProviderHttp): ImageGenerationProvider = OpenAiImagesProvider(config, http)
}
