package com.unfiltered.studio.data.providers

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.doubleValueOrNull
import com.unfiltered.studio.core.longValueOrNull
import com.unfiltered.studio.core.str
import com.unfiltered.studio.data.providers.comfyui.ComfyWorkflowBuilder
import com.unfiltered.studio.data.providers.comfyui.NodeRef
import com.unfiltered.studio.domain.models.ComfyTemplate
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.HiresFixParams
import com.unfiltered.studio.domain.models.LoraSelection
import com.unfiltered.studio.domain.models.ModelArchitecture
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyWorkflowBuilderTest {

    private fun JsonObject.nodes(classType: String) = entries.filter { (it.value as JsonObject).str("class_type") == classType }
    private fun JsonObject.node(id: String) = (this[id] as JsonObject)
    private fun JsonObject.inputs(id: String) = node(id)["inputs"] as JsonObject
    private fun JsonObject.ref(id: String, input: String) = NodeRef.from(inputs(id)[input])

    private val loras = listOf(
        LoraSelection(name = "A", backendName = "a.safetensors", strength = 0.75f),
        LoraSelection(name = "B", backendName = "sub/b.safetensors", strength = 0.6f, clipStrength = 0.3f),
        LoraSelection(name = "Off", backendName = "off.safetensors", enabled = false),
    )

    @Test fun builtInCheckpointGraphChainsLorasClipSkipVaeAndHires() {
        val p = GenerationParameters(
            prompt = "a castle", negativePrompt = "blurry", width = 832, height = 1216, steps = 30, cfgScale = 6f, seed = 99,
            sampler = "dpmpp_2m", scheduler = "karras", batchSize = 2, clipSkip = 2, checkpoint = "sdxl.safetensors",
            vae = "sdxl_vae.safetensors", loras = loras, hiresFix = HiresFixParams(enabled = true, scale = 1.5f, denoise = 0.4f),
        )
        val g = ComfyWorkflowBuilder.buildBuiltIn(ComfyTemplate.CHECKPOINT, p, initImage = null, filenamePrefix = "test").prompt

        val ckpt = g.nodes("CheckpointLoaderSimple").single().key
        assertEquals("sdxl.safetensors", g.inputs(ckpt).str("ckpt_name"))
        val loraNodes = g.nodes("LoraLoader").map { it.key }
        assertEquals(2, loraNodes.size)
        val (l1, l2) = loraNodes
        assertEquals(NodeRef(ckpt, 0), g.ref(l1, "model"))
        assertEquals(NodeRef(ckpt, 1), g.ref(l1, "clip"))
        assertEquals(NodeRef(l1, 0), g.ref(l2, "model"))
        assertEquals(0.3, g.inputs(l2)["strength_clip"].doubleValueOrNull!!, 0.001)

        val clipSkip = g.nodes("CLIPSetLastLayer").single().key
        assertEquals(NodeRef(l2, 1), g.ref(clipSkip, "clip"))
        assertEquals(-2L, g.inputs(clipSkip)["stop_at_clip_layer"].longValueOrNull)
        g.nodes("CLIPTextEncode").forEach { assertEquals(NodeRef(clipSkip, 0), g.ref(it.key, "clip")) }

        val samplers = g.nodes("KSampler").map { it.key }
        assertEquals(2, samplers.size)
        samplers.forEach { assertEquals(NodeRef(l2, 0), g.ref(it, "model")) }
        val upscale = g.nodes("LatentUpscaleBy").single().key
        assertEquals(NodeRef(samplers[0], 0), g.ref(upscale, "samples"))
        assertEquals(0.4, g.inputs(samplers[1])["denoise"].doubleValueOrNull!!, 0.001)

        val latent = g.nodes("EmptyLatentImage").single().key
        assertEquals(2L, g.inputs(latent)["batch_size"].longValueOrNull)
        val vae = g.nodes("VAELoader").single().key
        val decode = g.nodes("VAEDecode").single().key
        assertEquals(NodeRef(vae, 0), g.ref(decode, "vae"))
        assertEquals(NodeRef(samplers[1], 0), g.ref(decode, "samples"))
        assertEquals("test", g.inputs(g.nodes("SaveImage").single().key).str("filename_prefix"))
    }

    @Test fun fluxCheckpointUsesGuidanceAndSd3Latent() {
        val p = GenerationParameters(prompt = "x", architecture = ModelArchitecture.FLUX1, cfgScale = 1f, distilledGuidance = 3.2f, checkpoint = "flux.safetensors")
        val template = ComfyWorkflowBuilder.resolveTemplate(ComfyTemplate.AUTO, ModelArchitecture.FLUX1)
        assertEquals(ComfyTemplate.FLUX_CHECKPOINT, template)
        val g = ComfyWorkflowBuilder.buildBuiltIn(template, p, null, "x").prompt
        val guidance = g.nodes("FluxGuidance").single().key
        assertEquals(3.2, g.inputs(guidance)["guidance"].doubleValueOrNull!!, 0.01)
        assertEquals(NodeRef(guidance, 0), g.ref(g.nodes("KSampler").single().key, "positive"))
        assertEquals(1, g.nodes("EmptySD3LatentImage").size)
        assertTrue(g.nodes("CLIPSetLastLayer").isEmpty())
    }

    @Test fun img2imgUploadsThroughLoadImageAndVaeEncode() {
        val p = GenerationParameters(prompt = "x", checkpoint = "c", initImagePath = "/tmp/a.png", denoisingStrength = 0.55f, batchSize = 2)
        val g = ComfyWorkflowBuilder.buildBuiltIn(ComfyTemplate.CHECKPOINT, p, initImage = "uploaded.png", filenamePrefix = "x").prompt
        val load = g.nodes("LoadImage").single().key
        assertEquals("uploaded.png", g.inputs(load).str("image"))
        val encode = g.nodes("VAEEncode").single().key
        assertEquals(NodeRef(load, 0), g.ref(encode, "pixels"))
        val repeat = g.nodes("RepeatLatentBatch").single().key
        val sampler = g.nodes("KSampler").single().key
        assertEquals(NodeRef(repeat, 0), g.ref(sampler, "latent_image"))
        assertEquals(0.55, g.inputs(sampler)["denoise"].doubleValueOrNull!!, 0.001)
        assertTrue(g.nodes("EmptyLatentImage").isEmpty())
    }

    // The default ComfyUI workflow, exported with "Export (API)".
    private val defaultWorkflow = """
    {
      "3": {"class_type": "KSampler", "inputs": {"seed": 1, "steps": 20, "cfg": 8, "sampler_name": "euler", "scheduler": "normal", "denoise": 1,
            "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
      "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "v1-5-pruned-emaonly.safetensors"}},
      "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 512, "height": 512, "batch_size": 1}},
      "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "beautiful scenery", "clip": ["4", 1]}},
      "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "text, watermark", "clip": ["4", 1]}},
      "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
      "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ComfyUI", "images": ["8", 0]}}
    }"""

    @Test fun customWorkflowIsMappedAutomaticallyAndLorasAreSpliced() {
        val p = GenerationParameters(
            prompt = "a cat", negativePrompt = "blurry", seed = 123, steps = 12, cfgScale = 5.5f, width = 640, height = 768, batchSize = 2,
            sampler = "dpmpp_2m", checkpoint = "dream.safetensors", loras = loras.take(1),
        )
        val built = ComfyWorkflowBuilder.applyCustom(defaultWorkflow, p, initImage = null)
        val g = built.prompt
        assertEquals("a cat", g.inputs("6").str("text"))
        assertEquals("blurry", g.inputs("7").str("text"))
        assertEquals(123L, g.inputs("3")["seed"].longValueOrNull)
        assertEquals(12L, g.inputs("3")["steps"].longValueOrNull)
        assertEquals(5.5, g.inputs("3")["cfg"].doubleValueOrNull!!, 0.001)
        assertEquals("dpmpp_2m", g.inputs("3").str("sampler_name"))
        assertEquals(640L, g.inputs("5")["width"].longValueOrNull)
        assertEquals(2L, g.inputs("5")["batch_size"].longValueOrNull)
        assertEquals("dream.safetensors", g.inputs("4").str("ckpt_name"))

        val lora = g.nodes("LoraLoader").single().key
        assertEquals(NodeRef("4", 0), g.ref(lora, "model"))
        assertEquals(NodeRef("4", 1), g.ref(lora, "clip"))
        assertEquals(NodeRef(lora, 0), g.ref("3", "model"))
        assertEquals(NodeRef(lora, 1), g.ref("6", "clip"))
        assertEquals(NodeRef(lora, 1), g.ref("7", "clip"))
        assertEquals(NodeRef("4", 2), g.ref("8", "vae"))
        assertTrue(built.warnings.isEmpty())
    }

    @Test fun placeholdersTakePrecedence() {
        val wf = defaultWorkflow.replace("\"beautiful scenery\"", "\"masterpiece, {{positive_prompt}}\"").replace("\"seed\": 1", "\"seed\": \"{{seed}}\"")
        val g = ComfyWorkflowBuilder.applyCustom(wf, GenerationParameters(prompt = "a dog", seed = 7), null).prompt
        assertEquals("masterpiece, a dog", g.inputs("6").str("text"))
        assertEquals(7L, g.inputs("3")["seed"].longValueOrNull)
    }

    @Test(expected = AppError.Configuration::class)
    fun rejectsUiFormatWorkflows() {
        ComfyWorkflowBuilder.parseCustom("""{"last_node_id": 9, "nodes": [], "links": []}""")
    }

    @Test fun analysisReportsMappableInputs() {
        val a = ComfyWorkflowBuilder.analyze(defaultWorkflow)
        assertTrue(a.positiveText && a.negativeText && a.size && a.batch && a.seed && a.steps && a.cfg && a.sampler && a.checkpoint && a.loraInjection)
        assertFalse(a.guidance)
        assertEquals(1, a.outputs)
    }

    // FLUX dev split-file workflow using the custom sampling nodes.
    private val fluxWorkflow = """
    {
      "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "old", "clip": ["11", 0]}},
      "8": {"class_type": "VAEDecode", "inputs": {"samples": ["13", 0], "vae": ["10", 0]}},
      "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "flux", "images": ["8", 0]}},
      "10": {"class_type": "VAELoader", "inputs": {"vae_name": "ae.safetensors"}},
      "11": {"class_type": "DualCLIPLoader", "inputs": {"clip_name1": "t5xxl_fp16.safetensors", "clip_name2": "clip_l.safetensors", "type": "flux"}},
      "12": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux1-dev.safetensors", "weight_dtype": "default"}},
      "13": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["25", 0], "guider": ["22", 0], "sampler": ["16", 0], "sigmas": ["17", 0], "latent_image": ["27", 0]}},
      "16": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
      "17": {"class_type": "BasicScheduler", "inputs": {"scheduler": "simple", "steps": 20, "denoise": 1, "model": ["30", 0]}},
      "22": {"class_type": "BasicGuider", "inputs": {"model": ["30", 0], "conditioning": ["26", 0]}},
      "25": {"class_type": "RandomNoise", "inputs": {"noise_seed": 1}},
      "26": {"class_type": "FluxGuidance", "inputs": {"guidance": 3.5, "conditioning": ["6", 0]}},
      "27": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
      "30": {"class_type": "ModelSamplingFlux", "inputs": {"max_shift": 1.15, "base_shift": 0.5, "width": 1024, "height": 1024, "model": ["12", 0]}}
    }"""

    @Test fun customSamplerAdvancedWorkflowIsMapped() {
        val p = GenerationParameters(
            prompt = "a neon city", seed = 555, steps = 28, distilledGuidance = 2.5f, scheduler = "beta", sampler = "deis",
            width = 896, height = 1152, architecture = ModelArchitecture.FLUX1, loras = loras.take(1),
        )
        val g = ComfyWorkflowBuilder.applyCustom(fluxWorkflow, p, null).prompt
        assertEquals("a neon city", g.inputs("6").str("text"))
        assertEquals(555L, g.inputs("25")["noise_seed"].longValueOrNull)
        assertEquals(28L, g.inputs("17")["steps"].longValueOrNull)
        assertEquals("beta", g.inputs("17").str("scheduler"))
        assertEquals("deis", g.inputs("16").str("sampler_name"))
        assertEquals(2.5, g.inputs("26")["guidance"].doubleValueOrNull!!, 0.01)
        assertEquals(896L, g.inputs("27")["width"].longValueOrNull)

        val lora = g.nodes("LoraLoader").single().key
        assertEquals(NodeRef("12", 0), g.ref(lora, "model"))
        assertEquals(NodeRef("11", 0), g.ref(lora, "clip"))
        assertEquals(NodeRef(lora, 0), g.ref("30", "model"))
        assertEquals(NodeRef(lora, 1), g.ref("6", "clip"))
        assertNotNull(g.ref("17", "model"))

        val analysis = ComfyWorkflowBuilder.analyze(fluxWorkflow)
        assertTrue(analysis.guidance && analysis.unet && analysis.loraInjection)
        assertFalse(analysis.cfg)
        assertFalse(analysis.negativeText)
    }
}
