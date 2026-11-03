package com.unfiltered.studio.data.providers.a1111

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.arr
import com.unfiltered.studio.core.longValueOrNull
import com.unfiltered.studio.core.str
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.data.providers.ImagePayloads
import com.unfiltered.studio.data.providers.LoraSyntax
import com.unfiltered.studio.data.providers.ProviderAdapterFactory
import com.unfiltered.studio.data.providers.buildRetrofit
import com.unfiltered.studio.data.providers.optionalCall
import com.unfiltered.studio.data.providers.retrofitCall
import com.unfiltered.studio.data.storage.LoraMetadataParser
import com.unfiltered.studio.domain.models.A1111Settings
import com.unfiltered.studio.domain.models.A1111Variant
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BackendModel
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CancelSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.ComponentSlot
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.GeneratedImageData
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.HiresSupport
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ProgressSupport
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.SizeConstraints
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

class A1111Provider(
    override val config: ProviderConfig,
    private val http: ProviderHttp,
) : ImageGenerationProvider {

    private val settings = config.settings as? A1111Settings ?: A1111Settings()
    private val service = config.name
    private val host = config.host
    private val api: A1111Api = buildRetrofit(config.baseUrl, http.api.client).create(A1111Api::class.java)

    @Volatile private var forgeDetected: Boolean? = null

    private suspend fun <T> call(what: String = "Endpoint", block: suspend () -> T): T = retrofitCall(service, host, what, block)

    private suspend fun isForge(): Boolean = when (settings.variant) {
        A1111Variant.FORGE -> true
        A1111Variant.AUTOMATIC1111 -> false
        A1111Variant.AUTO -> forgeDetected ?: optionalCall(service, host) { api.sdModules() }.isSuccess.also { forgeDetected = it }
    }

    override suspend fun testConnection(): ConnectionInfo {
        val models = call("Model list (is the server started with --api?)") { api.sdModels() }
        val forge = isForge()
        val loras = optionalCall(service, host) { api.loras() }.getOrNull()
        return ConnectionInfo(
            serverName = if (forge) "Stable Diffusion WebUI Forge" else "AUTOMATIC1111 WebUI",
            details = listOfNotNull(
                "${models.size} checkpoint(s)",
                loras?.let { "${it.size} LoRA(s)" },
                if (config.baseUrl.startsWith("http://") && config.auth.type.name != "NONE") "Warning: credentials are sent unencrypted over http://" else null,
            ),
        )
    }

    override suspend fun fetchCatalog(): BackendCatalog = coroutineScope {
        val forge = isForge()
        val models = async { call("Model list") { api.sdModels() } }
        val samplers = async { optionalCall(service, host) { api.samplers() } }
        val schedulers = async { optionalCall(service, host) { api.schedulers() } }
        val upscalers = async { optionalCall(service, host) { api.upscalers() } }
        val latentModes = async { optionalCall(service, host) { api.latentUpscaleModes() } }
        val vaes = async { if (forge) null else optionalCall(service, host) { api.sdVae() } }
        val modules = async { if (forge) optionalCall(service, host) { api.sdModules() } else null }
        val loras = async { optionalCall(service, host) { api.loras() } }
        val embeddings = async { optionalCall(service, host) { api.embeddings() } }

        val warnings = mutableListOf<String>()
        val flags = mutableSetOf<String>()
        if (forge) flags += BackendCatalog.FLAG_FORGE
        val schedulerList = schedulers.await().getOrNull()?.map { it.name }
        if (schedulerList != null) flags += BackendCatalog.FLAG_SCHEDULERS
        val loraList = loras.await().also {
            if (it.isFailure) warnings += "LoRA listing (/sdapi/v1/loras) is unavailable — LoRA names must be linked manually."
        }.getOrNull()
        if (loraList != null) flags += BackendCatalog.FLAG_LORA_LISTING
        val vaeList = vaes.await()?.getOrNull()
        if (vaeList != null) flags += BackendCatalog.FLAG_VAE_LISTING
        if (samplers.await().isFailure) warnings += "Sampler list unavailable; the server default sampler will be used."

        BackendCatalog(
            providerId = config.id,
            fetchedAt = System.currentTimeMillis(),
            serverLabel = if (forge) "Forge" else "AUTOMATIC1111",
            checkpoints = models.await().map { m ->
                BackendModel(name = m.title, title = m.modelName, path = m.filename, hash = m.sha256 ?: m.hash, architecture = ModelArchitecture.detect(m.title))
            },
            loras = loraList.orEmpty().map { lora ->
                val meta = lora.metadata.orEmpty().mapValues { (_, v) -> v.stringOrNull ?: v.toString() }
                BackendModel(
                    name = lora.name,
                    title = lora.alias?.takeIf { it.isNotBlank() } ?: lora.name,
                    path = lora.path,
                    architecture = LoraMetadataParser.parse(meta).architecture ?: ModelArchitecture.detect(lora.name),
                    metadata = meta,
                )
            },
            vaes = vaeList.orEmpty().map { BackendModel(name = it.modelName, path = it.filename) },
            samplers = samplers.await().getOrNull().orEmpty().map { it.name },
            schedulers = schedulerList.orEmpty(),
            upscalers = latentModes.await().getOrNull().orEmpty().map { it.name } +
                upscalers.await().getOrNull().orEmpty().map { it.name }.filterNot { it.equals("None", true) },
            embeddings = embeddings.await().getOrNull()?.loaded?.keys?.toList().orEmpty(),
            componentFiles = modules.await()?.getOrNull()?.let { list ->
                mapOf(SLOT_FORGE_MODULES to list.map { it.filename ?: it.modelName })
            }.orEmpty(),
            flags = flags,
            warnings = warnings,
        )
    }

    override fun capabilities(catalog: BackendCatalog?, params: GenerationParameters): ProviderCapabilities {
        val arch = params.architecture
        val forge = catalog?.has(BackendCatalog.FLAG_FORGE) ?: (settings.variant == A1111Variant.FORGE)
        val notes = buildList {
            if (!arch.usesNegativePrompt) add("${arch.displayName} ignores the negative prompt unless CFG is above 1.")
            if (arch.usesDistilledGuidance && !forge) add("Distilled CFG requires Forge; AUTOMATIC1111 can't run ${arch.displayName} natively.")
            if (catalog?.has(BackendCatalog.FLAG_LORA_LISTING) == false) add("This server doesn't list LoRAs; link LoRA names manually in the LoRA editor.")
            if (params.checkpoint.isNullOrBlank()) add("No checkpoint selected — the server's currently loaded model is used.")
        }
        return ProviderCapabilities(
            negativePrompt = true,
            size = SizeConstraints(min = 64, max = 2048, multipleOf = 8),
            steps = 1..150,
            cfg = 1f..30f,
            distilledGuidance = forge && arch.usesDistilledGuidance,
            seed = true,
            batch = BatchSupport.Native(4),
            samplers = catalog?.samplers.orEmpty(),
            schedulers = catalog?.schedulers.orEmpty(),
            clipSkip = arch.supportsClipSkip,
            hiresFix = HiresSupport(catalog?.upscalers?.ifEmpty { null } ?: listOf("Latent")),
            img2img = true,
            checkpointSelection = CheckpointSelection.FromList(catalog?.checkpoints?.map { it.name }.orEmpty(), allowEmpty = true),
            vaes = if (forge) null else listOf("Automatic", "None") + catalog?.vaes?.map { it.name }.orEmpty(),
            components = if (forge) listOf(
                ComponentSlot(SLOT_FORGE_MODULES, "VAE / text encoders (Forge)", catalog?.componentFiles?.get(SLOT_FORGE_MODULES).orEmpty(), required = false, multiple = true),
            ) else emptyList(),
            loras = LoraSupport.PromptSyntax,
            cancellation = CancelSupport.SERVER,
            progress = ProgressSupport.STEPS,
            livePreview = settings.livePreview,
            notes = notes,
        )
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val p = request.params
        val forge = isForge()
        val initBytes = p.initImagePath?.let { path ->
            File(path).takeIf { it.exists() }?.readBytes() ?: throw AppError.Storage("The init image is missing; pick it again.")
        }
        val body = buildPayload(p, forge, initBytes)
        send(GenerationEvent.Submitted(null))
        send(GenerationEvent.Status("Waiting for ${config.name}…"))

        val poller = launch { pollProgress() }
        val response = try {
            call("Generation") { if (initBytes != null) api.img2img(body) else api.txt2img(body) }
        } finally {
            poller.cancel()
        }

        val info = response.info?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val seeds = info?.arr("all_seeds")?.mapNotNull { it.longValueOrNull }.orEmpty()
        val infotexts = info?.arr("infotexts")?.mapNotNull { it.stringOrNull }.orEmpty()
        val first = info?.get("index_of_first_image")?.longValueOrNull?.toInt() ?: 0
        // Extensions (ControlNet etc.) may append extra images after the batch; keep only the batch.
        val images = response.images.orEmpty().drop(first).take(p.batchSize.coerceAtLeast(1))
        if (images.isEmpty()) throw AppError.GenerationFailed("The server finished without returning images (it may have been interrupted).")
        images.forEachIndexed { index, b64 ->
            val (bytes, mime) = ImagePayloads.decode(b64)
            send(
                GenerationEvent.Image(
                    GeneratedImageData(bytes, mime, seed = seeds.getOrNull(index) ?: (p.seed + index), infotext = infotexts.getOrNull(index)),
                ),
            )
        }
        send(GenerationEvent.Completed(info?.str("sd_model_name")))
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<GenerationEvent>.pollProgress() {
        var lastPreview = 0
        delay(600)
        while (isActive) {
            val progress = try {
                api.progress(skipCurrentImage = !settings.livePreview)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                null
            }
            val state = progress?.state
            if (progress != null && state != null && state.jobCount > 0) {
                send(
                    GenerationEvent.Progress(
                        fraction = progress.progress.toFloat().coerceIn(0f, 1f),
                        step = state.samplingStep.takeIf { state.samplingSteps > 0 },
                        totalSteps = state.samplingSteps.takeIf { it > 0 },
                        etaSeconds = progress.etaRelative.toFloat().takeIf { it > 0f },
                        message = if (state.jobCount > 1) "Job ${state.jobNo + 1} of ${state.jobCount}" else null,
                    ),
                )
                val preview = progress.currentImage
                if (!preview.isNullOrBlank() && preview.hashCode() != lastPreview) {
                    lastPreview = preview.hashCode()
                    val bytes = try { ImagePayloads.decode(preview).first } catch (e: AppError) { null }
                    if (bytes != null) send(GenerationEvent.Preview(bytes))
                }
            }
            delay(1000)
        }
    }

    internal fun buildPayload(p: GenerationParameters, forge: Boolean, initBytes: ByteArray?): JsonObject = buildJsonObject {
        put("prompt", LoraSyntax.appendTags(p.prompt, p.loras))
        put("negative_prompt", p.negativePrompt)
        put("seed", p.seed)
        put("subseed", -1)
        put("subseed_strength", 0)
        p.sampler?.takeIf { it.isNotBlank() }?.let { put("sampler_name", it) }
        p.scheduler?.takeIf { it.isNotBlank() }?.let { put("scheduler", it) }
        put("batch_size", p.batchSize)
        put("n_iter", 1)
        put("steps", p.steps)
        put("cfg_scale", p.cfgScale)
        put("width", p.width)
        put("height", p.height)
        if (forge && p.architecture.usesDistilledGuidance) put("distilled_cfg_scale", p.distilledGuidance)
        if (initBytes != null) {
            put("init_images", JsonArray(listOf(JsonPrimitive(ImagePayloads.encode(initBytes)))))
            put("denoising_strength", p.denoisingStrength)
            put("resize_mode", 0)
            put("include_init_images", false)
        } else if (p.hiresFix.enabled) {
            put("enable_hr", true)
            put("hr_scale", p.hiresFix.scale)
            put("hr_upscaler", p.hiresFix.upscaler ?: "Latent")
            put("hr_second_pass_steps", p.hiresFix.steps)
            put("denoising_strength", p.hiresFix.denoise)
        }
        put(
            "override_settings",
            buildJsonObject {
                p.checkpoint?.takeIf { it.isNotBlank() }?.let { put("sd_model_checkpoint", it) }
                if (!forge) p.vae?.takeIf { it.isNotBlank() }?.let { put("sd_vae", it) }
                if (p.architecture.supportsClipSkip) put("CLIP_stop_at_last_layers", p.clipSkip.coerceIn(1, 12))
                if (forge) {
                    val modules = p.components[SLOT_FORGE_MODULES]?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    if (!modules.isNullOrEmpty()) put("forge_additional_modules", JsonArray(modules.map { JsonPrimitive(it) }))
                }
            },
        )
        put("override_settings_restore_afterwards", settings.restoreSettingsAfterwards)
        put("send_images", true)
        put("save_images", settings.saveImagesOnServer)
        put("do_not_save_samples", !settings.saveImagesOnServer)
        put("do_not_save_grid", true)
    }

    override suspend fun cancel(remoteJobId: String?) {
        runCatching { call { api.interrupt() } }
    }

    companion object {
        const val SLOT_FORGE_MODULES = "forge_modules"
    }
}

class A1111ProviderFactory @Inject constructor() : ProviderAdapterFactory {
    override fun create(config: ProviderConfig, http: ProviderHttp): ImageGenerationProvider = A1111Provider(config, http)
}
