package com.unfiltered.studio.domain

import com.unfiltered.studio.domain.models.A1111Settings
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.AspectRatioPreset
import com.unfiltered.studio.domain.models.AuthConfig
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BackendModel
import com.unfiltered.studio.domain.models.BatchSupport
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.Compatibility
import com.unfiltered.studio.domain.models.CompatibilityRules
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.models.SizeConstraints
import com.unfiltered.studio.domain.usecases.GenerationPreparer
import com.unfiltered.studio.domain.usecases.InfotextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ModelArchitectureTest {
    @Test fun detectsFromFilenames() {
        assertEquals(ModelArchitecture.PONY, ModelArchitecture.detect("ponyDiffusionV6XL_v6.safetensors"))
        assertEquals(ModelArchitecture.ILLUSTRIOUS, ModelArchitecture.detect("noobaiXLNAIXL_vPred10.safetensors"))
        assertEquals(ModelArchitecture.SDXL, ModelArchitecture.detect("juggernautXL_v9.safetensors"))
        assertEquals(ModelArchitecture.SDXL, ModelArchitecture.detect("sd_xl_base_1.0.safetensors"))
        assertEquals(ModelArchitecture.FLUX1, ModelArchitecture.detect("flux1-dev-fp8.safetensors"))
        assertEquals(ModelArchitecture.FLUX2, ModelArchitecture.detect("black-forest-labs/FLUX.2-dev"))
        assertEquals(ModelArchitecture.SD3, ModelArchitecture.detect("sd3.5_large_fp8_scaled.safetensors"))
        assertEquals(ModelArchitecture.SD15, ModelArchitecture.detect("v1-5-pruned-emaonly.safetensors"))
        assertEquals(ModelArchitecture.QWEN_IMAGE, ModelArchitecture.detect("Qwen/Qwen-Image"))
        assertNull(ModelArchitecture.detect("my_custom_model.safetensors"))
    }

    @Test fun civitaiBaseModels() {
        assertEquals(ModelArchitecture.SDXL, ModelArchitecture.fromCivitaiBaseModel("SDXL 1.0"))
        assertEquals(ModelArchitecture.PONY, ModelArchitecture.fromCivitaiBaseModel("Pony"))
        assertEquals(ModelArchitecture.FLUX1, ModelArchitecture.fromCivitaiBaseModel("Flux.1 D"))
        assertEquals(ModelArchitecture.SD15, ModelArchitecture.fromCivitaiBaseModel("SD 1.5"))
        assertEquals(ModelArchitecture.OTHER, ModelArchitecture.fromCivitaiBaseModel("SomethingNew"))
    }

    @Test fun loraCompatibility() {
        assertEquals(Compatibility.COMPATIBLE, CompatibilityRules.loraOnBase(ModelArchitecture.SDXL, ModelArchitecture.SDXL))
        assertEquals(Compatibility.PARTIAL, CompatibilityRules.loraOnBase(ModelArchitecture.PONY, ModelArchitecture.ILLUSTRIOUS))
        assertEquals(Compatibility.INCOMPATIBLE, CompatibilityRules.loraOnBase(ModelArchitecture.SD15, ModelArchitecture.FLUX1))
        assertEquals(Compatibility.PARTIAL, CompatibilityRules.loraOnBase(ModelArchitecture.FLUX1, ModelArchitecture.CHROMA))
        assertEquals(Compatibility.UNKNOWN, CompatibilityRules.loraOnBase(null, ModelArchitecture.SDXL))
    }

    @Test fun aspectPresetsKeepPixelCount() {
        val (w, h) = AspectRatioPreset.WIDE_16_9.dimensions(1024)
        assertEquals(0, w % 64)
        assertEquals(0, h % 64)
        assertTrue(w > h)
        assertTrue(kotlin.math.abs(w * h - 1024 * 1024) < 1024 * 1024 * 0.12)
        assertEquals(AspectRatioPreset.PORTRAIT_2_3, AspectRatioPreset.matching(832, 1248))
    }
}

class GenerationPreparerTest {
    private val provider = ProviderConfig("p1", "Home Comfy", ProviderType.COMFYUI, "http://h", AuthConfig(), A1111Settings(), 0)
    private val caps = ProviderCapabilities(
        negativePrompt = true,
        size = SizeConstraints(64, 2048, 8),
        steps = 1..150,
        cfg = 1f..30f,
        seed = true,
        batch = BatchSupport.Native(4),
        checkpointSelection = CheckpointSelection.FromList(listOf("sdxl.safetensors"), allowEmpty = false),
        loras = LoraSupport.ComfyNodes,
    )
    private val catalog = BackendCatalog("p1", 0, loras = listOf(BackendModel("styles/detail_slider.safetensors")))
    private fun lora(id: String, name: String, file: String?, arch: ModelArchitecture?, links: Map<String, String> = emptyMap(), triggers: List<String> = emptyList()) =
        Lora(id = id, name = name, fileName = file, architecture = arch, backendLinks = links, triggerWords = triggers)

    @Test fun resolvesSeedSizeAndLoras() {
        val stack = listOf(
            ActiveLora(lora("a", "Detail", "detail_slider.safetensors", ModelArchitecture.SDXL, triggers = listOf("ultra detailed")), 0.75f, null, true, 0),
            ActiveLora(lora("b", "Linked", null, ModelArchitecture.SDXL, links = mapOf("p1" to "linked.safetensors")), 0.6f, 0.4f, true, 1),
            ActiveLora(lora("c", "Missing", "missing.safetensors", ModelArchitecture.SDXL), 0.8f, null, true, 2),
            ActiveLora(lora("d", "Disabled", null, ModelArchitecture.SDXL, links = mapOf("p1" to "x")), 1f, null, false, 3),
            ActiveLora(lora("e", "Old SD", null, ModelArchitecture.SD15, links = mapOf("p1" to "sd15.safetensors")), 1f, null, true, 4),
        )
        val prepared = GenerationPreparer(Random(1)).prepare(
            provider, caps, catalog,
            GenerationParameters(prompt = "a castle", width = 1023, height = 777, seed = -1, checkpoint = "sdxl.safetensors", architecture = ModelArchitecture.SDXL, batchSize = 9),
            stack,
        )
        assertTrue(prepared.errors.isEmpty())
        val p = prepared.params
        assertTrue(p.seed in 0..GenerationPreparer.MAX_SEED)
        assertEquals(1016, p.width)
        assertEquals(776, p.height)
        assertEquals(4, p.batchSize)
        assertEquals(listOf("styles/detail_slider.safetensors", "linked.safetensors", "sd15.safetensors"), p.loras.map { it.backendName })
        assertEquals(0.4f, p.loras[1].clipStrength)
        assertTrue(p.prompt.endsWith(", ultra detailed"))
        assertTrue(prepared.warnings.any { "Missing" in it && "isn't installed" in it })
        assertTrue(prepared.warnings.any { it.startsWith("Old SD:") })
    }

    @Test fun reportsMissingPromptAndCheckpoint() {
        val prepared = GenerationPreparer().prepare(provider, caps, catalog, GenerationParameters(prompt = " ", checkpoint = null), emptyList())
        assertTrue(prepared.errors.contains("Enter a prompt."))
        assertTrue(prepared.errors.contains("Select a checkpoint."))
    }

    @Test fun cloudProvidersNeedHuggingFaceWeights() {
        val cloudCaps = caps.copy(loras = LoraSupport.RemoteWeights(maxCount = 1, perLoraStrength = true), checkpointSelection = CheckpointSelection.FreeText("repo"))
        val stack = listOf(
            ActiveLora(Lora(id = "1", name = "HF one", hfRepoId = "o/r", hfWeightFile = "l.safetensors"), 0.9f, null, true, 0),
            ActiveLora(Lora(id = "2", name = "HF two", hfRepoId = "o/r2", hfWeightFile = "l.safetensors"), 0.9f, null, true, 1),
            ActiveLora(Lora(id = "3", name = "Local only"), 0.9f, null, true, 2),
        )
        val prepared = GenerationPreparer().prepare(provider, cloudCaps, null, GenerationParameters(prompt = "x", checkpoint = "o/base"), stack)
        assertEquals(listOf("o/r"), prepared.params.loras.map { it.hfRepoId })
        assertTrue(prepared.warnings.any { "Local only" in it })
        assertTrue(prepared.warnings.any { "at most 1" in it })
    }

    @Test fun infotextIsA1111Compatible() {
        val text = InfotextBuilder.build(
            GenerationParameters(prompt = "a cat", negativePrompt = "blurry", steps = 20, sampler = "Euler a", cfgScale = 7f, width = 512, height = 768),
            seed = 42, modelName = "dreamshaper_8.safetensors", providerName = "Home", appVersion = "1.0",
        )
        val lines = text.lines()
        assertEquals("a cat", lines[0])
        assertEquals("Negative prompt: blurry", lines[1])
        assertTrue(lines[2].startsWith("Steps: 20, Sampler: Euler a, CFG scale: 7, Seed: 42, Size: 512x768, Model: dreamshaper_8"))
    }
}
