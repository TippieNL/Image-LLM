package com.unfiltered.studio.data.providers.custom

import com.unfiltered.studio.domain.models.AuthConfig
import com.unfiltered.studio.domain.models.AuthType
import com.unfiltered.studio.domain.models.CustomAsyncConfig
import com.unfiltered.studio.domain.models.CustomLoraMode
import com.unfiltered.studio.domain.models.CustomResponseMode
import com.unfiltered.studio.domain.models.CustomRestSettings
import com.unfiltered.studio.domain.models.HeaderEntry

/** Ready-made request/response mappings for popular hosted APIs (editable after applying). */
object CustomRestPresets {
    data class Preset(
        val name: String,
        val description: String,
        val baseUrl: String,
        val auth: AuthConfig,
        val settings: CustomRestSettings,
    )

    val all: List<Preset> = listOf(
        Preset(
            name = "Replicate",
            description = "Predictions API. Put \"owner/model\" in the model field (e.g. black-forest-labs/flux-dev).",
            baseUrl = "https://api.replicate.com",
            auth = AuthConfig(type = AuthType.BEARER, extraHeaders = listOf(HeaderEntry("Prefer", "wait"))),
            settings = CustomRestSettings(
                method = "POST",
                path = "/v1/models/{{model}}/predictions",
                bodyTemplate = """{
  "input": {
    "prompt": "{{prompt}}",
    "negative_prompt": "{{negative_prompt}}",
    "width": "{{width}}",
    "height": "{{height}}",
    "num_inference_steps": "{{steps}}",
    "guidance": "{{guidance}}",
    "seed": "{{seed}}",
    "num_outputs": "{{batch_size}}",
    "output_format": "png"
  }
}""",
                imagePath = "output",
                async = CustomAsyncConfig(
                    jobIdPath = "id",
                    statusUrlPath = "urls.get",
                    statusPath = "status",
                    successValues = listOf("succeeded"),
                    failureValues = listOf("failed", "canceled"),
                    errorPath = "error",
                    cancelUrlPath = "urls.cancel",
                    cancelMethod = "POST",
                    pollIntervalMs = 1500,
                ),
                supportsBatch = true,
                supportsModel = true,
            ),
        ),
        Preset(
            name = "fal.ai queue",
            description = "Queue API with polling. Model = fal endpoint id (e.g. fal-ai/flux-lora). LoRAs are sent as {path, scale}.",
            baseUrl = "https://queue.fal.run",
            auth = AuthConfig(type = AuthType.API_KEY_HEADER, headerName = "Authorization", headerValuePrefix = "Key "),
            settings = CustomRestSettings(
                method = "POST",
                path = "/{{model}}",
                bodyTemplate = """{
  "prompt": "{{prompt}}",
  "negative_prompt": "{{negative_prompt}}",
  "image_size": { "width": "{{width}}", "height": "{{height}}" },
  "num_inference_steps": "{{steps}}",
  "guidance_scale": "{{guidance}}",
  "seed": "{{seed}}",
  "num_images": "{{batch_size}}",
  "loras": "{{loras_path_scale}}"
}""",
                imagePath = "images[*].url",
                async = CustomAsyncConfig(
                    jobIdPath = "request_id",
                    statusUrlPath = "status_url",
                    statusPath = "status",
                    successValues = listOf("COMPLETED"),
                    failureValues = listOf("FAILED", "ERROR"),
                    resultUrlPath = "response_url",
                    errorPath = "detail",
                    cancelUrlPath = "cancel_url",
                    cancelMethod = "PUT",
                    pollIntervalMs = 1000,
                ),
                loraMode = CustomLoraMode.TEMPLATE_VARIABLE,
                supportsBatch = true,
                supportsModel = true,
            ),
        ),
        Preset(
            name = "RunPod Serverless",
            description = "Async /run + /status/{id}. Replace YOUR_ENDPOINT_ID and adapt the input/output paths to your worker.",
            baseUrl = "https://api.runpod.ai/v2/YOUR_ENDPOINT_ID",
            auth = AuthConfig(type = AuthType.BEARER),
            settings = CustomRestSettings(
                method = "POST",
                path = "/run",
                bodyTemplate = """{
  "input": {
    "prompt": "{{prompt}}",
    "negative_prompt": "{{negative_prompt}}",
    "width": "{{width}}",
    "height": "{{height}}",
    "steps": "{{steps}}",
    "cfg_scale": "{{cfg_scale}}",
    "seed": "{{seed}}"
  }
}""",
                imagePath = "output.images[*].data",
                async = CustomAsyncConfig(
                    jobIdPath = "id",
                    statusUrlTemplate = "{{base_url}}/status/{{job_id}}",
                    statusPath = "status",
                    successValues = listOf("COMPLETED"),
                    failureValues = listOf("FAILED", "CANCELLED", "TIMED_OUT"),
                    errorPath = "error",
                    cancelUrlTemplate = "{{base_url}}/cancel/{{job_id}}",
                    cancelMethod = "POST",
                    pollIntervalMs = 2000,
                ),
            ),
        ),
        Preset(
            name = "Generic JSON (sync)",
            description = "POST returns {\"images\": [base64 or URL, …]}.",
            baseUrl = "https://",
            auth = AuthConfig(),
            settings = CustomRestSettings(),
        ),
        Preset(
            name = "Raw image response",
            description = "POST returns the image bytes directly.",
            baseUrl = "https://",
            auth = AuthConfig(),
            settings = CustomRestSettings(responseMode = CustomResponseMode.IMAGE_BYTES),
        ),
    )
}
