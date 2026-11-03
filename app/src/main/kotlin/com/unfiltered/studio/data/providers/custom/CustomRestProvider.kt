package com.unfiltered.studio.data.providers.custom

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.JsonPath
import com.unfiltered.studio.core.JsonTemplate
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.data.network.toRequestBody
import com.unfiltered.studio.data.network.trimBaseUrl
import com.unfiltered.studio.data.providers.ImagePayloads
import com.unfiltered.studio.data.providers.LoraSyntax
import com.unfiltered.studio.data.providers.ProviderAdapterFactory
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CancelSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.CustomLoraMode
import com.unfiltered.studio.domain.models.CustomResponseMode
import com.unfiltered.studio.domain.models.CustomRestSettings
import com.unfiltered.studio.domain.models.GeneratedImageData
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ProgressSupport
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.SizeConstraints
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

/**
 * Fully configurable REST adapter: a JSON body template with `{{placeholders}}`, JSON-path based
 * image extraction and optional async job polling (submit → status → result → cancel).
 */
class CustomRestProvider(
    override val config: ProviderConfig,
    private val http: ProviderHttp,
) : ImageGenerationProvider {

    private val settings = config.settings as? CustomRestSettings ?: CustomRestSettings()
    private val base = config.baseUrl.trimBaseUrl()
    @Volatile private var activeCancelUrl: String? = null

    override suspend fun testConnection(): ConnectionInfo {
        val response = http.api.execute(Request.Builder().url(base).get().build())
        response.close()
        when (response.code) {
            401 -> throw AppError.Unauthorized(config.name)
            403 -> throw AppError.Forbidden(config.name)
        }
        return ConnectionInfo("Custom REST API", details = listOf("Reachable (HTTP ${response.code} on GET /)"))
    }

    override suspend fun fetchCatalog(): BackendCatalog = BackendCatalog(
        providerId = config.id,
        fetchedAt = System.currentTimeMillis(),
        serverLabel = "Custom",
        samplers = settings.samplerOptions,
    )

    override fun capabilities(catalog: BackendCatalog?, params: GenerationParameters): ProviderCapabilities = ProviderCapabilities(
        negativePrompt = settings.supportsNegativePrompt,
        size = if (settings.supportsSize) SizeConstraints(64, 4096, 8) else null,
        steps = if (settings.supportsSteps) 1..150 else null,
        cfg = if (settings.supportsCfg) 0f..30f else null,
        distilledGuidance = settings.supportsCfg && params.architecture.usesDistilledGuidance,
        seed = settings.supportsSeed,
        batch = if (settings.supportsBatch) BatchSupport.Native(4) else BatchSupport.Sequential(4),
        samplers = if (settings.supportsSampler) settings.samplerOptions else emptyList(),
        checkpointSelection = if (settings.supportsModel) CheckpointSelection.FreeText("model identifier") else CheckpointSelection.None,
        loras = when (settings.loraMode) {
            CustomLoraMode.NONE -> LoraSupport.None
            CustomLoraMode.PROMPT_SYNTAX -> LoraSupport.PromptSyntax
            CustomLoraMode.TEMPLATE_VARIABLE -> LoraSupport.TemplateVariable
        },
        img2img = "init_image" in settings.bodyTemplate,
        cancellation = if (settings.async?.cancelUrlPath != null || settings.async?.cancelUrlTemplate != null) CancelSupport.SERVER else CancelSupport.CLIENT_ONLY,
        progress = if (settings.async != null) ProgressSupport.STATUS else ProgressSupport.NONE,
    )

    fun variables(p: GenerationParameters, seed: Long, count: Int, initBytes: ByteArray?): Map<String, Any?> = buildMap {
        val prompt = if (settings.loraMode == CustomLoraMode.PROMPT_SYNTAX) LoraSyntax.appendTags(p.prompt, p.loras) else p.prompt
        put("prompt", prompt)
        put("prompt_with_loras", LoraSyntax.appendTags(p.prompt, p.loras))
        put("negative_prompt", p.negativePrompt)
        put("width", p.width)
        put("height", p.height)
        put("size", "${p.width}x${p.height}")
        put("steps", p.steps)
        put("cfg_scale", p.cfgScale)
        put("guidance", p.distilledGuidance)
        put("seed", seed)
        put("batch_size", count)
        put("sampler", p.sampler)
        put("scheduler", p.scheduler)
        put("model", p.checkpoint)
        put("denoise", p.denoisingStrength)
        put("loras", p.loras.filter { it.enabled }.map { mapOf("name" to (it.backendName ?: it.name), "strength" to it.strength, "url" to it.remoteUrl) })
        // fal.ai / WaveSpeed style: [{"path": url-or-name, "scale": strength}]
        put("loras_path_scale", p.loras.filter { it.enabled }.map { mapOf("path" to (it.remoteUrl ?: it.backendName ?: it.name), "scale" to it.strength) })
        put("init_image_base64", initBytes?.let { ImagePayloads.encode(it) })
        put("init_image_data_uri", initBytes?.let { ImagePayloads.dataUri(it) })
        put("base_url", base)
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val p = request.params
        val initBytes = p.initImagePath?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
        val rounds = if (settings.supportsBatch) listOf(p.batchSize) else List(p.batchSize) { 1 }
        rounds.forEachIndexed { index, count ->
            val seed = if (p.seed >= 0) p.seed + index else p.seed
            if (rounds.size > 1) send(GenerationEvent.Progress(index.toFloat() / rounds.size, message = "Image ${index + 1} of ${rounds.size}"))
            val vars = variables(p, seed, count, initBytes)
            runOnce(vars, seed)
        }
        send(GenerationEvent.Completed())
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<GenerationEvent>.runOnce(vars: Map<String, Any?>, seed: Long) {
        val rendered = JsonTemplate.render(settings.bodyTemplate, vars)
        rendered.unresolved.forEach { send(GenerationEvent.Warning("Unknown placeholder {{$it}} in the body template.")) }
        val url = base + JsonTemplate.renderText(settings.path, vars).let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
        val method = settings.method.uppercase()
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, rendered.json.toRequestBody())
        }
        send(GenerationEvent.Submitted(null))

        val async = settings.async
        if (async == null) {
            if (settings.responseMode == CustomResponseMode.IMAGE_BYTES) {
                val (bytes, type) = http.api.bytes(builder.build())
                val mime = ImageStorage.sniffMime(bytes) ?: type ?: "image/png"
                send(GenerationEvent.Image(GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 })))
            } else {
                emitImages(http.api.json(builder.build()), seed)
            }
            return
        }

        val submit = http.api.json(builder.build())
        val jobId = JsonPath.selectString(submit, async.jobIdPath)
        val statusUrl = JsonPath.selectString(submit, async.statusUrlPath)
            ?: async.statusUrlTemplate?.let { JsonTemplate.renderText(it, vars + ("job_id" to jobId)) }
            ?: throw AppError.Configuration("Async mode needs a status URL path or template.")
        activeCancelUrl = JsonPath.selectString(submit, async.cancelUrlPath)
            ?: async.cancelUrlTemplate?.let { JsonTemplate.renderText(it, vars + ("job_id" to jobId)) }
        send(GenerationEvent.Submitted(jobId))

        var payload: JsonElement = submit
        while (true) {
            val status = JsonPath.selectString(payload, async.statusPath)
            when {
                status != null && async.successValues.any { it.equals(status, ignoreCase = true) } -> break
                status != null && async.failureValues.any { it.equals(status, ignoreCase = true) } -> {
                    val error = JsonPath.selectString(payload, async.errorPath) ?: status
                    throw AppError.GenerationFailed("${config.name}: $error")
                }
                else -> send(GenerationEvent.Progress(fraction = null, message = status?.let { "Status: $it" } ?: "Waiting…"))
            }
            delay(async.pollIntervalMs.coerceIn(250, 30_000))
            payload = http.api.getJson(statusUrl)
        }
        activeCancelUrl = null
        val result = JsonPath.selectString(payload, async.resultUrlPath)?.let { http.api.getJson(it) } ?: payload
        emitImages(result, seed)
    }

    private suspend fun ProducerScope<GenerationEvent>.emitImages(json: JsonElement, seed: Long) {
        val values = JsonPath.selectFlattened(json, settings.imagePath).mapNotNull { it.stringOrNull?.takeIf { s -> s.isNotBlank() } }
        if (values.isEmpty()) throw AppError.GenerationFailed("No images found at JSON path \"${settings.imagePath}\" in the response.")
        values.forEach { value ->
            val (bytes, mime) = ImagePayloads.resolve(value, http, config.host)
            send(GenerationEvent.Image(GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 })))
        }
    }

    override suspend fun cancel(remoteJobId: String?) {
        val url = activeCancelUrl ?: return
        val method = settings.async?.cancelMethod?.uppercase() ?: "POST"
        runCatching { http.api.send(Request.Builder().url(url).method(method, if (method == "GET") null else ByteArray(0).toRequestBody()).build()) }
        activeCancelUrl = null
    }
}

class CustomRestProviderFactory @Inject constructor() : ProviderAdapterFactory {
    override fun create(config: ProviderConfig, http: ProviderHttp): ImageGenerationProvider = CustomRestProvider(config, http)
}
