package com.unfiltered.studio.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType(val displayName: String, val tagline: String) {
    A1111("Stable Diffusion WebUI / Forge", "AUTOMATIC1111, Forge or reForge with --api"),
    COMFYUI("ComfyUI", "Your own GPU server via the workflow API"),
    HUGGING_FACE("Hugging Face", "Inference Providers, Inference Endpoints or a compatible URL"),
    OPENAI_COMPATIBLE("OpenAI-compatible Images API", "Any server exposing /v1/images/generations"),
    CUSTOM_REST("Custom REST API", "Configurable request template and response mapping"),
}

/**
 * Non-secret provider configuration. Secrets (tokens, passwords, secret header values) are kept
 * exclusively in the Keystore-backed credential store and injected by the HTTP layer.
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val auth: AuthConfig,
    val settings: ProviderSettings,
    val createdAt: Long,
    val sortOrder: Int = 0,
) {
    val host: String get() = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl
}

@Serializable
enum class AuthType(val displayName: String) {
    NONE("None"),
    BEARER("Bearer token"),
    BASIC("Username + password"),
    API_KEY_HEADER("API key header"),
}

@Serializable
data class AuthConfig(
    val type: AuthType = AuthType.NONE,
    /** Username for BASIC auth (not secret). */
    val username: String? = null,
    /** Header name for API_KEY_HEADER (e.g. "X-API-Key" or "Authorization"). */
    val headerName: String? = null,
    /** Optional prefix for API_KEY_HEADER values (e.g. "Key " for fal.ai). */
    val headerValuePrefix: String? = null,
    /** Hugging Face providers can reuse the account token from Settings → Accounts. */
    val useAccountToken: Boolean = false,
    val extraHeaders: List<HeaderEntry> = emptyList(),
)

/** A custom header. When [secret] is true the value lives in the credential store and [value] is blank. */
@Serializable
data class HeaderEntry(val name: String, val value: String = "", val secret: Boolean = false)

/** Per-provider settings, persisted as JSON with a type discriminator. */
@Serializable
sealed interface ProviderSettings

@Serializable
enum class A1111Variant(val displayName: String) {
    AUTO("Auto-detect"),
    AUTOMATIC1111("AUTOMATIC1111"),
    FORGE("Forge / reForge"),
}

@Serializable
@SerialName("a1111")
data class A1111Settings(
    val variant: A1111Variant = A1111Variant.AUTO,
    val defaultCheckpoint: String? = null,
    /** Restore the server's own checkpoint/VAE settings after each request. */
    val restoreSettingsAfterwards: Boolean = true,
    val livePreview: Boolean = true,
    val saveImagesOnServer: Boolean = false,
) : ProviderSettings

@Serializable
enum class ComfyWorkflowMode { BUILT_IN, CUSTOM }

@Serializable
enum class ComfyTemplate(val displayName: String, val description: String) {
    AUTO("Automatic", "Pick a template from the selected architecture"),
    CHECKPOINT("Checkpoint (SD1.x/2.x/SDXL/Pony/Illustrious)", "CheckpointLoaderSimple → KSampler"),
    SD3_CHECKPOINT("SD3 / SD3.5 checkpoint", "Checkpoint with SD3 latent"),
    FLUX_CHECKPOINT("FLUX.1 all-in-one checkpoint", "fp8 checkpoint with FluxGuidance"),
    FLUX_SPLIT("FLUX.1 split files", "UNETLoader + DualCLIPLoader + VAELoader"),
}

@Serializable
@SerialName("comfyui")
data class ComfySettings(
    val workflowMode: ComfyWorkflowMode = ComfyWorkflowMode.BUILT_IN,
    val template: ComfyTemplate = ComfyTemplate.AUTO,
    /** Workflow exported with ComfyUI "Export (API)". */
    val customWorkflowJson: String? = null,
    val customWorkflowName: String? = null,
    val defaultCheckpoint: String? = null,
    val useWebSocket: Boolean = true,
    val filenamePrefix: String = "UnfilteredAI",
) : ProviderSettings

@Serializable
enum class HfMode(val displayName: String, val description: String) {
    INFERENCE_PROVIDERS("Inference Providers (serverless)", "router.huggingface.co with your HF token"),
    DEDICATED_ENDPOINT("Dedicated Inference Endpoint", "Your *.endpoints.huggingface.cloud URL"),
    CUSTOM_URL("Custom compatible URL", "Any endpoint accepting {inputs, parameters}"),
}

@Serializable
@SerialName("huggingface")
data class HuggingFaceSettings(
    val mode: HfMode = HfMode.INFERENCE_PROVIDERS,
    /** "auto" or an explicit router provider id (hf-inference, fal-ai, replicate, together, nscale, wavespeed). */
    val routingProvider: String = "auto",
    val defaultModelId: String? = null,
    /** For dedicated endpoints: namespace + name enable status checks and resume. */
    val endpointNamespace: String? = null,
    val endpointName: String? = null,
    /**
     * Some third-party providers (fal.ai, Replicate) run their own output safety checker by
     * default and expose a documented request flag to turn it off. Off = provider default.
     */
    val disableProviderSafetyChecker: Boolean = false,
) : ProviderSettings

@Serializable
enum class OpenAiResponseFormat { B64_JSON, URL }

@Serializable
@SerialName("openai")
data class OpenAiImagesSettings(
    val defaultModel: String? = null,
    /** Send negative_prompt / steps / seed / guidance_scale (Together, many local servers accept these). */
    val sendExtendedParams: Boolean = false,
    val responseFormat: OpenAiResponseFormat = OpenAiResponseFormat.B64_JSON,
    val supportsBatch: Boolean = true,
    /** JSON object merged into every request body (provider specific options). */
    val extraJson: String? = null,
) : ProviderSettings

@Serializable
enum class CustomResponseMode(val displayName: String) {
    JSON("JSON (base64, data URI or URLs)"),
    IMAGE_BYTES("Raw image bytes"),
}

@Serializable
enum class CustomLoraMode(val displayName: String) {
    NONE("Not supported"),
    PROMPT_SYNTAX("Prompt syntax <lora:name:weight>"),
    TEMPLATE_VARIABLE("Template variable {{loras}}"),
}

@Serializable
data class CustomAsyncConfig(
    val jobIdPath: String = "id",
    /** JSON path of a poll URL in the submit response (e.g. Replicate `urls.get`, fal `status_url`). */
    val statusUrlPath: String? = null,
    /** Used when no status URL is returned, e.g. `{{base_url}}/status/{{job_id}}`. */
    val statusUrlTemplate: String? = null,
    val statusPath: String = "status",
    val successValues: List<String> = listOf("succeeded", "completed", "COMPLETED", "success", "SUCCESS"),
    val failureValues: List<String> = listOf("failed", "error", "canceled", "cancelled", "FAILED", "CANCELLED", "TIMED_OUT"),
    /** When results live at a separate URL (fal `response_url`). */
    val resultUrlPath: String? = null,
    val errorPath: String? = "error",
    val cancelUrlPath: String? = null,
    val cancelUrlTemplate: String? = null,
    val cancelMethod: String = "POST",
    val pollIntervalMs: Long = 1500,
)

@Serializable
@SerialName("custom")
data class CustomRestSettings(
    val method: String = "POST",
    val path: String = "",
    val bodyTemplate: String = DEFAULT_TEMPLATE,
    val responseMode: CustomResponseMode = CustomResponseMode.JSON,
    /** JSON path to image values in the final payload. */
    val imagePath: String = "images[*]",
    val async: CustomAsyncConfig? = null,
    val loraMode: CustomLoraMode = CustomLoraMode.NONE,
    // Declared capabilities: only these parameters are shown in the UI.
    val supportsNegativePrompt: Boolean = true,
    val supportsSize: Boolean = true,
    val supportsSteps: Boolean = true,
    val supportsCfg: Boolean = true,
    val supportsSeed: Boolean = true,
    val supportsBatch: Boolean = false,
    val supportsSampler: Boolean = false,
    val supportsModel: Boolean = false,
    val samplerOptions: List<String> = emptyList(),
) : ProviderSettings {
    companion object {
        const val DEFAULT_TEMPLATE = """{
  "prompt": "{{prompt}}",
  "negative_prompt": "{{negative_prompt}}",
  "width": "{{width}}",
  "height": "{{height}}",
  "steps": "{{steps}}",
  "cfg_scale": "{{cfg_scale}}",
  "seed": "{{seed}}"
}"""
    }
}

/** Router providers on router.huggingface.co that this app has a request adapter for. */
object HfRouterProviders {
    const val AUTO = "auto"
    val supported = listOf("fal-ai", "replicate", "together", "nscale", "wavespeed", "hf-inference")
    fun displayName(id: String): String = when (id) {
        AUTO -> "Automatic (your HF provider order)"
        "hf-inference" -> "HF Inference"
        "fal-ai" -> "fal.ai"
        "replicate" -> "Replicate"
        "together" -> "Together AI"
        "nscale" -> "Nscale"
        "wavespeed" -> "WaveSpeed"
        else -> id
    }
}

data class ConnectionInfo(
    val serverName: String,
    val version: String? = null,
    val details: List<String> = emptyList(),
)

enum class ConnectionState { UNKNOWN, CHECKING, ONLINE, ERROR }

data class ProviderStatus(
    val state: ConnectionState = ConnectionState.UNKNOWN,
    val info: ConnectionInfo? = null,
    val error: String? = null,
    val checkedAt: Long? = null,
)
