package com.unfiltered.studio.domain.models

import kotlinx.serialization.Serializable

/**
 * LoRAs are only interchangeable inside a family. Derivatives (Pony, Illustrious) share the SDXL
 * family: their LoRAs load, but results vary, so they are reported as "partially compatible".
 */
@Serializable
enum class ArchitectureFamily(val displayName: String) {
    SD1("Stable Diffusion 1.x"),
    SD2("Stable Diffusion 2.x"),
    SDXL("SDXL family"),
    SD3("Stable Diffusion 3.x"),
    FLUX1("FLUX.1"),
    FLUX2("FLUX.2"),
    CHROMA("Chroma"),
    QWEN_IMAGE("Qwen-Image"),
    HIDREAM("HiDream"),
    Z_IMAGE("Z-Image"),
    HUNYUAN_IMAGE("HunyuanImage"),
    OTHER("Other / unknown"),
}

@Serializable
enum class ModelArchitecture(
    val displayName: String,
    val family: ArchitectureFamily,
    /** Training resolution; aspect-ratio presets scale around it. */
    val nativeResolution: Int,
    val defaultSteps: Int,
    val defaultCfg: Float,
    /** False for guidance-distilled models where CFG≈1 makes the negative prompt a no-op. */
    val usesNegativePrompt: Boolean,
    val supportsClipSkip: Boolean,
    /** Uses a separate "distilled guidance" value (FLUX guidance / Forge distilled CFG). */
    val usesDistilledGuidance: Boolean,
    val defaultGuidance: Float,
    /** ComfyUI latent node used by the built-in templates. */
    val comfyLatentNode: String,
    /** Whether the built-in ComfyUI templates can drive this architecture (others need a custom workflow). */
    val builtInComfySupport: Boolean,
) {
    SD15("SD 1.5", ArchitectureFamily.SD1, 512, 25, 7f, true, true, false, 0f, "EmptyLatentImage", true),
    SD2("SD 2.x", ArchitectureFamily.SD2, 768, 25, 7f, true, true, false, 0f, "EmptyLatentImage", true),
    SDXL("SDXL", ArchitectureFamily.SDXL, 1024, 30, 6f, true, true, false, 0f, "EmptyLatentImage", true),
    PONY("Pony (SDXL)", ArchitectureFamily.SDXL, 1024, 25, 7f, true, true, false, 0f, "EmptyLatentImage", true),
    ILLUSTRIOUS("Illustrious / NoobAI (SDXL)", ArchitectureFamily.SDXL, 1024, 28, 5.5f, true, true, false, 0f, "EmptyLatentImage", true),
    SD3("SD 3 / 3.5", ArchitectureFamily.SD3, 1024, 28, 4.5f, true, false, false, 0f, "EmptySD3LatentImage", true),
    FLUX1("FLUX.1 (dev / schnell / Krea)", ArchitectureFamily.FLUX1, 1024, 20, 1f, false, false, true, 3.5f, "EmptySD3LatentImage", true),
    FLUX2("FLUX.2", ArchitectureFamily.FLUX2, 1024, 28, 1f, false, false, true, 4f, "EmptySD3LatentImage", false),
    CHROMA("Chroma", ArchitectureFamily.CHROMA, 1024, 26, 4f, true, false, false, 0f, "EmptySD3LatentImage", false),
    QWEN_IMAGE("Qwen-Image", ArchitectureFamily.QWEN_IMAGE, 1328, 30, 4f, true, false, false, 0f, "EmptySD3LatentImage", false),
    HIDREAM("HiDream", ArchitectureFamily.HIDREAM, 1024, 28, 5f, true, false, false, 0f, "EmptySD3LatentImage", false),
    Z_IMAGE("Z-Image", ArchitectureFamily.Z_IMAGE, 1024, 9, 1f, false, false, false, 0f, "EmptySD3LatentImage", false),
    HUNYUAN_IMAGE("HunyuanImage", ArchitectureFamily.HUNYUAN_IMAGE, 1024, 30, 4f, true, false, false, 0f, "EmptySD3LatentImage", false),
    OTHER("Other / unknown", ArchitectureFamily.OTHER, 1024, 25, 7f, true, false, false, 0f, "EmptyLatentImage", false),
    ;

    companion object {
        /**
         * Guesses the architecture from a checkpoint/LoRA filename, repo id or base-model label.
         * Returns null when nothing matches — callers must then treat compatibility as unknown
         * instead of assuming SD/SDXL.
         */
        fun detect(text: String?): ModelArchitecture? {
            if (text.isNullOrBlank()) return null
            val t = text.lowercase().replace('_', '-').replace(' ', '-')
            return when {
                "pony" in t -> PONY
                "illustrious" in t || "noobai" in t || "noob-ai" in t || Regex("""(^|[^a-z])ilxl""").containsMatchIn(t) -> ILLUSTRIOUS
                Regex("""flux[.-]?2""").containsMatchIn(t) -> FLUX2
                "flux" in t || "schnell" in t -> FLUX1
                "chroma" in t -> CHROMA
                "qwen" in t -> QWEN_IMAGE
                "hidream" in t -> HIDREAM
                "z-image" in t || "zimage" in t -> Z_IMAGE
                "hunyuan" in t -> HUNYUAN_IMAGE
                Regex("""(sd|stable-diffusion)-?3""").containsMatchIn(t) || "sd3" in t -> SD3
                "sdxl" in t || Regex("""(^|[^a-z0-9])xl([^a-z]|$)""").containsMatchIn(t) || t.endsWith("xl") ||
                    "stable-diffusion-xl" in t || "juggernaut" in t || "realvisxl" in t -> SDXL
                Regex("""(sd-?2|v2-1|stable-diffusion-2|768-v)""").containsMatchIn(t) -> SD2
                Regex("""(sd-?1[.-]?5|v1-5|sd15|stable-diffusion-v1|sd-?1[.-]?4)""").containsMatchIn(t) -> SD15
                else -> null
            }
        }

        /** Maps Civitai `baseModel` labels (e.g. "SDXL 1.0", "Flux.1 D", "Pony") to architectures. */
        fun fromCivitaiBaseModel(label: String?): ModelArchitecture {
            if (label.isNullOrBlank()) return OTHER
            val l = label.lowercase()
            return when {
                l.startsWith("sd 1") -> SD15
                l.startsWith("sd 2") -> SD2
                l.startsWith("sd 3") -> SD3
                l.startsWith("pony") -> PONY
                l.startsWith("illustrious") || l.startsWith("noobai") -> ILLUSTRIOUS
                l.startsWith("sdxl") -> SDXL
                l.startsWith("flux.2") || l.startsWith("flux 2") -> FLUX2
                l.startsWith("flux") -> FLUX1
                else -> detect(label) ?: OTHER
            }
        }

        /** Civitai `baseModels` filter values for each family (used by the model browser). */
        fun civitaiBaseModels(family: ArchitectureFamily): List<String> = when (family) {
            ArchitectureFamily.SD1 -> listOf("SD 1.4", "SD 1.5", "SD 1.5 LCM", "SD 1.5 Hyper")
            ArchitectureFamily.SD2 -> listOf("SD 2.0", "SD 2.1")
            ArchitectureFamily.SDXL -> listOf("SDXL 1.0", "SDXL Lightning", "SDXL Hyper", "SDXL Turbo", "Pony", "Illustrious", "NoobAI")
            ArchitectureFamily.SD3 -> listOf("SD 3", "SD 3.5", "SD 3.5 Medium", "SD 3.5 Large", "SD 3.5 Large Turbo")
            ArchitectureFamily.FLUX1 -> listOf("Flux.1 D", "Flux.1 S", "Flux.1 Krea", "Flux.1 Kontext")
            ArchitectureFamily.FLUX2 -> listOf("Flux.2 D", "Flux.2 Klein")
            ArchitectureFamily.CHROMA -> listOf("Chroma")
            ArchitectureFamily.QWEN_IMAGE -> listOf("Qwen")
            ArchitectureFamily.HIDREAM -> listOf("HiDream")
            ArchitectureFamily.Z_IMAGE -> listOf("ZImageTurbo", "ZImageBase")
            ArchitectureFamily.HUNYUAN_IMAGE -> listOf("Hunyuan 1")
            ArchitectureFamily.OTHER -> emptyList()
        }

        /** Hugging Face base-model repo used for `base_model:adapter:<repo>` LoRA filtering. */
        fun huggingFaceBaseRepo(family: ArchitectureFamily): String? = when (family) {
            ArchitectureFamily.SD1 -> "stable-diffusion-v1-5/stable-diffusion-v1-5"
            ArchitectureFamily.SD2 -> "stabilityai/stable-diffusion-2-1"
            ArchitectureFamily.SDXL -> "stabilityai/stable-diffusion-xl-base-1.0"
            ArchitectureFamily.SD3 -> "stabilityai/stable-diffusion-3.5-large"
            ArchitectureFamily.FLUX1 -> "black-forest-labs/FLUX.1-dev"
            ArchitectureFamily.FLUX2 -> "black-forest-labs/FLUX.2-dev"
            ArchitectureFamily.QWEN_IMAGE -> "Qwen/Qwen-Image"
            ArchitectureFamily.CHROMA -> "lodestones/Chroma"
            ArchitectureFamily.HIDREAM -> "HiDream-ai/HiDream-I1-Full"
            ArchitectureFamily.Z_IMAGE -> "Tongyi-MAI/Z-Image-Turbo"
            ArchitectureFamily.HUNYUAN_IMAGE -> null
            ArchitectureFamily.OTHER -> null
        }
    }
}

enum class Compatibility { COMPATIBLE, PARTIAL, INCOMPATIBLE, UNKNOWN }

object CompatibilityRules {
    /** Compatibility of a LoRA trained on [lora] when applied to a checkpoint of type [base]. */
    fun loraOnBase(lora: ModelArchitecture?, base: ModelArchitecture?): Compatibility {
        if (lora == null || base == null) return Compatibility.UNKNOWN
        if (lora == ModelArchitecture.OTHER || base == ModelArchitecture.OTHER) return Compatibility.UNKNOWN
        if (lora == base) return Compatibility.COMPATIBLE
        if (lora.family == base.family) return Compatibility.PARTIAL
        // Chroma is derived from FLUX.1-schnell: many FLUX.1 LoRAs load with reduced fidelity.
        val pair = setOf(lora.family, base.family)
        if (pair == setOf(ArchitectureFamily.FLUX1, ArchitectureFamily.CHROMA)) return Compatibility.PARTIAL
        return Compatibility.INCOMPATIBLE
    }

    fun describe(c: Compatibility, lora: ModelArchitecture?, base: ModelArchitecture?): String? = when (c) {
        Compatibility.COMPATIBLE -> null
        Compatibility.PARTIAL -> "Trained for ${lora?.displayName}; same family as ${base?.displayName} — usually loads, results may differ."
        Compatibility.INCOMPATIBLE -> "Trained for ${lora?.displayName}, but the model is ${base?.displayName}. This LoRA will likely fail to load or produce garbage."
        Compatibility.UNKNOWN -> "Base model unknown — compatibility can't be verified."
    }
}
