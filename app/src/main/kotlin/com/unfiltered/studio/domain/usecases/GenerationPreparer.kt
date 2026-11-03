package com.unfiltered.studio.domain.usecases

import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.CheckpointSelection
import com.unfiltered.studio.domain.models.Compatibility
import com.unfiltered.studio.domain.models.CompatibilityRules
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraSelection
import com.unfiltered.studio.domain.models.LoraSupport
import com.unfiltered.studio.domain.models.PreparedGeneration
import com.unfiltered.studio.domain.models.ProviderCapabilities
import com.unfiltered.studio.domain.models.ProviderConfig
import kotlin.random.Random

/**
 * Turns what the user configured into parameters the target provider can execute:
 * clamps values to the provider's capabilities, resolves random seeds, maps library LoRAs to the
 * identifiers the backend uses and collects human-readable warnings. Pure and unit-tested.
 */
class GenerationPreparer(private val random: Random = Random.Default) {

    fun prepare(
        provider: ProviderConfig,
        caps: ProviderCapabilities,
        catalog: BackendCatalog?,
        input: GenerationParameters,
        stack: List<ActiveLora>,
    ): PreparedGeneration {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var p = input.copy(prompt = input.prompt.trim(), negativePrompt = input.negativePrompt.trim())

        if (p.prompt.isBlank()) errors += "Enter a prompt."

        caps.size?.let { c ->
            val w = c.clamp(p.width)
            val h = c.clamp(p.height)
            if (w != p.width || h != p.height) warnings += "Size adjusted to ${w}×$h (multiple of ${c.multipleOf}, ${c.min}–${c.max})."
            p = p.copy(width = w, height = h)
        }
        caps.steps?.let { range -> p = p.copy(steps = p.steps.coerceIn(range)) }
        caps.cfg?.let { range -> p = p.copy(cfgScale = p.cfgScale.coerceIn(range.start, range.endInclusive)) }
        p = p.copy(batchSize = p.batchSize.coerceIn(1, caps.batch.max.coerceAtLeast(1)))

        p = if (caps.seed) {
            if (p.seed < 0) p.copy(seed = random.nextLong(0, MAX_SEED)) else p.copy(seed = p.seed.coerceAtMost(MAX_SEED))
        } else {
            p.copy(seed = -1)
        }

        when (val selection = caps.checkpointSelection) {
            is CheckpointSelection.FromList -> {
                val ckpt = p.checkpoint
                if (ckpt.isNullOrBlank()) {
                    if (!selection.allowEmpty) errors += "Select a checkpoint."
                } else if (selection.options.isNotEmpty() && ckpt !in selection.options) {
                    warnings += "Checkpoint \"${ckpt.substringAfterLast('/')}\" is not in the server's current list. Refresh the model list if it was renamed."
                }
            }
            is CheckpointSelection.FreeText -> if (p.checkpoint.isNullOrBlank()) errors += "Enter a model id (${selection.hint})."
            CheckpointSelection.None -> Unit
        }

        for (slot in caps.components) {
            val value = p.components[slot.id]
            if (slot.required && value.isNullOrBlank()) errors += "Select ${slot.label}."
        }
        if (caps.components.isEmpty() && p.components.isNotEmpty()) p = p.copy(components = emptyMap())

        if (p.initImagePath != null && !caps.img2img) {
            warnings += "${provider.name} can't do image-to-image with this model; the init image is ignored."
            p = p.copy(initImagePath = null)
        }
        if (p.hiresFix.enabled && caps.hiresFix == null) {
            warnings += "Hires fix isn't available on ${provider.name}; disabled."
            p = p.copy(hiresFix = p.hiresFix.copy(enabled = false))
        }
        if (!caps.clipSkip) p = p.copy(clipSkip = 1)
        if (!caps.negativePrompt && p.negativePrompt.isNotBlank()) {
            warnings += "The negative prompt isn't used by ${provider.name} with this model."
        }
        if (caps.vaes == null) p = p.copy(vae = null)

        val selections = resolveLoras(provider, caps, catalog, stack.filter { it.enabled }, warnings)
        for (sel in selections) {
            val compat = CompatibilityRules.loraOnBase(sel.architecture, p.architecture)
            if (compat == Compatibility.INCOMPATIBLE || compat == Compatibility.PARTIAL) {
                CompatibilityRules.describe(compat, sel.architecture, p.architecture)?.let { warnings += "${sel.name}: $it" }
            }
        }

        if (p.autoTriggerWords && selections.isNotEmpty()) {
            val missing = selections.flatMap { it.triggerWords }
                .map { it.trim().trim(',') }
                .filter { it.isNotBlank() && !p.prompt.contains(it, ignoreCase = true) }
                .distinct()
            if (missing.isNotEmpty()) p = p.copy(prompt = p.prompt.trimEnd().trimEnd(',') + ", " + missing.joinToString(", "))
        }

        p = p.copy(loras = selections)
        return PreparedGeneration(p, warnings.distinct(), errors.distinct())
    }

    private fun resolveLoras(
        provider: ProviderConfig,
        caps: ProviderCapabilities,
        catalog: BackendCatalog?,
        enabled: List<ActiveLora>,
        warnings: MutableList<String>,
    ): List<LoraSelection> {
        if (enabled.isEmpty()) return emptyList()
        return when (val support = caps.loras) {
            LoraSupport.None -> {
                warnings += "${provider.name} can't apply LoRAs with the current setup; ${enabled.size} active LoRA(s) ignored."
                emptyList()
            }
            LoraSupport.PromptSyntax, LoraSupport.ComfyNodes -> enabled.mapNotNull { active ->
                val backendName = active.lora.backendLinks[provider.id] ?: matchOnServer(active.lora, catalog)
                if (backendName == null) {
                    warnings += "LoRA \"${active.lora.name}\" isn't installed on ${provider.name} — skipped. Copy the file to the server's LoRA folder, then sync."
                    null
                } else {
                    active.toSelection(backendName)
                }
            }
            is LoraSupport.RemoteWeights -> {
                val usable = enabled.filter { it.lora.hasRemoteWeights }
                enabled.filterNot { it.lora.hasRemoteWeights }.forEach {
                    warnings += "LoRA \"${it.lora.name}\" has no Hugging Face weights file; cloud providers can only load LoRAs from Hugging Face — skipped."
                }
                if (usable.size > support.maxCount) {
                    warnings += "${provider.name} accepts at most ${support.maxCount} LoRA(s) for this model; extra LoRAs skipped."
                }
                usable.take(support.maxCount).map { it.toSelection(null) }
            }
            LoraSupport.TemplateVariable -> enabled.map { active ->
                active.toSelection(active.lora.backendLinks[provider.id] ?: active.lora.fileName?.substringBeforeLast('.') ?: active.lora.name)
            }
        }
    }

    private fun ActiveLora.toSelection(backendName: String?) = LoraSelection(
        loraId = lora.id,
        name = lora.name,
        backendName = backendName,
        strength = strength,
        clipStrength = clipStrength,
        enabled = true,
        triggerWords = lora.triggerWords,
        architecture = lora.architecture,
        hfRepoId = lora.hfRepoId,
        hfWeightFile = lora.hfWeightFile,
        remoteUrl = lora.downloadUrl,
    )

    companion object {
        /** Seeds are kept within 32 bits: every supported backend accepts that range. */
        const val MAX_SEED = 4_294_967_295L

        /** Finds a server LoRA whose filename matches the library entry (used before an explicit link exists). */
        fun matchOnServer(lora: Lora, catalog: BackendCatalog?): String? {
            val loras = catalog?.loras ?: return null
            val stems = listOfNotNull(lora.fileName, lora.name).map { stem(it) }.toSet()
            return loras.firstOrNull { stem(it.name) in stems || (it.path != null && stem(it.path) in stems) }?.name
        }

        fun stem(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').lowercase()
    }
}
