package com.unfiltered.studio.data.providers.comfyui

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.HttpErrors
import com.unfiltered.studio.core.arr
import com.unfiltered.studio.core.arrOrNull
import com.unfiltered.studio.core.doubleValueOrNull
import com.unfiltered.studio.core.longValueOrNull
import com.unfiltered.studio.core.obj
import com.unfiltered.studio.core.objOrNull
import com.unfiltered.studio.core.str
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.data.network.Retry
import com.unfiltered.studio.data.network.toRequestBody
import com.unfiltered.studio.data.network.trimBaseUrl
import com.unfiltered.studio.data.providers.ProviderAdapterFactory
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BackendModel
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CancelSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.ComfyTemplate
import com.unfiltered.studio.domain.models.ComfyWorkflowMode
import com.unfiltered.studio.domain.models.ComponentSlot
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.GeneratedImageData
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.HiresSupport
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ProgressSupport
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.SizeConstraints
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject

class ComfyUiProvider(
    override val config: ProviderConfig,
    private val http: ProviderHttp,
    private val pollIntervalMs: Long = 1500,
) : ImageGenerationProvider {

    private val settings = config.settings as? ComfySettings ?: ComfySettings()
    private val base = config.baseUrl.trimBaseUrl()
    private val api = http.api

    private fun url(path: String) = base + path

    override suspend fun testConnection(): ConnectionInfo {
        val stats = api.getJson(url("/system_stats")).objOrNull
            ?: throw AppError.Parse(config.name, "system_stats is not an object")
        val system = stats.obj("system")
        val device = stats.arr("devices")?.firstOrNull()?.objOrNull
        val vramTotal = device?.get("vram_total").longValueOrNull
        val vramFree = device?.get("vram_free").longValueOrNull
        return ConnectionInfo(
            serverName = "ComfyUI",
            version = system?.str("comfyui_version"),
            details = listOfNotNull(
                device?.str("name")?.let { "GPU: $it" },
                if (vramTotal != null && vramFree != null) "VRAM free ${vramFree / 1_048_576} / ${vramTotal / 1_048_576} MB" else null,
                system?.str("pytorch_version")?.let { "PyTorch $it" },
            ),
        )
    }

    override suspend fun fetchCatalog(): BackendCatalog = coroutineScope {
        val warnings = mutableListOf<String>()
        suspend fun node(name: String): JsonObject? = try {
            api.getJson(url("/object_info/$name")).objOrNull?.obj(name)
        } catch (c: CancellationException) {
            throw c
        } catch (e: AppError) {
            if (e is AppError.Unauthorized || e is AppError.Forbidden) throw e
            null
        }
        val stats = async { runCatching { testConnection() }.getOrNull() }
        val ksampler = async { node("KSampler") }
        val ckpt = async { node("CheckpointLoaderSimple") }
        val lora = async { node("LoraLoader") }
        val vae = async { node("VAELoader") }
        val unet = async { node("UNETLoader") }
        val dualClip = async { node("DualCLIPLoader") }
        val upscale = async { node("LatentUpscaleBy") }
        val embeddings = async { runCatching { api.getJson(url("/embeddings")).arrOrNull?.mapNotNull { it.stringOrNull } }.getOrNull() }

        val ks = ksampler.await() ?: throw AppError.Parse(config.name, "object_info/KSampler missing — is this a ComfyUI server?")
        val checkpoints = ckpt.await()?.let { ComfyObjectInfo.comboOptions(it, "ckpt_name") }.orEmpty()
        val loraFiles = lora.await()?.let { ComfyObjectInfo.comboOptions(it, "lora_name") }
        if (loraFiles == null) warnings += "LoraLoader node is unavailable on this server."
        val unetInfo = unet.await()
        val clipInfo = dualClip.await()
        BackendCatalog(
            providerId = config.id,
            fetchedAt = System.currentTimeMillis(),
            serverLabel = stats.await()?.let { info -> listOfNotNull(info.serverName, info.version).joinToString(" ") },
            checkpoints = checkpoints.map { BackendModel(name = it) },
            loras = loraFiles.orEmpty().map { BackendModel(name = it) },
            vaes = vae.await()?.let { ComfyObjectInfo.comboOptions(it, "vae_name") }.orEmpty().map { BackendModel(name = it) },
            samplers = ComfyObjectInfo.comboOptions(ks, "sampler_name").orEmpty(),
            schedulers = ComfyObjectInfo.comboOptions(ks, "scheduler").orEmpty(),
            upscalers = upscale.await()?.let { ComfyObjectInfo.comboOptions(it, "upscale_method") }.orEmpty(),
            embeddings = embeddings.await().orEmpty(),
            componentFiles = buildMap {
                unetInfo?.let { info ->
                    put(ComfyWorkflowBuilder.SLOT_UNET, ComfyObjectInfo.comboOptions(info, "unet_name").orEmpty())
                    put(ComfyWorkflowBuilder.SLOT_WEIGHT_DTYPE, ComfyObjectInfo.comboOptions(info, "weight_dtype").orEmpty())
                }
                clipInfo?.let { info -> ComfyObjectInfo.comboOptions(info, "clip_name1")?.let { put(ComfyWorkflowBuilder.SLOT_CLIP1, it); put(ComfyWorkflowBuilder.SLOT_CLIP2, it) } }
            },
            flags = if (loraFiles != null) setOf(BackendCatalog.FLAG_LORA_LISTING) else emptySet(),
            warnings = warnings,
        )
    }

    override fun capabilities(catalog: BackendCatalog?, params: GenerationParameters): ProviderCapabilities {
        val samplers = catalog?.samplers?.ifEmpty { null } ?: DEFAULT_SAMPLERS
        val schedulers = catalog?.schedulers?.ifEmpty { null } ?: DEFAULT_SCHEDULERS
        val custom = settings.workflowMode == ComfyWorkflowMode.CUSTOM
        if (custom) return customCapabilities(catalog, samplers, schedulers)

        val template = ComfyWorkflowBuilder.resolveTemplate(settings.template, params.architecture)
        val arch = params.architecture
        val split = template == ComfyTemplate.FLUX_SPLIT
        val notes = buildList {
            if (!arch.builtInComfySupport) add("${arch.displayName} isn't covered by the built-in templates — use a custom workflow exported from ComfyUI for best results.")
            if (!arch.usesNegativePrompt) add("${arch.displayName} ignores the negative prompt at CFG 1.")
        }
        val files = catalog?.componentFiles.orEmpty()
        return ProviderCapabilities(
            negativePrompt = true,
            size = SizeConstraints(min = 64, max = 2048, multipleOf = if (template == ComfyTemplate.CHECKPOINT) 8 else 16),
            steps = 1..150,
            cfg = 1f..30f,
            distilledGuidance = template == ComfyTemplate.FLUX_CHECKPOINT || split,
            seed = true,
            batch = BatchSupport.Native(4),
            samplers = samplers,
            schedulers = schedulers,
            clipSkip = template == ComfyTemplate.CHECKPOINT && arch.supportsClipSkip,
            hiresFix = HiresSupport(catalog?.upscalers?.ifEmpty { null } ?: listOf("nearest-exact", "bilinear", "area", "bicubic", "bislerp")),
            img2img = true,
            checkpointSelection = if (split) CheckpointSelection.None
            else CheckpointSelection.FromList(catalog?.checkpoints?.map { it.name }.orEmpty(), allowEmpty = false),
            vaes = if (split) null else listOf("") + catalog?.vaes?.map { it.name }.orEmpty(),
            components = if (split) listOf(
                ComponentSlot(ComfyWorkflowBuilder.SLOT_UNET, "Diffusion model (UNET)", files[ComfyWorkflowBuilder.SLOT_UNET].orEmpty(), required = true),
                ComponentSlot(ComfyWorkflowBuilder.SLOT_WEIGHT_DTYPE, "Weight dtype", files[ComfyWorkflowBuilder.SLOT_WEIGHT_DTYPE].orEmpty().ifEmpty { listOf("default") }, required = false),
                ComponentSlot(ComfyWorkflowBuilder.SLOT_CLIP1, "Text encoder 1 (T5)", files[ComfyWorkflowBuilder.SLOT_CLIP1].orEmpty(), required = true),
                ComponentSlot(ComfyWorkflowBuilder.SLOT_CLIP2, "Text encoder 2 (CLIP-L)", files[ComfyWorkflowBuilder.SLOT_CLIP2].orEmpty(), required = true),
                ComponentSlot(ComfyWorkflowBuilder.SLOT_VAE_FILE, "VAE (ae.safetensors)", catalog?.vaes?.map { it.name }.orEmpty(), required = true),
            ) else emptyList(),
            loras = LoraSupport.ComfyNodes,
            loraClipStrength = true,
            cancellation = CancelSupport.SERVER,
            progress = ProgressSupport.STEPS,
            livePreview = settings.useWebSocket,
            notes = notes,
        )
    }

    private fun customCapabilities(catalog: BackendCatalog?, samplers: List<String>, schedulers: List<String>): ProviderCapabilities {
        val json = settings.customWorkflowJson
        val analysis = json?.let { runCatching { ComfyWorkflowBuilder.analyze(it) }.getOrNull() }
        if (analysis == null) {
            return ProviderCapabilities(
                notes = listOf(if (json.isNullOrBlank()) "Paste a workflow exported with Export (API) in the provider settings." else "The custom workflow couldn't be parsed — check it in the provider settings."),
            )
        }
        val files = catalog?.componentFiles.orEmpty()
        return ProviderCapabilities(
            negativePrompt = analysis.negativeText,
            size = if (analysis.size) SizeConstraints(64, 4096, 8) else null,
            steps = if (analysis.steps) 1..150 else null,
            cfg = if (analysis.cfg) 0f..30f else null,
            distilledGuidance = analysis.guidance,
            seed = analysis.seed,
            batch = if (analysis.batch) BatchSupport.Native(4) else BatchSupport.None,
            samplers = if (analysis.sampler) samplers else emptyList(),
            schedulers = if (analysis.scheduler) schedulers else emptyList(),
            clipSkip = analysis.clipInjection,
            hiresFix = null,
            img2img = analysis.img2img,
            checkpointSelection = if (analysis.checkpoint) CheckpointSelection.FromList(catalog?.checkpoints?.map { it.name }.orEmpty(), allowEmpty = true) else CheckpointSelection.None,
            vaes = if (analysis.vae) listOf("") + catalog?.vaes?.map { it.name }.orEmpty() else null,
            components = if (analysis.unet) listOf(
                ComponentSlot(ComfyWorkflowBuilder.SLOT_UNET, "Diffusion model (UNET)", files[ComfyWorkflowBuilder.SLOT_UNET].orEmpty(), required = false),
            ) else emptyList(),
            loras = if (analysis.loraInjection) LoraSupport.ComfyNodes else LoraSupport.None,
            loraClipStrength = analysis.clipInjection,
            cancellation = CancelSupport.SERVER,
            progress = ProgressSupport.STEPS,
            livePreview = settings.useWebSocket,
            notes = analysis.problems + listOfNotNull(
                settings.customWorkflowName?.let { "Custom workflow: $it" },
                analysis.placeholders.takeIf { it.isNotEmpty() }?.let { "Placeholders: ${it.joinToString { p -> "{{$p}}" }}" },
            ),
        )
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val p = request.params
        val initName = p.initImagePath?.let { uploadImage(File(it)) }
        val built = when (settings.workflowMode) {
            ComfyWorkflowMode.BUILT_IN -> ComfyWorkflowBuilder.buildBuiltIn(
                ComfyWorkflowBuilder.resolveTemplate(settings.template, p.architecture), p, initName, settings.filenamePrefix,
            )
            ComfyWorkflowMode.CUSTOM -> ComfyWorkflowBuilder.applyCustom(
                settings.customWorkflowJson ?: throw AppError.Configuration("No custom workflow configured for ${config.name}."),
                p, initName,
            )
        }
        built.warnings.forEach { send(GenerationEvent.Warning(it)) }

        val promptId = UUID.randomUUID().toString()
        val clientId = UUID.randomUUID().toString().replace("-", "")
        // Persist the id before submitting so the job can be recovered if the app dies mid-request.
        send(GenerationEvent.Submitted(promptId))

        val socket = if (settings.useWebSocket) openSocket(clientId) else null
        try {
            submit(built.prompt, promptId, clientId)
            awaitCompletion(promptId, socket)
            emitOutputs(promptId, p.seed)
        } finally {
            socket?.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun recover(request: GenerationRequest, remoteJobId: String): Flow<GenerationEvent> = channelFlow {
        send(GenerationEvent.Status("Reconnecting to ${config.name}…"))
        awaitCompletion(remoteJobId, socket = null)
        emitOutputs(remoteJobId, request.params.seed)
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(remoteJobId: String?) {
        if (remoteJobId == null) return
        runCatching {
            api.send(Request.Builder().url(url("/queue")).post(buildJsonObject { put("delete", JsonArray(listOf(JsonPrimitive(remoteJobId)))) }.toRequestBody()).build())
        }
        runCatching {
            api.send(Request.Builder().url(url("/interrupt")).post(buildJsonObject { put("prompt_id", remoteJobId) }.toRequestBody()).build())
        }
    }

    override suspend fun loraMetadata(backendName: String): Map<String, String>? {
        val target = url("/view_metadata/loras").toHttpUrl().newBuilder().addQueryParameter("filename", backendName).build()
        return try {
            api.getJson(target.toString()).objOrNull?.mapValues { (_, v) -> v.stringOrNull ?: v.toString() }
        } catch (c: CancellationException) {
            throw c
        } catch (e: AppError.NotFound) {
            null
        }
    }

    // -----------------------------------------------------------------------------------------

    private suspend fun uploadImage(file: File): String {
        if (!file.exists()) throw AppError.Storage("The init image is missing; pick it again.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "unfiltered_${file.name}", file.asRequestBody("image/png".toMediaType()))
            .addFormDataPart("overwrite", "true")
            .addFormDataPart("type", "input")
            .build()
        val response = api.json(Request.Builder().url(url("/upload/image")).post(body).build()).objOrNull
            ?: throw AppError.Parse(config.name, "upload/image returned no JSON")
        val name = response.str("name") ?: throw AppError.Parse(config.name, "upload/image returned no file name")
        val subfolder = response.str("subfolder").orEmpty()
        return if (subfolder.isBlank()) name else "$subfolder/$name"
    }

    private suspend fun submit(prompt: JsonObject, promptId: String, clientId: String) {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("client_id", clientId)
            put("prompt_id", promptId)
        }
        val request = Request.Builder().url(url("/prompt")).post(body.toRequestBody()).build()
        val response: Response = Retry.withBackoff(http.maxRetries, retryIf = Retry.beforeServerReached) { api.execute(request) }
        val text = response.use { r -> r.body.string() }
        if (!response.isSuccessful) {
            val json = runCatching { AppJson.parseToJsonElement(text).objOrNull }.getOrNull()
            if (response.code == 400 && json != null) throw ComfyErrors.validation(json)
            throw HttpErrors.toAppError(response.code, text, config.name, response.header("Retry-After"), "ComfyUI /prompt")
        }
        val json = runCatching { AppJson.parseToJsonElement(text).objOrNull }.getOrNull()
        val nodeErrors = json?.obj("node_errors")
        if (nodeErrors != null && nodeErrors.isNotEmpty()) throw ComfyErrors.validation(json)
    }

    private suspend fun ProducerScope<GenerationEvent>.awaitCompletion(promptId: String, socket: ComfySocket?) {
        var started = false
        var socketAlive = socket != null
        var lastPoll = 0L
        var currentNodeClass: String? = null
        while (true) {
            val event = if (socketAlive && socket != null) {
                withTimeoutOrNull(pollIntervalMs * 3) { socket.events.receiveCatching().getOrNull() }
            } else {
                delay(pollIntervalMs)
                null
            }
            when (event) {
                null -> Unit
                is ComfyWsEvent.Closed -> {
                    socketAlive = false
                    send(GenerationEvent.Status("Live connection lost — polling for the result…"))
                }
                is ComfyWsEvent.Queue -> if (!started) send(GenerationEvent.Queued(null))
                is ComfyWsEvent.ExecutionStart -> if (event.promptId == promptId) {
                    started = true
                    send(GenerationEvent.Status("Running on ${config.name}"))
                }
                is ComfyWsEvent.Executing -> if (event.promptId == null || event.promptId == promptId) {
                    if (event.node == null) {
                        if (event.promptId == promptId) return
                    } else {
                        started = true
                        currentNodeClass = event.displayClass
                        send(GenerationEvent.Progress(fraction = null, message = event.displayClass?.let { "Running $it" }))
                    }
                }
                is ComfyWsEvent.Progress -> if (event.promptId == null || event.promptId == promptId) {
                    started = true
                    send(
                        GenerationEvent.Progress(
                            fraction = if (event.max > 0) event.value.toFloat() / event.max else null,
                            step = event.value,
                            totalSteps = event.max,
                            message = currentNodeClass,
                        ),
                    )
                }
                is ComfyWsEvent.Success -> if (event.promptId == promptId) return
                is ComfyWsEvent.Error -> if (event.promptId == promptId) {
                    throw AppError.GenerationFailed("ComfyUI: ${event.nodeType ?: "node"} failed — ${event.message}", event.traceback)
                }
                is ComfyWsEvent.Interrupted -> if (event.promptId == promptId) {
                    throw AppError.GenerationFailed("The job was interrupted on the ComfyUI server.")
                }
                is ComfyWsEvent.Preview -> if (event.promptId == null || event.promptId == promptId) {
                    send(GenerationEvent.Preview(event.bytes))
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastPoll >= if (socketAlive) pollIntervalMs * 4 else pollIntervalMs) {
                lastPoll = now
                when (val state = historyState(promptId)) {
                    HistoryState.Success -> return
                    is HistoryState.Failed -> throw AppError.GenerationFailed("ComfyUI: ${state.message}", state.details)
                    HistoryState.Missing -> {
                        val position = queuePosition(promptId)
                        when {
                            position == null -> if (!started) throw AppError.GenerationFailed("ComfyUI no longer knows this job (the server may have restarted).")
                            position == 0 -> if (!socketAlive) send(GenerationEvent.Progress(fraction = null, message = "Running on ${config.name}"))
                            else -> send(GenerationEvent.Queued(position))
                        }
                        if (position == 0) started = true
                    }
                }
            }
        }
    }

    private sealed interface HistoryState {
        data object Missing : HistoryState
        data object Success : HistoryState
        data class Failed(val message: String, val details: String?) : HistoryState
    }

    private suspend fun historyState(promptId: String): HistoryState {
        val entry = fetchHistory(promptId) ?: return HistoryState.Missing
        val status = entry.obj("status")
        val statusStr = status?.str("status_str")
        val completed = status?.get("completed")?.stringOrNull == "true"
        if (statusStr == "error") {
            val error = status.arr("messages")?.mapNotNull { it.arrOrNull }
                ?.firstOrNull { it.getOrNull(0)?.stringOrNull == "execution_error" }
                ?.getOrNull(1)?.objOrNull
            return HistoryState.Failed(
                message = listOfNotNull(error?.str("node_type"), error?.str("exception_message")).joinToString(": ").ifBlank { "execution failed" },
                details = error?.arr("traceback")?.mapNotNull { it.stringOrNull }?.joinToString(""),
            )
        }
        return if (statusStr == "success" || completed || (status == null && entry.obj("outputs")?.isNotEmpty() == true)) HistoryState.Success else HistoryState.Missing
    }

    private suspend fun fetchHistory(promptId: String): JsonObject? = try {
        api.getJson(url("/history/$promptId")).objOrNull?.obj(promptId)
    } catch (c: CancellationException) {
        throw c
    } catch (e: AppError) {
        if (e is AppError.Unauthorized) throw e
        null
    }

    /** 0 = running, n = position in pending queue, null = not queued. */
    private suspend fun queuePosition(promptId: String): Int? {
        val queue = try {
            api.getJson(url("/queue")).objOrNull
        } catch (c: CancellationException) {
            throw c
        } catch (e: AppError) {
            return if (e.isTransient) 0 else null
        } ?: return null
        fun ids(key: String) = queue.arr(key)?.mapNotNull { it.arrOrNull?.getOrNull(1)?.stringOrNull }.orEmpty()
        if (promptId in ids("queue_running")) return 0
        val pending = queue.arr("queue_pending")?.mapNotNull { item ->
            val array = item.arrOrNull ?: return@mapNotNull null
            (array.getOrNull(0)?.doubleValueOrNull ?: 0.0) to array.getOrNull(1)?.stringOrNull
        }?.sortedBy { it.first }?.map { it.second }.orEmpty()
        val index = pending.indexOf(promptId)
        return if (index >= 0) index + 1 else null
    }

    private suspend fun ProducerScope<GenerationEvent>.emitOutputs(promptId: String, seed: Long) {
        val entry = fetchHistory(promptId) ?: throw AppError.GenerationFailed("ComfyUI finished but the job's history is missing.")
        val refs = ComfyOutputs.imageRefs(entry)
        if (refs.isEmpty()) throw AppError.GenerationFailed("The workflow finished without saving images (add a SaveImage node).")
        send(GenerationEvent.Status("Downloading ${refs.size} image(s)…"))
        for (ref in refs) {
            val target = url("/view").toHttpUrl().newBuilder()
                .addQueryParameter("filename", ref.filename)
                .addQueryParameter("subfolder", ref.subfolder)
                .addQueryParameter("type", ref.type)
                .build()
            val (bytes, type) = Retry.withBackoff(http.maxRetries) { api.bytes(Request.Builder().url(target).get().build()) }
            send(GenerationEvent.Image(GeneratedImageData(bytes, type?.substringBefore(';') ?: "image/png", seed)))
        }
        send(GenerationEvent.Completed())
    }

    private fun openSocket(clientId: String): ComfySocket {
        val channel = Channel<ComfyWsEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url("$base/ws?clientId=$clientId").build()
        val webSocket = http.streaming.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"feature_flags","data":{"supports_preview_metadata":true}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    ComfyWsEvent.parseText(text)?.let { channel.trySend(it) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    ComfyWsEvent.parseBinary(bytes.toByteArray())?.let { channel.trySend(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.trySend(ComfyWsEvent.Closed(t.message))
                    channel.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    channel.trySend(ComfyWsEvent.Closed(reason))
                    channel.close()
                }
            },
        )
        return ComfySocket(channel, webSocket)
    }

    companion object {
        val DEFAULT_SAMPLERS = listOf("euler", "euler_ancestral", "dpmpp_2m", "dpmpp_2m_sde", "dpmpp_sde", "uni_pc", "ddim", "lcm")
        val DEFAULT_SCHEDULERS = listOf("normal", "karras", "exponential", "sgm_uniform", "simple", "ddim_uniform", "beta")
    }
}

class ComfySocket(val events: Channel<ComfyWsEvent>, private val webSocket: WebSocket) {
    fun close() {
        webSocket.close(1000, null)
        events.close()
    }
}

sealed interface ComfyWsEvent {
    data class Queue(val remaining: Int?) : ComfyWsEvent
    data class ExecutionStart(val promptId: String?) : ComfyWsEvent
    data class Executing(val node: String?, val displayClass: String?, val promptId: String?) : ComfyWsEvent
    data class Progress(val value: Int, val max: Int, val promptId: String?) : ComfyWsEvent
    data class Success(val promptId: String?) : ComfyWsEvent
    data class Error(val promptId: String?, val nodeType: String?, val message: String, val traceback: String?) : ComfyWsEvent
    data class Interrupted(val promptId: String?) : ComfyWsEvent
    class Preview(val bytes: ByteArray, val promptId: String?) : ComfyWsEvent
    data class Closed(val reason: String?) : ComfyWsEvent

    companion object {
        fun parseText(text: String): ComfyWsEvent? {
            val root = runCatching { AppJson.parseToJsonElement(text).objOrNull }.getOrNull() ?: return null
            val data = root.obj("data") ?: JsonObject(emptyMap())
            val promptId = data.str("prompt_id")
            return when (root.str("type")) {
                "status" -> Queue(data.obj("status")?.obj("exec_info")?.get("queue_remaining").longValueOrNull?.toInt())
                "execution_start" -> ExecutionStart(promptId)
                "executing" -> Executing(data["node"].stringOrNull, data.str("display_node") ?: data["node"].stringOrNull, promptId)
                "progress" -> Progress(
                    value = data["value"].longValueOrNull?.toInt() ?: 0,
                    max = data["max"].longValueOrNull?.toInt() ?: 0,
                    promptId = promptId,
                )
                "execution_success" -> Success(promptId)
                "execution_error" -> Error(
                    promptId = promptId,
                    nodeType = data.str("node_type"),
                    message = data.str("exception_message")?.trim() ?: "execution error",
                    traceback = data.arr("traceback")?.mapNotNull { it.stringOrNull }?.joinToString(""),
                )
                "execution_interrupted" -> Interrupted(promptId)
                else -> null
            }
        }

        /** Binary frames: [u32 event][payload]. 1 = [u32 image type][bytes]; 4 = [u32 meta length][meta JSON][bytes]. */
        fun parseBinary(bytes: ByteArray): ComfyWsEvent? {
            if (bytes.size < 8) return null
            val buffer = ByteBuffer.wrap(bytes)
            return when (buffer.int) {
                1 -> Preview(bytes.copyOfRange(8, bytes.size), null)
                4 -> {
                    val metaLength = buffer.int
                    if (metaLength < 0 || 8 + metaLength > bytes.size) return null
                    val meta = runCatching { AppJson.parseToJsonElement(String(bytes, 8, metaLength, Charsets.UTF_8)).objOrNull }.getOrNull()
                    Preview(bytes.copyOfRange(8 + metaLength, bytes.size), meta?.str("prompt_id"))
                }
                else -> null
            }
        }
    }
}

object ComfyObjectInfo {
    /** Combo options for [input]: legacy `[[options], {…}]` or V3 `["COMBO", {"options": […]}]`. */
    fun comboOptions(nodeInfo: JsonObject, input: String): List<String>? {
        val inputs = nodeInfo.obj("input") ?: return null
        val spec = (inputs.obj("required")?.get(input) ?: inputs.obj("optional")?.get(input))?.arrOrNull ?: return null
        val first = spec.firstOrNull()
        return when {
            first is JsonArray -> first.mapNotNull { it.stringOrNull }
            first.stringOrNull == "COMBO" -> spec.getOrNull(1)?.objOrNull?.arr("options")?.mapNotNull { it.stringOrNull }
            else -> null
        }
    }
}

object ComfyOutputs {
    data class ImageRef(val filename: String, val subfolder: String, val type: String, val node: String)

    /** Image references from a /history entry; final "output" images first, previews only as fallback. */
    fun imageRefs(entry: JsonObject): List<ImageRef> {
        val outputs = entry.obj("outputs") ?: return emptyList()
        val all = outputs.entries
            .sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
            .flatMap { (node, value) ->
                value.objOrNull?.arr("images")?.mapNotNull { image ->
                    val o = image.objOrNull ?: return@mapNotNull null
                    val name = o.str("filename") ?: return@mapNotNull null
                    ImageRef(name, o.str("subfolder").orEmpty(), o.str("type") ?: "output", node)
                }.orEmpty()
            }
        val saved = all.filter { it.type == "output" }
        return saved.ifEmpty { all }
    }
}

object ComfyErrors {
    /** Converts `{error, node_errors}` from POST /prompt into a readable validation error. */
    fun validation(json: JsonObject): AppError.Validation {
        val error = json.obj("error")
        val summary = error?.str("message") ?: "ComfyUI rejected the workflow"
        val issues = mutableListOf<String>()
        error?.str("details")?.takeIf { it.isNotBlank() }?.let { issues += it }
        json.obj("node_errors")?.forEach { (nodeId, value) ->
            val node = value.objOrNull ?: return@forEach
            val classType = node.str("class_type") ?: "node"
            node.arr("errors")?.forEach { e ->
                val o = e.objOrNull ?: return@forEach
                val input = o.obj("extra_info")?.str("input_name")
                val details = o.str("details")?.ifBlank { null } ?: o.str("message")
                issues += "Node $nodeId ($classType)${input?.let { " · $it" } ?: ""}: $details"
            }
        }
        return AppError.Validation("ComfyUI: $summary", issues)
    }
}

class ComfyUiProviderFactory @Inject constructor() : ProviderAdapterFactory {
    override fun create(config: ProviderConfig, http: ProviderHttp): ImageGenerationProvider = ComfyUiProvider(config, http)
}
