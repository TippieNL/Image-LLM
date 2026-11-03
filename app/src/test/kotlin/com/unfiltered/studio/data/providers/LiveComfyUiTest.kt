package com.unfiltered.studio.data.providers

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.data.providers.comfyui.ComfyUiProvider
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.ComfyTemplate
import com.unfiltered.studio.domain.models.ComfyWorkflowMode
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.LoraSelection
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.testutil.TestHttp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Opt-in compatibility check against a real ComfyUI server (skipped unless COMFY_URL is set):
 *
 *   COMFY_URL=http://192.168.1.20:8188 ./gradlew :app:testDebugUnitTest --tests '*LiveComfyUiTest*'
 *
 * No models are needed: built-in templates are validated by ComfyUI itself (only model-file inputs
 * may be reported), and the success path uses the core EmptyImage → SaveImage nodes.
 */
class LiveComfyUiTest {
    private val url: String? = System.getenv("COMFY_URL")?.trimEnd('/')

    private fun provider(settings: ComfySettings) =
        ComfyUiProvider(TestHttp.config(ProviderType.COMFYUI, url!!, settings), TestHttp.http(TestHttp.client()), pollIntervalMs = 300)

    @Test fun connectsAndListsCatalog() = runBlocking {
        assumeTrue("Set COMFY_URL to run", url != null)
        val p = provider(ComfySettings())
        val info = p.testConnection()
        assertEquals("ComfyUI", info.serverName)
        val catalog = p.fetchCatalog()
        assertTrue("sampler list from object_info", catalog.samplers.contains("euler"))
        assertTrue(catalog.schedulers.contains("normal"))
    }

    @Test fun builtInTemplatesPassServerValidationExceptForModelFiles() = runBlocking {
        assumeTrue("Set COMFY_URL to run", url != null)
        val modelInputs = setOf("ckpt_name", "lora_name", "vae_name", "unet_name", "clip_name1", "clip_name2")
        for (template in ComfyTemplate.entries.filter { it != ComfyTemplate.AUTO }) {
            val p = provider(ComfySettings(template = template))
            val params = GenerationParameters(
                prompt = "validation probe", negativePrompt = "x", checkpoint = "__missing__.safetensors", vae = "__missing_vae__.safetensors",
                architecture = if (template == ComfyTemplate.FLUX_CHECKPOINT || template == ComfyTemplate.FLUX_SPLIT) ModelArchitecture.FLUX1 else ModelArchitecture.SDXL,
                clipSkip = 2, steps = 2, width = 512, height = 512,
                loras = listOf(LoraSelection(name = "probe", backendName = "__missing_lora__.safetensors")),
                components = mapOf("unet" to "__missing__.safetensors", "clip1" to "__missing__.safetensors", "clip2" to "__missing__.safetensors", "vae_file" to "__missing__.safetensors"),
            )
            val error = runCatching { p.generate(GenerationRequest("live", "provider-1", params)).toList() }.exceptionOrNull()
            assertTrue("$template should fail validation on missing files, got $error", error is AppError.Validation)
            val issues = (error as AppError.Validation).issues
            val unexpected = issues.filter { issue -> modelInputs.none { it in issue } && "Node" in issue }
            assertTrue("$template: unexpected validation problems $unexpected", unexpected.isEmpty())
        }
    }

    @Test fun customWorkflowRoundTripWithWebSocketAndPolling() = runBlocking {
        assumeTrue("Set COMFY_URL to run", url != null)
        val workflow = """{
          "1": {"class_type": "EmptyImage", "inputs": {"width": "{{width}}", "height": "{{height}}", "batch_size": "{{batch_size}}", "color": 9133302}},
          "2": {"class_type": "SaveImage", "inputs": {"filename_prefix": "unfiltered_live_test", "images": ["1", 0]}}
        }"""
        for (ws in listOf(true, false)) {
            val p = provider(ComfySettings(workflowMode = ComfyWorkflowMode.CUSTOM, customWorkflowJson = workflow, useWebSocket = ws))
            val events = p.generate(GenerationRequest("live", "provider-1", GenerationParameters(prompt = "unused", width = 320, height = 192, batchSize = 2))).toList()
            val images = events.filterIsInstance<GenerationEvent.Image>()
            assertEquals("websocket=$ws", 2, images.size)
            val png = images.first().image.bytes
            assertEquals(320, ByteBuffer.wrap(png, 16, 4).int)
            assertEquals(192, ByteBuffer.wrap(png, 20, 4).int)
            assertTrue(events.any { it is GenerationEvent.Submitted && it.remoteJobId != null })
        }
    }
}
