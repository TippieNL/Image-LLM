package com.unfiltered.studio.domain.usecases

import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.PromptTemplate
import java.util.Locale
import kotlin.random.Random

/** Offline random prompt generator that adapts its phrasing to the model architecture. */
class RandomPromptGenerator(private val random: Random = Random.Default) {

    private val subjects = listOf(
        "a lone astronaut tending a greenhouse on Mars", "an old lighthouse keeper reading by lantern light",
        "a cyberpunk street vendor selling noodles in the rain", "a fox spirit wandering through a bamboo forest",
        "a Victorian inventor in a cluttered workshop", "a giant whale swimming through clouds above a city",
        "a samurai resting beneath a blooming cherry tree", "a cozy cabin library during a snowstorm",
        "a desert caravan crossing dunes at dusk", "a bioluminescent jellyfish garden in a deep ocean trench",
        "a street musician playing violin in an empty plaza", "an ancient dragon curled around a ruined tower",
        "a retro-futuristic diner on a lonely highway", "a botanist cataloguing glowing mushrooms",
        "a knight in weathered armor at the edge of a cliff", "a floating market on a misty river",
        "a portrait of an elderly fisherman with weathered skin", "a mechanical owl perched on a stack of books",
        "a neon-lit arcade full of vintage cabinets", "a witch brewing potions in a candlelit cottage",
    )
    private val styles = listOf(
        "cinematic film still", "oil painting", "watercolor illustration", "35mm analog photograph",
        "digital concept art", "studio Ghibli inspired illustration", "isometric 3D render", "charcoal sketch",
        "high fashion editorial photograph", "dark fantasy illustration", "ukiyo-e woodblock print", "low-poly render",
    )
    private val lighting = listOf(
        "golden hour light", "soft diffused light", "dramatic rim lighting", "moody volumetric fog",
        "neon reflections", "overcast natural light", "candlelight", "harsh midday sun", "moonlight",
    )
    private val compositions = listOf(
        "wide establishing shot", "close-up portrait", "low angle", "symmetrical composition",
        "rule of thirds", "aerial view", "shallow depth of field", "dutch angle",
    )
    private val palettes = listOf(
        "muted earth tones", "teal and orange", "pastel palette", "monochrome", "vibrant saturated colors",
        "deep blues and purples", "warm amber tones",
    )

    fun generate(architecture: ModelArchitecture): String {
        val subject = subjects.random(random)
        val style = styles.random(random)
        val light = lighting.random(random)
        val comp = compositions.random(random)
        val palette = palettes.random(random)
        return when {
            architecture == ModelArchitecture.PONY ->
                "score_9, score_8_up, score_7_up, $style, $subject, $light, $comp, $palette, highly detailed"
            architecture == ModelArchitecture.ILLUSTRIOUS ->
                "masterpiece, best quality, very aesthetic, $subject, $style, $light, $comp, $palette"
            architecture.family == ArchitectureFamily.SD1 || architecture.family == ArchitectureFamily.SD2 ->
                "$style of $subject, $light, $comp, $palette, highly detailed, sharp focus"
            else ->
                "A $style of $subject. The scene is lit by $light, framed as a $comp, with $palette. Rich detail and texture."
        }
    }
}

object TemplateApplier {
    /** Inserts [current] into the template's `{subject}` slot (or prefixes it when there is no slot). */
    fun apply(template: PromptTemplate, current: String): Pair<String, String> {
        val subject = current.trim().ifBlank { "your subject" }
        val prompt = if ("{subject}" in template.prompt) template.prompt.replace("{subject}", subject)
        else listOf(subject, template.prompt).filter { it.isNotBlank() }.joinToString(", ")
        return prompt to template.negativePrompt
    }

    val builtIns: List<PromptTemplate> = listOf(
        t("photo-portrait", "Photoreal portrait", "Photography",
            "professional portrait photograph of {subject}, 85mm lens, f/1.8, soft key light, natural skin texture, sharp focus on the eyes, shallow depth of field",
            "lowres, blurry, cartoon, 3d render, oversaturated, deformed hands, extra fingers, watermark, text"),
        t("cinematic", "Cinematic still", "Photography",
            "cinematic film still of {subject}, anamorphic lens, dramatic lighting, film grain, color graded, epic composition",
            "lowres, bad anatomy, watermark, text, logo, jpeg artifacts"),
        t("analog", "Analog film", "Photography",
            "35mm analog photograph of {subject}, Kodak Portra 400, natural light, subtle grain, candid moment",
            "digital, cgi, oversharpened, watermark"),
        t("product", "Product shot", "Commercial",
            "studio product photograph of {subject}, seamless backdrop, softbox lighting, crisp reflections, commercial advertising",
            "cluttered background, dust, blurry, low quality"),
        t("anime-tags", "Anime illustration (tags)", "Illustration",
            "masterpiece, best quality, very aesthetic, absurdres, 1girl, {subject}, detailed background, vibrant colors",
            "lowres, worst quality, bad anatomy, bad hands, jpeg artifacts, signature, watermark, extra digits"),
        t("pony", "Pony quality tags", "Illustration",
            "score_9, score_8_up, score_7_up, source_anime, {subject}, detailed, vivid colors",
            "score_4, score_5, score_6, lowres, bad anatomy, bad hands, watermark"),
        t("concept", "Concept art", "Illustration",
            "digital concept art of {subject}, matte painting, atmospheric perspective, trending artstation style, highly detailed",
            "lowres, blurry, text, watermark"),
        t("watercolor", "Watercolor", "Illustration",
            "delicate watercolor painting of {subject}, soft washes, paper texture, loose brush strokes, white background",
            "photo, 3d, harsh lines, oversaturated"),
        t("isometric", "Isometric diorama", "3D",
            "isometric 3D diorama of {subject}, tilt-shift, soft global illumination, clay render, cute miniature",
            "lowres, noisy, text"),
        t("pixel", "Pixel art", "Stylized",
            "pixel art of {subject}, 16-bit, limited palette, crisp pixels, retro game style",
            "blurry, smooth gradients, photo, 3d"),
        t("dark-fantasy", "Dark fantasy", "Stylized",
            "dark fantasy illustration of {subject}, gothic atmosphere, dramatic chiaroscuro, intricate details, ominous mood",
            "bright, cheerful, lowres, watermark"),
        t("architecture", "Architectural visualization", "Commercial",
            "architectural visualization of {subject}, golden hour, photoreal materials, wide angle, magazine quality",
            "distorted lines, lowres, people, clutter"),
    )

    private fun t(id: String, name: String, category: String, prompt: String, negative: String) =
        PromptTemplate("builtin-$id", name, category, prompt, negative, builtIn = true)
}

/**
 * Builds AUTOMATIC1111-style "parameters" infotext so exported PNGs are readable by PNG-info tools,
 * Civitai and other UIs.
 */
object InfotextBuilder {
    fun build(params: GenerationParameters, seed: Long?, modelName: String?, providerName: String, appVersion: String): String {
        val prompt = buildString {
            append(params.prompt)
            val tags = params.loras.filter { it.enabled }.mapNotNull { sel ->
                val name = sel.backendName ?: sel.name
                if (params.prompt.contains("<lora:$name:", ignoreCase = true)) null
                else "<lora:${name.substringAfterLast('/').substringBeforeLast('.')}:${fmt(sel.strength)}>"
            }
            if (tags.isNotEmpty()) append(" ").append(tags.joinToString(" "))
        }
        val fields = buildList {
            add("Steps: ${params.steps}")
            params.sampler?.let { add("Sampler: $it") }
            params.scheduler?.let { add("Schedule type: $it") }
            add("CFG scale: ${fmt(params.cfgScale)}")
            if (params.architecture.usesDistilledGuidance) add("Distilled CFG Scale: ${fmt(params.distilledGuidance)}")
            seed?.let { add("Seed: $it") }
            add("Size: ${params.width}x${params.height}")
            modelName?.let { add("Model: ${it.substringAfterLast('/').substringBeforeLast('.')}") }
            if (params.clipSkip > 1) add("Clip skip: ${params.clipSkip}")
            if (params.initImagePath != null) add("Denoising strength: ${fmt(params.denoisingStrength)}")
            if (params.hiresFix.enabled) {
                add("Hires upscale: ${fmt(params.hiresFix.scale)}")
                params.hiresFix.upscaler?.let { add("Hires upscaler: $it") }
                add("Denoising strength: ${fmt(params.hiresFix.denoise)}")
            }
            add("Provider: $providerName")
            add("Version: Unfiltered AI Studio $appVersion")
        }
        return buildString {
            append(prompt)
            if (params.negativePrompt.isNotBlank()) append("\nNegative prompt: ").append(params.negativePrompt)
            append("\n").append(fields.joinToString(", "))
        }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}
