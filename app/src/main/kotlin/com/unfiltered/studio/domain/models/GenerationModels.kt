package com.unfiltered.studio.domain.models

import kotlinx.serialization.Serializable

/**
 * Everything needed to reproduce a generation. Persisted as JSON with each history record, so
 * fields must stay backwards compatible (add with defaults, never rename).
 */
@Serializable
data class GenerationParameters(
    val prompt: String = "",
    val negativePrompt: String = "",
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 25,
    val cfgScale: Float = 7f,
    val distilledGuidance: Float = 3.5f,
    val sampler: String? = null,
    val scheduler: String? = null,
    /** -1 = random (resolved to a concrete seed when the job is queued). */
    val seed: Long = -1L,
    val batchSize: Int = 1,
    /** A1111 semantics: 1 = use the last CLIP layer (no skip), 2 = skip one layer, … */
    val clipSkip: Int = 1,
    val hiresFix: HiresFixParams = HiresFixParams(),
    /** Image-to-image strength (only used with [initImagePath]). */
    val denoisingStrength: Float = 0.65f,
    /** App-private copy of the init image for img2img. */
    val initImagePath: String? = null,
    /** Checkpoint name on the backend, HF model id, or OpenAI model id. */
    val checkpoint: String? = null,
    val architecture: ModelArchitecture = ModelArchitecture.SDXL,
    val vae: String? = null,
    /** Extra model components by slot id (ComfyUI split loaders, Forge modules). */
    val components: Map<String, String> = emptyMap(),
    val loras: List<LoraSelection> = emptyList(),
    /** Append trigger words of enabled LoRAs that aren't already in the prompt. */
    val autoTriggerWords: Boolean = true,
)

@Serializable
data class HiresFixParams(
    val enabled: Boolean = false,
    val upscaler: String? = null,
    val scale: Float = 1.5f,
    /** 0 = same number of steps as the first pass. */
    val steps: Int = 0,
    val denoise: Float = 0.45f,
)

/**
 * A LoRA as applied to one generation. [backendName] is how the *target backend* identifies the
 * file (A1111 network name, ComfyUI relative filename); remote fields are used by cloud providers
 * that load LoRAs from a URL.
 */
@Serializable
data class LoraSelection(
    val loraId: String? = null,
    val name: String,
    val backendName: String? = null,
    val strength: Float = 0.8f,
    /** ComfyUI only: separate CLIP strength. Null = same as [strength]. */
    val clipStrength: Float? = null,
    val enabled: Boolean = true,
    val triggerWords: List<String> = emptyList(),
    val architecture: ModelArchitecture? = null,
    val hfRepoId: String? = null,
    val hfWeightFile: String? = null,
    val remoteUrl: String? = null,
)

/** Aspect ratio presets; dimensions are computed around the architecture's native resolution. */
enum class AspectRatioPreset(val label: String, val w: Int, val h: Int) {
    SQUARE("1:1", 1, 1),
    LANDSCAPE_3_2("3:2", 3, 2),
    PORTRAIT_2_3("2:3", 2, 3),
    LANDSCAPE_4_3("4:3", 4, 3),
    PORTRAIT_3_4("3:4", 3, 4),
    WIDE_16_9("16:9", 16, 9),
    TALL_9_16("9:16", 9, 16),
    CUSTOM("Custom", 0, 0),
    ;

    /** Keeps the pixel count close to native² and rounds to [multiple]. */
    fun dimensions(native: Int, multiple: Int = 64, min: Int = 512, max: Int = 2048): Pair<Int, Int> {
        if (this == CUSTOM) return native to native
        val area = native.toDouble() * native
        val ratio = w.toDouble() / h
        val height = kotlin.math.sqrt(area / ratio)
        val width = height * ratio
        fun round(v: Double) = ((v / multiple).let { kotlin.math.round(it) } * multiple).toInt().coerceIn(min, max)
        return round(width) to round(height)
    }

    companion object {
        fun matching(width: Int, height: Int): AspectRatioPreset =
            entries.firstOrNull { it != CUSTOM && it.w * height == it.h * width } ?: CUSTOM
    }
}

data class GenerationRequest(
    val generationId: String,
    val providerId: String,
    val params: GenerationParameters,
)

/** Raw image returned by a provider. */
class GeneratedImageData(
    val bytes: ByteArray,
    val mimeType: String,
    val seed: Long?,
    val infotext: String? = null,
)

/** Events emitted by [com.unfiltered.studio.domain.providers.ImageGenerationProvider.generate]. */
sealed interface GenerationEvent {
    /** The backend accepted the job. [remoteJobId] is persisted so the job can be recovered/cancelled. */
    data class Submitted(val remoteJobId: String?) : GenerationEvent
    data class Queued(val position: Int?) : GenerationEvent
    data class Status(val message: String) : GenerationEvent
    /** Non-fatal issue recorded with the generation (e.g. a workflow input that couldn't be mapped). */
    data class Warning(val message: String) : GenerationEvent
    data class Progress(
        val fraction: Float?,
        val step: Int? = null,
        val totalSteps: Int? = null,
        val etaSeconds: Float? = null,
        val message: String? = null,
    ) : GenerationEvent
    class Preview(val bytes: ByteArray) : GenerationEvent
    class Image(val image: GeneratedImageData) : GenerationEvent
    data class Completed(val info: String? = null) : GenerationEvent
}

enum class GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** Live progress for a running job (in-memory only). */
data class LiveProgress(
    val generationId: String,
    val phase: String = "Starting…",
    val fraction: Float? = null,
    val step: Int? = null,
    val totalSteps: Int? = null,
    val etaSeconds: Float? = null,
    val queuePosition: Int? = null,
    val preview: ByteArray? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val imagesReceived: Int = 0,
)

data class PreparedGeneration(
    val params: GenerationParameters,
    val warnings: List<String>,
    val errors: List<String>,
) {
    val canRun: Boolean get() = errors.isEmpty()
}
