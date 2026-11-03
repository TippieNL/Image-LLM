package com.unfiltered.studio.domain.models

import kotlinx.serialization.Serializable

/**
 * What the active provider + model combination can actually do. The generation screen renders a
 * control only when its capability is present, so unsupported parameters are never exposed.
 */
data class ProviderCapabilities(
    val negativePrompt: Boolean = false,
    val size: SizeConstraints? = null,
    val steps: IntRange? = null,
    val cfg: ClosedFloatingPointRange<Float>? = null,
    val distilledGuidance: Boolean = false,
    val seed: Boolean = false,
    val batch: BatchSupport = BatchSupport.None,
    val samplers: List<String> = emptyList(),
    val schedulers: List<String> = emptyList(),
    /** Scheduler can be typed freely (e.g. diffusers scheduler class names). */
    val freeformScheduler: Boolean = false,
    val clipSkip: Boolean = false,
    val hiresFix: HiresSupport? = null,
    val img2img: Boolean = false,
    val checkpointSelection: CheckpointSelection = CheckpointSelection.None,
    val vaes: List<String>? = null,
    val components: List<ComponentSlot> = emptyList(),
    val loras: LoraSupport = LoraSupport.None,
    val loraClipStrength: Boolean = false,
    val cancellation: CancelSupport = CancelSupport.CLIENT_ONLY,
    val progress: ProgressSupport = ProgressSupport.NONE,
    val livePreview: Boolean = false,
    /** Caveats shown under the settings, e.g. "Negative prompt ignored at CFG 1". */
    val notes: List<String> = emptyList(),
)

data class SizeConstraints(val min: Int = 64, val max: Int = 2048, val multipleOf: Int = 8) {
    fun clamp(value: Int): Int {
        val rounded = (value / multipleOf) * multipleOf
        return rounded.coerceIn(min, max)
    }
}

sealed interface BatchSupport {
    val max: Int
    data object None : BatchSupport { override val max = 1 }
    /** The backend generates the batch in one request. */
    data class Native(override val max: Int) : BatchSupport
    /** The app issues one request per image (seed + i). */
    data class Sequential(override val max: Int) : BatchSupport
}

data class HiresSupport(val upscalers: List<String>, val maxScale: Float = 4f)

sealed interface CheckpointSelection {
    data object None : CheckpointSelection
    /** Pick from the backend's list. */
    data class FromList(val options: List<String>, val allowEmpty: Boolean) : CheckpointSelection
    /** Free-text model id (Hugging Face repo id, OpenAI model). */
    data class FreeText(val hint: String, val suggestions: List<String> = emptyList()) : CheckpointSelection
}

/** An additional model component (ComfyUI UNET/CLIP/VAE loaders, Forge modules). */
data class ComponentSlot(
    val id: String,
    val label: String,
    val options: List<String>,
    val required: Boolean,
    val multiple: Boolean = false,
)

sealed interface LoraSupport {
    data object None : LoraSupport
    /** `<lora:name:weight>` in the prompt (A1111/Forge). LoRA file must be on the server. */
    data object PromptSyntax : LoraSupport
    /** LoraLoader nodes are inserted into the ComfyUI graph. LoRA file must be on the server. */
    data object ComfyNodes : LoraSupport
    /** Cloud provider loads LoRAs from URLs (fal.ai, WaveSpeed: many; Replicate: one). */
    data class RemoteWeights(val maxCount: Int, val perLoraStrength: Boolean) : LoraSupport
    /** Template variable on custom REST providers. */
    data object TemplateVariable : LoraSupport

    val isSupported: Boolean get() = this != None
}

enum class CancelSupport(val description: String) {
    SERVER("Cancel stops the job on the server."),
    CLIENT_ONLY("Cancel stops waiting; the server may still finish the job."),
}

enum class ProgressSupport { STEPS, STATUS, NONE }

/** Data discovered from a backend (lists of models, samplers, …). */
@Serializable
data class BackendCatalog(
    val providerId: String,
    val fetchedAt: Long,
    val serverLabel: String? = null,
    val checkpoints: List<BackendModel> = emptyList(),
    val loras: List<BackendModel> = emptyList(),
    val vaes: List<BackendModel> = emptyList(),
    val samplers: List<String> = emptyList(),
    val schedulers: List<String> = emptyList(),
    val upscalers: List<String> = emptyList(),
    val embeddings: List<String> = emptyList(),
    /** Component file lists keyed by slot id (unet, clip, forge_modules, …). */
    val componentFiles: Map<String, List<String>> = emptyMap(),
    val flags: Set<String> = emptySet(),
    val hfModel: HfModelInfo? = null,
    val models: List<String> = emptyList(),
    /** Non-fatal problems while building the catalog (e.g. an endpoint missing on old servers). */
    val warnings: List<String> = emptyList(),
) {
    fun has(flag: String) = flag in flags

    companion object {
        const val FLAG_FORGE = "forge"
        const val FLAG_SCHEDULERS = "schedulers"
        const val FLAG_LORA_LISTING = "lora_listing"
        const val FLAG_VAE_LISTING = "vae_listing"
    }
}

@Serializable
data class BackendModel(
    /** Identifier sent back to the backend (A1111 title/name, ComfyUI relative filename). */
    val name: String,
    val title: String = name,
    val path: String? = null,
    val hash: String? = null,
    val architecture: ModelArchitecture? = ModelArchitecture.detect(name),
    /** Safetensors metadata reported by the backend (A1111 /sdapi/v1/loras). */
    val metadata: Map<String, String> = emptyMap(),
) {
    val displayName: String get() = title.substringAfterLast('/').substringAfterLast('\\')
}

/** Hugging Face model info relevant to inference routing. */
@Serializable
data class HfModelInfo(
    val modelId: String,
    val pipelineTag: String?,
    val gated: Boolean,
    val mappings: List<HfProviderMapping>,
    val baseModel: String? = null,
    val isLora: Boolean = false,
)

@Serializable
data class HfProviderMapping(
    val provider: String,
    val providerId: String,
    val status: String,
    val task: String,
    val adapter: String? = null,
    val adapterWeightsPath: String? = null,
) {
    val isLive: Boolean get() = status == "live"
}
