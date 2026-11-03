package com.unfiltered.studio.data.providers.huggingface

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.arr
import com.unfiltered.studio.core.arrOrNull
import com.unfiltered.studio.core.boolValueOrNull
import com.unfiltered.studio.core.longValueOrNull
import com.unfiltered.studio.core.obj
import com.unfiltered.studio.core.objOrNull
import com.unfiltered.studio.core.str
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.core.toJsonElement
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.data.network.Retry
import com.unfiltered.studio.data.network.huggingface.HfMapper
import com.unfiltered.studio.data.network.huggingface.HfModelDto
import com.unfiltered.studio.data.network.huggingface.HfQueries
import com.unfiltered.studio.data.network.toRequestBody
import com.unfiltered.studio.data.network.trimBaseUrl
import com.unfiltered.studio.data.providers.ImagePayloads
import com.unfiltered.studio.data.providers.ProviderAdapterFactory
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CancelSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.GeneratedImageData
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.HfMode
import com.unfiltered.studio.domain.models.HfModelInfo
import com.unfiltered.studio.domain.models.HfRouterProviders
import com.unfiltered.studio.domain.models.HuggingFaceSettings
import com.unfiltered.studio.domain.models.LoraSelection
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import javax.inject.Inject

/** Which router provider + endpoint will serve a request, and what it supports. */
data class HfRoutePlan(
    val provider: String,
    val endpoint: String,
    val task: String,
    val loraSupport: LoraSupport,
    val nativeBatch: Boolean,
    val cancellable: Boolean,
    /** When the selected repo is itself a LoRA adapter served on a base model. */
    val adapterLoraUrl: String? = null,
    val note: String? = null,
)

class HuggingFaceProvider(
    override val config: ProviderConfig,
    private val http: ProviderHttp,
) : ImageGenerationProvider {

    private val settings = config.settings as? HuggingFaceSettings ?: HuggingFaceSettings()
    private val api = http.api
    private val modelInfoCache = mutableMapOf<String, Pair<Long, HfModelInfo>>()

    override suspend fun testConnection(): ConnectionInfo = when (settings.mode) {
        HfMode.INFERENCE_PROVIDERS -> {
            val me = api.getJson("${HfQueries.BASE}api/whoami-v2").objOrNull
                ?: throw AppError.Parse("Hugging Face", "whoami returned no data")
            val token = me.obj("auth")?.obj("accessToken")
            val model = settings.defaultModelId?.let { id -> runCatching { modelInfo(id, force = true) }.getOrNull() }
            ConnectionInfo(
                serverName = "Hugging Face Inference Providers",
                details = listOfNotNull(
                    "Signed in as ${me.str("name")}",
                    token?.str("displayName")?.let { "Token: $it (${token.str("role") ?: "?"})" },
                    model?.let { info -> "${info.modelId}: " + (info.mappings.filter { it.isLive }.joinToString { it.provider }.ifBlank { "no live providers" }) },
                ),
            )
        }
        HfMode.DEDICATED_ENDPOINT -> {
            val status = endpointStatus()
            if (status != null) {
                ConnectionInfo("HF Inference Endpoint", details = listOfNotNull("State: ${status.first}", status.second?.let { "Model: $it" }, endpointStateHint(status.first)))
            } else {
                probeReachable()
                ConnectionInfo("HF Inference Endpoint", details = listOf("Reachable. Add namespace + endpoint name to see its state."))
            }
        }
        HfMode.CUSTOM_URL -> {
            probeReachable()
            ConnectionInfo("Hugging Face-compatible endpoint", details = listOf("Reachable"))
        }
    }

    /** (state, model repository) from the Inference Endpoints API when namespace + name are configured. */
    suspend fun endpointStatus(): Pair<String, String?>? {
        val ns = settings.endpointNamespace?.trim()?.ifBlank { null } ?: return null
        val name = settings.endpointName?.trim()?.ifBlank { null } ?: return null
        val json = api.getJson("https://api.endpoints.huggingface.cloud/v2/endpoint/$ns/$name").objOrNull ?: return null
        val state = json.obj("status")?.str("state") ?: "unknown"
        return state to json.obj("model")?.str("repository")
    }

    suspend fun resumeEndpoint() {
        val ns = settings.endpointNamespace?.trim()?.ifBlank { null } ?: throw AppError.Configuration("Set the endpoint namespace first.")
        val name = settings.endpointName?.trim()?.ifBlank { null } ?: throw AppError.Configuration("Set the endpoint name first.")
        api.send(Request.Builder().url("https://api.endpoints.huggingface.cloud/v2/endpoint/$ns/$name/resume").post(ByteArray(0).toRequestBody()).build())
    }

    private fun endpointStateHint(state: String): String? = when (state) {
        "running" -> null
        "scaledToZero" -> "Scaled to zero: the first request wakes it up (can take several minutes)."
        "paused" -> "Paused: resume it (Settings → provider → Resume endpoint) before generating."
        "initializing", "pending", "updating" -> "Starting up — requests will be retried until it's ready."
        "failed", "updateFailed" -> "The endpoint failed to start; check its logs on huggingface.co."
        else -> null
    }

    private suspend fun probeReachable() {
        val response = api.execute(Request.Builder().url(config.baseUrl.trim()).get().build())
        response.close()
        if (response.code == 401) throw AppError.Unauthorized(config.name)
        if (response.code == 403) throw AppError.Forbidden(config.name)
    }

    override suspend fun fetchCatalog(): BackendCatalog {
        val suggestions = when (settings.mode) {
            HfMode.INFERENCE_PROVIDERS -> runCatching {
                val url = "${HfQueries.BASE}api/models".toHttpUrl().newBuilder()
                    .addQueryParameter("pipeline_tag", "text-to-image")
                    .addQueryParameter("inference_provider", "all")
                    .addQueryParameter("sort", "trendingScore")
                    .addQueryParameter("limit", "40")
                    .build()
                api.getJson(url.toString()).arrOrNull?.mapNotNull { it.objOrNull?.str("id") }.orEmpty()
            }.getOrDefault(emptyList())
            else -> emptyList()
        }
        val modelId = settings.defaultModelId
        val info = if (settings.mode == HfMode.INFERENCE_PROVIDERS && !modelId.isNullOrBlank()) runCatching { modelInfo(modelId, force = true) }.getOrNull() else null
        return BackendCatalog(
            providerId = config.id,
            fetchedAt = System.currentTimeMillis(),
            serverLabel = settings.mode.displayName,
            models = suggestions,
            hfModel = info,
        )
    }

    override suspend fun refreshModelInfo(catalog: BackendCatalog, modelId: String?): BackendCatalog {
        if (settings.mode != HfMode.INFERENCE_PROVIDERS || modelId.isNullOrBlank()) return catalog
        return catalog.copy(hfModel = modelInfo(modelId.trim(), force = false))
    }

    private suspend fun modelInfo(modelId: String, force: Boolean): HfModelInfo {
        val cached = modelInfoCache[modelId]
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000) return cached.second
        val url = "${HfQueries.BASE}api/models/$modelId".toHttpUrl().newBuilder().apply {
            listOf("inferenceProviderMapping", "pipeline_tag", "gated", "tags", "cardData").forEach { addQueryParameter("expand[]", it) }
        }.build()
        val dto = try {
            AppJson.decodeFromJsonElement(HfModelDto.serializer(), api.getJson(url.toString()))
        } catch (e: AppError.NotFound) {
            throw AppError.NotFound("Hugging Face model \"$modelId\"", "Check the repo id (owner/name).")
        }
        val info = HfMapper.toModelInfo(dto)
        modelInfoCache[modelId] = System.currentTimeMillis() to info
        return info
    }

    // -----------------------------------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------------------------------

    fun planRoute(info: HfModelInfo, task: String): HfRoutePlan? {
        val candidates = info.mappings.filter { it.isLive && it.task == task && it.provider in HfRouterProviders.supported }
        val chosen = if (settings.routingProvider == HfRouterProviders.AUTO) candidates.firstOrNull()
        else candidates.firstOrNull { it.provider == settings.routingProvider }
        chosen ?: return null
        val adapterUrl = if (chosen.adapter == "lora" && chosen.adapterWeightsPath != null) {
            if (chosen.provider == "wavespeed") info.modelId else HfQueries.resolveUrl(info.modelId, chosen.adapterWeightsPath)
        } else null
        return when (chosen.provider) {
            "fal-ai" -> {
                val loraCapable = "lora" in chosen.providerId
                // fal-ai/flux/dev itself has no LoRA input; runFal switches to fal-ai/flux-lora when LoRAs are applied.
                HfRoutePlan(
                    "fal-ai", chosen.providerId, task,
                    loraSupport = if (loraCapable || chosen.providerId == "fal-ai/flux/dev") LoraSupport.RemoteWeights(maxCount = 5, perLoraStrength = true) else LoraSupport.None,
                    nativeBatch = true, cancellable = true, adapterLoraUrl = adapterUrl,
                )
            }
            "replicate" -> HfRoutePlan(
                "replicate", chosen.providerId, task,
                loraSupport = if ("lora" in chosen.providerId && adapterUrl == null) LoraSupport.RemoteWeights(1, perLoraStrength = true) else LoraSupport.None,
                nativeBatch = true, cancellable = true, adapterLoraUrl = adapterUrl,
            )
            "wavespeed" -> HfRoutePlan(
                "wavespeed", chosen.providerId, task,
                loraSupport = if ("lora" in chosen.providerId) LoraSupport.RemoteWeights(3, perLoraStrength = true) else LoraSupport.None,
                nativeBatch = false, cancellable = false, adapterLoraUrl = adapterUrl,
            )
            "together" -> HfRoutePlan("together", chosen.providerId, task, LoraSupport.None, nativeBatch = true, cancellable = false)
            "nscale" -> HfRoutePlan("nscale", chosen.providerId, task, LoraSupport.None, nativeBatch = false, cancellable = false)
            "hf-inference" -> HfRoutePlan("hf-inference", info.modelId, task, LoraSupport.None, nativeBatch = false, cancellable = false)
            else -> null
        }
    }

    override fun capabilities(catalog: BackendCatalog?, params: GenerationParameters): ProviderCapabilities {
        val arch = params.architecture
        val common = ProviderCapabilities(
            negativePrompt = true,
            size = SizeConstraints(min = 256, max = 2048, multipleOf = 16),
            steps = 1..100,
            cfg = if (arch.usesDistilledGuidance) null else 0f..20f,
            distilledGuidance = arch.usesDistilledGuidance,
            seed = true,
            batch = BatchSupport.Sequential(4),
            cancellation = CancelSupport.CLIENT_ONLY,
            progress = ProgressSupport.NONE,
        )
        if (settings.mode != HfMode.INFERENCE_PROVIDERS) {
            return common.copy(
                schedulers = DIFFUSERS_SCHEDULERS,
                freeformScheduler = true,
                notes = listOf("Dedicated/custom endpoints run one fixed model; LoRA stacking depends on its handler and isn't sent."),
            )
        }
        val modelId = params.checkpoint?.ifBlank { null } ?: settings.defaultModelId
        val info = catalog?.hfModel?.takeIf { it.modelId == modelId }
        val task = if (params.initImagePath != null) "image-to-image" else "text-to-image"
        val plan = info?.let { planRoute(it, task) }
        val img2imgAvailable = info?.let { planRoute(it, "image-to-image") }?.provider in setOf("fal-ai", "replicate", "hf-inference")
        val notes = buildList {
            when {
                modelId.isNullOrBlank() -> add("Enter a Hugging Face model id to see which providers can run it.")
                info == null -> add("Checking which Inference Providers serve $modelId…")
                plan == null -> add(
                    "No supported provider currently serves $modelId for $task" +
                        (if (settings.routingProvider != HfRouterProviders.AUTO) " via ${HfRouterProviders.displayName(settings.routingProvider)}" else "") +
                        ". Live: " + info.mappings.filter { it.isLive }.joinToString { "${it.provider} (${it.task})" }.ifBlank { "none" },
                )
                else -> add("Routed via ${HfRouterProviders.displayName(plan.provider)} → ${plan.endpoint}")
            }
            if (info?.gated == true) add("This model is gated: accept its license on huggingface.co with the same account.")
            if (plan?.provider == "fal-ai" || plan?.provider == "replicate") {
                add(if (settings.disableProviderSafetyChecker) "Provider safety checker: off (requested)." else "Provider safety checker: provider default (may blank flagged images).")
            }
            if (info?.isLora == true) add("This repo is a LoRA adapter; it is applied on its base model by the provider.")
        }
        return common.copy(
            batch = if (plan?.nativeBatch == true) BatchSupport.Native(4) else BatchSupport.Sequential(4),
            schedulers = if (plan?.provider == "hf-inference") DIFFUSERS_SCHEDULERS else emptyList(),
            freeformScheduler = plan?.provider == "hf-inference",
            img2img = img2imgAvailable,
            checkpointSelection = CheckpointSelection.FreeText("owner/model, e.g. black-forest-labs/FLUX.1-dev", catalog?.models.orEmpty()),
            loras = plan?.loraSupport ?: LoraSupport.None,
            cancellation = if (plan?.cancellable == true) CancelSupport.SERVER else CancelSupport.CLIENT_ONLY,
            progress = if (plan?.provider in setOf("fal-ai", "replicate", "wavespeed")) ProgressSupport.STATUS else ProgressSupport.NONE,
            notes = notes,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------------------------

    @Volatile private var activeCancel: (suspend () -> Unit)? = null

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val p = request.params
        val initBytes = p.initImagePath?.let { path ->
            File(path).takeIf { it.exists() }?.readBytes() ?: throw AppError.Storage("The init image is missing; pick it again.")
        }
        when (settings.mode) {
            HfMode.INFERENCE_PROVIDERS -> generateRouted(p, initBytes)
            HfMode.DEDICATED_ENDPOINT, HfMode.CUSTOM_URL -> generateDirect(p, initBytes)
        }
        send(GenerationEvent.Completed())
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(remoteJobId: String?) {
        activeCancel?.let { runCatching { it() } }
        activeCancel = null
    }

    private suspend fun ProducerScope<GenerationEvent>.generateRouted(p: GenerationParameters, initBytes: ByteArray?) {
        val modelId = p.checkpoint?.trim()?.ifBlank { null } ?: settings.defaultModelId?.trim()?.ifBlank { null }
            ?: throw AppError.Configuration("Enter a Hugging Face model id.")
        val info = modelInfo(modelId, force = false)
        val task = if (initBytes != null) "image-to-image" else "text-to-image"
        val plan = planRoute(info, task) ?: throw AppError.Unsupported(
            "Running $modelId ($task)",
            "the Inference Providers you enabled (live: ${info.mappings.filter { it.isLive }.joinToString { it.provider }.ifBlank { "none" }})",
        )
        send(GenerationEvent.Status("Routing via ${HfRouterProviders.displayName(plan.provider)} (${plan.endpoint})"))
        val remoteLoras = p.loras.filter { it.enabled && it.hfRepoId != null && it.hfWeightFile != null }

        val batches = if (plan.nativeBatch) listOf(p.batchSize to p.seed)
        else (0 until p.batchSize).map { i -> 1 to (if (p.seed >= 0) p.seed + i else p.seed) }
        batches.forEachIndexed { index, (count, seed) ->
            if (batches.size > 1) send(GenerationEvent.Progress(fraction = index.toFloat() / batches.size, message = "Image ${index + 1} of ${batches.size}"))
            val images = when (plan.provider) {
                "hf-inference" -> listOf(runHfInference("https://router.huggingface.co/hf-inference/models/$modelId", p, seed, initBytes))
                "fal-ai" -> runFal(plan, p, count, seed, initBytes, remoteLoras)
                "replicate" -> runReplicate(plan, p, count, seed, initBytes, remoteLoras)
                "together" -> runOpenAiStyle("https://router.huggingface.co/together/v1/images/generations", plan.endpoint, p, count, seed, together = true)
                "nscale" -> runOpenAiStyle("https://router.huggingface.co/nscale/v1/images/generations", plan.endpoint, p, count, seed, together = false)
                "wavespeed" -> runWavespeed(plan, p, seed, remoteLoras)
                else -> throw AppError.Unsupported("Provider ${plan.provider}", "this app version")
            }
            images.forEach { send(GenerationEvent.Image(it)) }
        }
    }

    private fun guidance(p: GenerationParameters) = if (p.architecture.usesDistilledGuidance) p.distilledGuidance else p.cfgScale

    private fun hfParameters(p: GenerationParameters, seed: Long): Map<String, Any?> = buildMap {
        p.negativePrompt.ifBlank { null }?.let { put("negative_prompt", it) }
        put("width", p.width)
        put("height", p.height)
        put("num_inference_steps", p.steps)
        put("guidance_scale", guidance(p))
        if (seed >= 0) put("seed", seed)
        p.scheduler?.ifBlank { null }?.let { put("scheduler", it) }
    }

    /** HF Inference / dedicated endpoints: `{inputs, parameters}` → raw image bytes (or JSON with base64). */
    private suspend fun ProducerScope<GenerationEvent>.runHfInference(url: String, p: GenerationParameters, seed: Long, initBytes: ByteArray?): GeneratedImageData {
        val body = if (initBytes != null) {
            mapOf("inputs" to ImagePayloads.encode(initBytes), "parameters" to hfParameters(p, seed) + mapOf("prompt" to p.prompt, "strength" to p.denoisingStrength))
        } else {
            mapOf("inputs" to p.prompt, "parameters" to hfParameters(p, seed))
        }.toJsonElement()
        val request = Request.Builder().url(url).post(body.toRequestBody()).header("Accept", "image/png").build()
        val (bytes, contentType) = Retry.withBackoff(
            maxAttempts = if (settings.mode == HfMode.DEDICATED_ENDPOINT) 40 else http.maxRetries + 2,
            // Generation POSTs are only retried when no work can have started server-side (credits!).
            retryIf = { it is AppError.ModelLoading || it is AppError.RateLimited || Retry.beforeServerReached(it) || (it is AppError.Server && it.isTransient) },
            onRetry = { _, error, delayMs -> send(GenerationEvent.Status("${error.userMessage} Retrying in ${delayMs / 1000}s…")) },
        ) { api.bytes(request) }
        return imageFromResponse(bytes, contentType, seed)
    }

    private suspend fun imageFromResponse(bytes: ByteArray, contentType: String?, seed: Long): GeneratedImageData {
        val sniffed = ImageStorage.sniffMime(bytes)
        if (sniffed != null) return GeneratedImageData(bytes, sniffed, seed.takeIf { it >= 0 })
        val json = runCatching { AppJson.parseToJsonElement(String(bytes, Charsets.UTF_8)) }.getOrNull()
            ?: throw AppError.Parse(config.name, "Response is neither an image nor JSON (${contentType ?: "unknown type"})")
        val value = findImageValue(json) ?: throw AppError.Parse(config.name, "No image found in JSON response")
        val (data, mime) = ImagePayloads.resolve(value, http, config.host)
        return GeneratedImageData(data, mime, seed.takeIf { it >= 0 })
    }

    /** Looks for common image fields: data[0].b64_json, images[0], image, output[0], generated_image… */
    private fun findImageValue(json: JsonElement): String? {
        json.arrOrNull?.firstOrNull()?.let { first -> first.stringOrNull?.let { return it }; findImageValue(first)?.let { return it } }
        val o = json.objOrNull ?: return null
        o.arr("data")?.firstOrNull()?.objOrNull?.let { d -> (d.str("b64_json") ?: d.str("url"))?.let { return it } }
        for (key in listOf("image", "images", "output", "generated_image", "image_base64", "base64")) {
            val v = o[key] ?: continue
            v.stringOrNull?.let { return it }
            v.arrOrNull?.firstOrNull()?.let { first -> first.stringOrNull ?: first.objOrNull?.let { it.str("url") ?: it.str("b64_json") } }?.let { return it }
        }
        return null
    }

    private suspend fun ProducerScope<GenerationEvent>.runFal(
        plan: HfRoutePlan,
        p: GenerationParameters,
        count: Int,
        seed: Long,
        initBytes: ByteArray?,
        loras: List<LoraSelection>,
    ): List<GeneratedImageData> {
        val loraPayload = buildList {
            plan.adapterLoraUrl?.let { add(mapOf("path" to it, "scale" to 1.0)) }
            loras.forEach { add(mapOf("path" to HfQueries.resolveUrl(it.hfRepoId!!, it.hfWeightFile!!), "scale" to it.strength)) }
        }
        val endpoint = if (loraPayload.isNotEmpty() && plan.endpoint == "fal-ai/flux/dev") "fal-ai/flux-lora" else plan.endpoint
        if (endpoint != plan.endpoint) send(GenerationEvent.Status("Using fal-ai/flux-lora to apply LoRAs"))
        val body = buildMap<String, Any?> {
            put("prompt", p.prompt)
            putAll(hfParameters(p, seed))
            put("image_size", mapOf("width" to p.width, "height" to p.height))
            put("num_images", count)
            put("enable_safety_checker", !settings.disableProviderSafetyChecker)
            if (loraPayload.isNotEmpty()) put("loras", loraPayload)
            if (endpoint == "fal-ai/lora") put("model_name", "stabilityai/stable-diffusion-xl-base-1.0")
            if (initBytes != null) {
                put("image_url", ImagePayloads.dataUri(initBytes))
                put("strength", p.denoisingStrength)
            }
        }.toJsonElement()
        val submit = api.postJson("https://router.huggingface.co/fal-ai/$endpoint?_subdomain=queue", body).objOrNull
            ?: throw AppError.Parse("fal.ai", "empty submit response")
        val responseUrl = submit.str("response_url") ?: throw AppError.Parse("fal.ai", "no response_url in queue response")
        val path = URI(responseUrl).path
        val statusUrl = "https://router.huggingface.co/fal-ai$path/status?_subdomain=queue"
        val resultUrl = "https://router.huggingface.co/fal-ai$path?_subdomain=queue"
        activeCancel = {
            api.send(Request.Builder().url("https://router.huggingface.co/fal-ai$path/cancel?_subdomain=queue").put(ByteArray(0).toRequestBody()).build())
        }
        var status = submit.str("status")
        while (status != "COMPLETED") {
            delay(700)
            val s = api.getJson(statusUrl).objOrNull ?: continue
            status = s.str("status")
            when (status) {
                "IN_QUEUE" -> send(GenerationEvent.Queued(s["queue_position"].longValueOrNull?.toInt()?.plus(1)))
                "IN_PROGRESS" -> send(GenerationEvent.Progress(fraction = null, message = "Generating on fal.ai"))
                "COMPLETED", null -> Unit
                else -> if (status.contains("ERROR", true) || status.contains("FAIL", true)) throw AppError.GenerationFailed("fal.ai reported $status")
            }
        }
        activeCancel = null
        val result = api.getJson(resultUrl).objOrNull ?: throw AppError.Parse("fal.ai", "empty result")
        val flagged = result.arr("has_nsfw_concepts")?.map { it.boolValueOrNull == true }.orEmpty()
        if (flagged.any { it } && !settings.disableProviderSafetyChecker) {
            send(GenerationEvent.Warning("fal.ai's safety checker flagged ${flagged.count { it }} image(s); the provider may have returned them blanked. You can request it off in the provider settings."))
        }
        val images = result.arr("images")?.mapNotNull { it.objOrNull?.str("url") }.orEmpty()
        if (images.isEmpty()) throw AppError.GenerationFailed("fal.ai returned no images${result.str("detail")?.let { ": $it" } ?: ""}")
        send(GenerationEvent.Status("Downloading results…"))
        return images.map { url ->
            val (bytes, mime) = ImagePayloads.resolve(url, http, null)
            GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 })
        }
    }

    private suspend fun ProducerScope<GenerationEvent>.runReplicate(
        plan: HfRoutePlan,
        p: GenerationParameters,
        count: Int,
        seed: Long,
        initBytes: ByteArray?,
        loras: List<LoraSelection>,
    ): List<GeneratedImageData> {
        val input = buildMap<String, Any?> {
            put("prompt", p.prompt)
            putAll(hfParameters(p, seed))
            if (p.architecture.usesDistilledGuidance) put("guidance", p.distilledGuidance)
            put("aspect_ratio", closestAspect(p.width, p.height))
            put("num_outputs", count)
            if (settings.disableProviderSafetyChecker) put("disable_safety_checker", true)
            val lora = plan.adapterLoraUrl ?: loras.firstOrNull()?.let { HfQueries.resolveUrl(it.hfRepoId!!, it.hfWeightFile!!) }
            if (lora != null) {
                put("lora_weights", lora)
                put("lora_scale", loras.firstOrNull()?.strength ?: 1f)
            }
            if (initBytes != null) {
                put("image", ImagePayloads.dataUri(initBytes))
                put("prompt_strength", p.denoisingStrength)
            }
        }
        val body = buildMap<String, Any?> {
            put("input", input)
            if (":" in plan.endpoint) put("version", plan.endpoint.substringAfter(':'))
        }.toJsonElement()
        val url = if (":" in plan.endpoint) "https://router.huggingface.co/replicate/v1/predictions"
        else "https://router.huggingface.co/replicate/v1/models/${plan.endpoint}/predictions"
        var prediction = api.postJson(url, body, headers = mapOf("Prefer" to "wait")).objOrNull
            ?: throw AppError.Parse("Replicate", "empty response")
        val id = prediction.str("id")
        if (id != null) {
            activeCancel = { api.send(Request.Builder().url("https://router.huggingface.co/replicate/v1/predictions/$id/cancel").post(ByteArray(0).toRequestBody()).build()) }
        }
        while (prediction.str("status") !in setOf("succeeded", "failed", "canceled") && prediction["output"] == null) {
            if (id == null) throw AppError.Parse("Replicate", "prediction has no id")
            send(GenerationEvent.Progress(fraction = null, message = "Replicate: ${prediction.str("status") ?: "starting"}"))
            delay(1000)
            prediction = api.getJson("https://router.huggingface.co/replicate/v1/predictions/$id").objOrNull ?: prediction
        }
        activeCancel = null
        if (prediction.str("status") == "failed") throw AppError.GenerationFailed("Replicate: ${prediction["error"].stringOrNull ?: "prediction failed"}")
        if (prediction.str("status") == "canceled") throw AppError.GenerationFailed("Replicate prediction was canceled.")
        val outputs = when (val out = prediction["output"]) {
            null -> emptyList()
            else -> out.arrOrNull?.mapNotNull { it.stringOrNull } ?: listOfNotNull(out.stringOrNull)
        }
        if (outputs.isEmpty()) throw AppError.GenerationFailed("Replicate returned no images.")
        return outputs.map { value ->
            val (bytes, mime) = ImagePayloads.resolve(value, http, null)
            GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 })
        }
    }

    private suspend fun runOpenAiStyle(url: String, model: String, p: GenerationParameters, count: Int, seed: Long, together: Boolean): List<GeneratedImageData> {
        val body = buildMap<String, Any?> {
            put("model", model)
            put("prompt", p.prompt)
            p.negativePrompt.ifBlank { null }?.let { put("negative_prompt", it) }
            put("width", p.width)
            put("height", p.height)
            if (together) put("steps", p.steps) else put("num_inference_steps", p.steps)
            put("guidance_scale", guidance(p))
            if (seed >= 0) put("seed", seed)
            put("n", count)
            put("response_format", if (together) "base64" else "b64_json")
        }.toJsonElement()
        val json = api.postJson(url, body).objOrNull ?: throw AppError.Parse(config.name, "empty response")
        val values = json.arr("data")?.mapNotNull { it.objOrNull?.let { d -> d.str("b64_json") ?: d.str("url") } }.orEmpty()
        if (values.isEmpty()) throw AppError.GenerationFailed("The provider returned no images.")
        return values.map { v ->
            val (bytes, mime) = ImagePayloads.resolve(v, http, null)
            GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 })
        }
    }

    private suspend fun ProducerScope<GenerationEvent>.runWavespeed(plan: HfRoutePlan, p: GenerationParameters, seed: Long, loras: List<LoraSelection>): List<GeneratedImageData> {
        val loraPayload = buildList {
            plan.adapterLoraUrl?.let { add(mapOf("path" to it, "scale" to 1.0)) }
            loras.forEach { add(mapOf("path" to it.hfRepoId, "scale" to it.strength)) }
        }
        val body = buildMap<String, Any?> {
            put("prompt", p.prompt)
            putAll(hfParameters(p, seed))
            put("size", "${p.width}*${p.height}")
            if (loraPayload.isNotEmpty()) put("loras", loraPayload)
            if (settings.disableProviderSafetyChecker) put("enable_safety_checker", false)
        }.toJsonElement()
        val submit = api.postJson("https://router.huggingface.co/wavespeed/api/v3/${plan.endpoint}", body).objOrNull
            ?: throw AppError.Parse("WaveSpeed", "empty submit response")
        val getUrl = submit.obj("data")?.obj("urls")?.str("get") ?: throw AppError.Parse("WaveSpeed", "no result URL")
        val pollUrl = "https://router.huggingface.co/wavespeed${URI(getUrl).path}"
        while (true) {
            delay(800)
            val data = api.getJson(pollUrl).objOrNull?.obj("data") ?: continue
            when (data.str("status")) {
                "completed" -> {
                    val outputs = data.arr("outputs")?.mapNotNull { it.stringOrNull }.orEmpty()
                    if (outputs.isEmpty()) throw AppError.GenerationFailed("WaveSpeed returned no images.")
                    return outputs.map { url ->
                        val (bytes, mime) = ImagePayloads.resolve(url, http, null)
                        GeneratedImageData(bytes, mime, seed.takeIf { it >= 0 })
                    }
                }
                "failed" -> throw AppError.GenerationFailed("WaveSpeed: ${data.str("error") ?: "task failed"}")
                else -> send(GenerationEvent.Progress(fraction = null, message = "WaveSpeed: ${data.str("status") ?: "processing"}"))
            }
        }
    }

    private suspend fun ProducerScope<GenerationEvent>.generateDirect(p: GenerationParameters, initBytes: ByteArray?) {
        if (settings.mode == HfMode.DEDICATED_ENDPOINT) {
            val status = runCatching { endpointStatus() }.getOrNull()
            when (status?.first) {
                "paused" -> throw AppError.Configuration("The Inference Endpoint is paused. Resume it in the provider settings or on huggingface.co.")
                "failed", "updateFailed" -> throw AppError.GenerationFailed("The Inference Endpoint is in a failed state; check its logs on huggingface.co.")
                "scaledToZero", "initializing", "pending" -> send(GenerationEvent.Status("Endpoint is ${status.first} — waking it up, this can take a few minutes…"))
            }
        }
        val url = config.baseUrl.trimBaseUrl()
        for (i in 0 until p.batchSize) {
            val seed = if (p.seed >= 0) p.seed + i else p.seed
            if (p.batchSize > 1) send(GenerationEvent.Progress(fraction = i.toFloat() / p.batchSize, message = "Image ${i + 1} of ${p.batchSize}"))
            send(GenerationEvent.Image(runHfInference(url, p, seed, initBytes)))
        }
    }

    private fun closestAspect(width: Int, height: Int): String {
        val ratios = listOf("1:1", "16:9", "21:9", "3:2", "2:3", "4:5", "5:4", "3:4", "4:3", "9:16", "9:21")
        val target = width.toDouble() / height
        return ratios.minBy { r -> val (a, b) = r.split(':').map { it.toDouble() }; kotlin.math.abs(a / b - target) }
    }

    companion object {
        val DIFFUSERS_SCHEDULERS = listOf(
            "EulerDiscreteScheduler", "EulerAncestralDiscreteScheduler", "DPMSolverMultistepScheduler", "DPMSolverSinglestepScheduler",
            "DDIMScheduler", "PNDMScheduler", "LMSDiscreteScheduler", "UniPCMultistepScheduler", "HeunDiscreteScheduler",
            "FlowMatchEulerDiscreteScheduler",
        )
    }
}

class HuggingFaceProviderFactory @Inject constructor() : ProviderAdapterFactory {
    override fun create(config: ProviderConfig, http: ProviderHttp): ImageGenerationProvider = HuggingFaceProvider(config, http)
}
