package com.unfiltered.studio.data.providers.comfyui

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.JsonTemplate
import com.unfiltered.studio.core.longValueOrNull
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.core.toJsonElement
import com.unfiltered.studio.domain.models.ArchitectureFamily
import com.unfiltered.studio.domain.models.ComfyTemplate
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.ModelArchitecture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reference to output [output] of node [node] — serialized as `["node", output]`. */
data class NodeRef(val node: String, val output: Int) {
    fun toJson(): JsonArray = JsonArray(listOf(JsonPrimitive(node), JsonPrimitive(output)))

    companion object {
        fun from(element: JsonElement?): NodeRef? {
            val array = element as? JsonArray ?: return null
            if (array.size != 2) return null
            val node = array[0].stringOrNull ?: return null
            val output = array[1].longValueOrNull?.toInt() ?: return null
            return NodeRef(node, output)
        }
    }
}

/** Mutable API-format graph: `{ id: { class_type, inputs, _meta? } }`. */
class ComfyGraph(initial: Map<String, Node> = emptyMap()) {
    class Node(val classType: String, val inputs: MutableMap<String, JsonElement>, val meta: JsonElement? = null)

    val nodes: LinkedHashMap<String, Node> = LinkedHashMap(initial)
    private var nextId = (initial.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1

    fun add(classType: String, vararg inputs: Pair<String, Any?>): String {
        while (nodes.containsKey(nextId.toString())) nextId++
        val id = (nextId++).toString()
        nodes[id] = Node(classType, inputs.associate { (k, v) -> k to valueOf(v) }.toMutableMap())
        return id
    }

    fun set(nodeId: String, input: String, value: Any?) {
        nodes[nodeId]?.inputs?.set(input, valueOf(value))
    }

    fun ref(nodeId: String, input: String): NodeRef? = NodeRef.from(nodes[nodeId]?.inputs?.get(input))

    fun ofClass(vararg classTypes: String): List<String> = nodes.filter { it.value.classType in classTypes }.keys.toList()

    /** Replaces every link to [from] with [to], except inside [exclude] nodes. */
    fun rewire(from: NodeRef, to: NodeRef, exclude: Set<String>) {
        for ((id, node) in nodes) {
            if (id in exclude) continue
            for ((key, value) in node.inputs.entries.toList()) {
                if (NodeRef.from(value) == from) node.inputs[key] = to.toJson()
            }
        }
    }

    fun toJson(): JsonObject = JsonObject(
        nodes.mapValues { (_, node) ->
            JsonObject(
                buildMap {
                    put("class_type", JsonPrimitive(node.classType))
                    put("inputs", JsonObject(node.inputs))
                    node.meta?.let { put("_meta", it) }
                },
            )
        },
    )

    private fun valueOf(v: Any?): JsonElement = when (v) {
        is NodeRef -> v.toJson()
        else -> v.toJsonElement()
    }

    companion object {
        fun parse(json: JsonObject): ComfyGraph {
            val nodes = LinkedHashMap<String, Node>()
            for ((id, value) in json) {
                val obj = value as? JsonObject ?: throw invalid("Node $id is not an object")
                val classType = obj["class_type"]?.stringOrNull ?: throw invalid("Node $id has no class_type")
                val inputs = (obj["inputs"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                nodes[id] = Node(classType, inputs, obj["_meta"])
            }
            return ComfyGraph(nodes)
        }

        private fun invalid(reason: String) = AppError.Configuration("The custom workflow isn't in ComfyUI API format: $reason.")
    }
}

/** What a custom workflow lets the app control. Drives which settings are shown. */
data class WorkflowAnalysis(
    val positiveText: Boolean,
    val negativeText: Boolean,
    val size: Boolean,
    val batch: Boolean,
    val seed: Boolean,
    val steps: Boolean,
    val cfg: Boolean,
    val sampler: Boolean,
    val scheduler: Boolean,
    val guidance: Boolean,
    val checkpoint: Boolean,
    val unet: Boolean,
    val loraInjection: Boolean,
    val clipInjection: Boolean,
    val vae: Boolean,
    val img2img: Boolean,
    val outputs: Int,
    val placeholders: Set<String>,
    val problems: List<String>,
)

object ComfyWorkflowBuilder {
    const val SLOT_UNET = "unet"
    const val SLOT_WEIGHT_DTYPE = "weight_dtype"
    const val SLOT_CLIP1 = "clip1"
    const val SLOT_CLIP2 = "clip2"
    const val SLOT_VAE_FILE = "vae_file"

    private val SAMPLERS = setOf("KSampler", "KSamplerAdvanced", "SamplerCustom", "SamplerCustomAdvanced")
    private val CHECKPOINT_LOADERS = setOf("CheckpointLoaderSimple", "CheckpointLoader", "ImageOnlyCheckpointLoader", "unCLIPCheckpointLoader")
    private val UNET_LOADERS = setOf("UNETLoader", "UnetLoaderGGUF", "UnetLoaderGGUFAdvanced")
    private val TEXT_FIELDS = listOf("text", "text_g", "text_l", "clip_l", "t5xxl", "clip_g", "prompt")
    private val OUTPUT_HINTS = listOf("SaveImage", "PreviewImage", "Image Save", "SaveImageWebsocket", "SaveAnimatedWEBP")

    data class Built(val prompt: JsonObject, val warnings: List<String>)

    fun resolveTemplate(template: ComfyTemplate, architecture: ModelArchitecture): ComfyTemplate = when (template) {
        ComfyTemplate.AUTO -> when (architecture.family) {
            ArchitectureFamily.SD3 -> ComfyTemplate.SD3_CHECKPOINT
            ArchitectureFamily.FLUX1 -> ComfyTemplate.FLUX_CHECKPOINT
            else -> ComfyTemplate.CHECKPOINT
        }
        else -> template
    }

    // -----------------------------------------------------------------------------------------
    // Built-in templates
    // -----------------------------------------------------------------------------------------

    fun buildBuiltIn(template: ComfyTemplate, p: GenerationParameters, initImage: String?, filenamePrefix: String): Built {
        val g = ComfyGraph()
        val warnings = mutableListOf<String>()
        val isFlux = template == ComfyTemplate.FLUX_CHECKPOINT || template == ComfyTemplate.FLUX_SPLIT

        var model: NodeRef
        var clip: NodeRef
        var vae: NodeRef
        if (template == ComfyTemplate.FLUX_SPLIT) {
            val unet = g.add("UNETLoader", "unet_name" to p.components[SLOT_UNET].orEmpty(), "weight_dtype" to (p.components[SLOT_WEIGHT_DTYPE] ?: "default"))
            val clipLoader = g.add("DualCLIPLoader", "clip_name1" to p.components[SLOT_CLIP1].orEmpty(), "clip_name2" to p.components[SLOT_CLIP2].orEmpty(), "type" to "flux")
            val vaeLoader = g.add("VAELoader", "vae_name" to (p.components[SLOT_VAE_FILE] ?: p.vae).orEmpty())
            model = NodeRef(unet, 0)
            clip = NodeRef(clipLoader, 0)
            vae = NodeRef(vaeLoader, 0)
        } else {
            val ckpt = g.add("CheckpointLoaderSimple", "ckpt_name" to p.checkpoint.orEmpty())
            model = NodeRef(ckpt, 0)
            clip = NodeRef(ckpt, 1)
            vae = NodeRef(ckpt, 2)
        }

        for (lora in p.loras.filter { it.enabled && !it.backendName.isNullOrBlank() }) {
            val node = g.add(
                "LoraLoader",
                "model" to model, "clip" to clip,
                "lora_name" to lora.backendName,
                "strength_model" to lora.strength,
                "strength_clip" to (lora.clipStrength ?: lora.strength),
            )
            model = NodeRef(node, 0)
            clip = NodeRef(node, 1)
        }

        if (p.clipSkip > 1 && template == ComfyTemplate.CHECKPOINT) {
            clip = NodeRef(g.add("CLIPSetLastLayer", "clip" to clip, "stop_at_clip_layer" to -p.clipSkip), 0)
        }
        if (template != ComfyTemplate.FLUX_SPLIT && !p.vae.isNullOrBlank()) {
            vae = NodeRef(g.add("VAELoader", "vae_name" to p.vae), 0)
        }

        val positiveText = g.add("CLIPTextEncode", "text" to p.prompt, "clip" to clip)
        val positive = if (isFlux) NodeRef(g.add("FluxGuidance", "conditioning" to NodeRef(positiveText, 0), "guidance" to p.distilledGuidance), 0)
        else NodeRef(positiveText, 0)
        val negative = NodeRef(g.add("CLIPTextEncode", "text" to p.negativePrompt, "clip" to clip), 0)

        val latent = if (initImage != null) {
            val load = g.add("LoadImage", "image" to initImage)
            val encoded = NodeRef(g.add("VAEEncode", "pixels" to NodeRef(load, 0), "vae" to vae), 0)
            if (p.batchSize > 1) NodeRef(g.add("RepeatLatentBatch", "samples" to encoded, "amount" to p.batchSize), 0) else encoded
        } else {
            val latentClass = if (template == ComfyTemplate.CHECKPOINT) "EmptyLatentImage" else "EmptySD3LatentImage"
            NodeRef(g.add(latentClass, "width" to p.width, "height" to p.height, "batch_size" to p.batchSize), 0)
        }

        val sampler = p.sampler?.ifBlank { null } ?: "euler"
        val scheduler = p.scheduler?.ifBlank { null } ?: if (isFlux || template == ComfyTemplate.SD3_CHECKPOINT) "simple" else "normal"
        var samples = NodeRef(
            g.add(
                "KSampler",
                "model" to model, "seed" to p.seed, "steps" to p.steps, "cfg" to p.cfgScale,
                "sampler_name" to sampler, "scheduler" to scheduler,
                "positive" to positive, "negative" to negative, "latent_image" to latent,
                "denoise" to if (initImage != null) p.denoisingStrength else 1.0f,
            ),
            0,
        )
        if (p.hiresFix.enabled && initImage == null) {
            val upscaled = g.add("LatentUpscaleBy", "samples" to samples, "upscale_method" to (p.hiresFix.upscaler ?: "nearest-exact"), "scale_by" to p.hiresFix.scale)
            samples = NodeRef(
                g.add(
                    "KSampler",
                    "model" to model, "seed" to p.seed, "steps" to (if (p.hiresFix.steps > 0) p.hiresFix.steps else p.steps),
                    "cfg" to p.cfgScale, "sampler_name" to sampler, "scheduler" to scheduler,
                    "positive" to positive, "negative" to negative, "latent_image" to NodeRef(upscaled, 0),
                    "denoise" to p.hiresFix.denoise,
                ),
                0,
            )
        }
        val decoded = g.add("VAEDecode", "samples" to samples, "vae" to vae)
        g.add("SaveImage", "images" to NodeRef(decoded, 0), "filename_prefix" to filenamePrefix)
        return Built(g.toJson(), warnings)
    }

    // -----------------------------------------------------------------------------------------
    // Custom workflows (exported with "Export (API)")
    // -----------------------------------------------------------------------------------------

    fun parseCustom(json: String): ComfyGraph {
        val root = try {
            AppJson.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw AppError.Configuration("The custom workflow is not valid JSON.")
        if (root["nodes"] is JsonArray && root.containsKey("links")) {
            throw AppError.Configuration(
                "This is a UI-format workflow. In ComfyUI use Workflow → Export (API) (or enable Dev mode → Save (API Format)) and paste that file.",
            )
        }
        val graphJson = (root["prompt"] as? JsonObject)?.takeIf { p -> p.values.all { it is JsonObject } } ?: root
        if (graphJson.isEmpty()) throw AppError.Configuration("The custom workflow has no nodes.")
        return ComfyGraph.parse(graphJson)
    }

    fun placeholderVars(p: GenerationParameters, initImage: String?): Map<String, Any?> = buildMap {
        put("positive_prompt", p.prompt)
        put("prompt", p.prompt)
        put("negative_prompt", p.negativePrompt)
        put("seed", p.seed)
        put("steps", p.steps)
        put("cfg", p.cfgScale)
        put("guidance", p.distilledGuidance)
        put("width", p.width)
        put("height", p.height)
        put("batch_size", p.batchSize)
        put("sampler", p.sampler ?: "euler")
        put("scheduler", p.scheduler ?: "normal")
        put("denoise", if (initImage != null) p.denoisingStrength else 1.0f)
        p.checkpoint?.let { put("checkpoint", it) }
        p.vae?.let { put("vae", it) }
        initImage?.let { put("init_image", it) }
        put("clip_skip", -p.clipSkip.coerceAtLeast(1))
        p.components.forEach { (k, v) -> put(k, v) }
    }

    fun analyze(json: String): WorkflowAnalysis {
        val graph = parseCustom(json)
        val placeholders = JsonTemplate.placeholdersIn(json)
        val t = Tracer(graph)
        val primary = t.primarySampler()
        val problems = mutableListOf<String>()
        if (primary == null) problems += "No sampler node (KSampler / SamplerCustomAdvanced) found — only placeholders will be applied."
        val outputs = graph.nodes.count { (_, n) -> OUTPUT_HINTS.any { n.classType.contains(it) } }
        if (outputs == 0) problems += "No SaveImage/PreviewImage node — the workflow produces no images the app can fetch."
        fun ph(vararg names: String) = names.any { it in placeholders }
        val latent = primary?.let { t.latentSource(it) }
        return WorkflowAnalysis(
            positiveText = ph("positive_prompt", "prompt") || (primary != null && t.textNode(primary, positive = true) != null),
            negativeText = ph("negative_prompt") || (primary != null && t.textNode(primary, positive = false) != null),
            size = ph("width", "height") || (latent != null && graph.nodes[latent]?.inputs?.containsKey("width") == true),
            batch = ph("batch_size") || (latent != null && graph.nodes[latent]?.inputs?.containsKey("batch_size") == true),
            seed = ph("seed") || t.seedTargets().isNotEmpty(),
            steps = ph("steps") || (primary != null && t.stepsTarget(primary) != null),
            cfg = ph("cfg") || (primary != null && t.cfgTarget(primary) != null),
            sampler = ph("sampler") || (primary != null && t.samplerTarget(primary) != null),
            scheduler = ph("scheduler") || (primary != null && t.schedulerTarget(primary) != null),
            guidance = ph("guidance") || graph.ofClass("FluxGuidance").isNotEmpty(),
            checkpoint = ph("checkpoint") || graph.ofClass(*CHECKPOINT_LOADERS.toTypedArray()).isNotEmpty(),
            unet = graph.ofClass(*UNET_LOADERS.toTypedArray()).isNotEmpty(),
            loraInjection = primary != null && t.modelSource(primary) != null,
            clipInjection = primary != null && t.clipSource(primary) != null,
            vae = ph("vae") || graph.ofClass("VAELoader").isNotEmpty(),
            img2img = ph("init_image") || graph.ofClass("LoadImage").isNotEmpty(),
            outputs = outputs,
            placeholders = placeholders,
            problems = problems,
        )
    }

    fun applyCustom(json: String, p: GenerationParameters, initImage: String?): Built {
        val warnings = mutableListOf<String>()
        val placeholders = JsonTemplate.placeholdersIn(json)
        val rendered = JsonTemplate.renderTree(parseCustom(json).toJson(), placeholderVars(p, initImage))
        rendered.unresolved.forEach { warnings += "Unknown placeholder {{$it}} left unchanged." }
        val graph = ComfyGraph.parse(rendered.json as JsonObject)
        val t = Tracer(graph)
        fun covered(vararg names: String) = names.any { it in placeholders }

        val primary = t.primarySampler()
        if (primary == null) {
            if (placeholders.isEmpty()) warnings += "No sampler node found and no placeholders used — the workflow runs unchanged."
        } else {
            if (!covered("positive_prompt", "prompt")) {
                val node = t.textNode(primary, positive = true)
                if (node != null) t.setText(node, p.prompt) else warnings += "Couldn't locate the positive prompt node; add {{positive_prompt}} to the workflow."
            }
            if (!covered("negative_prompt")) {
                val pos = t.textNode(primary, positive = true)
                val neg = t.textNode(primary, positive = false)
                when {
                    neg == null -> if (p.negativePrompt.isNotBlank()) warnings += "The workflow has no negative prompt input; negative prompt ignored."
                    neg == pos -> warnings += "Positive and negative share one text node; negative prompt ignored."
                    else -> t.setText(neg, p.negativePrompt)
                }
            }
            if (!covered("seed")) t.seedTargets().forEach { (node, input) -> graph.set(node, input, p.seed) }
            if (!covered("steps")) t.stepsTarget(primary)?.let { (n, i) -> graph.set(n, i, p.steps) }
            if (!covered("cfg")) t.cfgTarget(primary)?.let { (n, i) -> graph.set(n, i, p.cfgScale) }
            if (!covered("sampler")) p.sampler?.ifBlank { null }?.let { s -> t.samplerTarget(primary)?.let { (n, i) -> graph.set(n, i, s) } }
            if (!covered("scheduler")) p.scheduler?.ifBlank { null }?.let { s -> t.schedulerTarget(primary)?.let { (n, i) -> graph.set(n, i, s) } }
            if (!covered("width", "height", "batch_size")) {
                val latent = t.latentSource(primary)
                val inputs = latent?.let { graph.nodes[it]?.inputs }
                if (inputs != null && inputs.containsKey("width")) {
                    graph.set(latent, "width", p.width)
                    graph.set(latent, "height", p.height)
                    if (inputs.containsKey("batch_size")) graph.set(latent, "batch_size", p.batchSize)
                }
            }
            if (!covered("denoise") && initImage != null) graph.set(primary, "denoise", p.denoisingStrength)
        }
        if (!covered("guidance")) graph.ofClass("FluxGuidance").forEach { graph.set(it, "guidance", p.distilledGuidance) }
        if (!covered("checkpoint") && !p.checkpoint.isNullOrBlank()) {
            graph.ofClass(*CHECKPOINT_LOADERS.toTypedArray()).firstOrNull()?.let { graph.set(it, "ckpt_name", p.checkpoint) }
        }
        p.components[SLOT_UNET]?.ifBlank { null }?.let { unet -> graph.ofClass(*UNET_LOADERS.toTypedArray()).firstOrNull()?.let { graph.set(it, "unet_name", unet) } }
        if (!covered("vae") && !p.vae.isNullOrBlank()) {
            val loader = graph.ofClass("VAELoader").firstOrNull()
            if (loader != null) graph.set(loader, "vae_name", p.vae) else warnings += "The workflow has no VAELoader; VAE override ignored."
        }
        if (!covered("init_image") && initImage != null) {
            val load = graph.ofClass("LoadImage").firstOrNull()
            if (load != null) graph.set(load, "image", initImage) else warnings += "The workflow has no LoadImage node; init image ignored."
        }

        val loras = p.loras.filter { it.enabled && !it.backendName.isNullOrBlank() }
        if (loras.isNotEmpty() || p.clipSkip > 1) {
            val modelSource = primary?.let { t.modelSource(it) }
            val clipSource = primary?.let { t.clipSource(it) }
            if (loras.isNotEmpty() && modelSource == null) {
                warnings += "Couldn't find where the model is loaded; LoRAs were not applied."
            } else if (modelSource != null) {
                var model = modelSource
                var clip = clipSource
                val inserted = mutableSetOf<String>()
                for (lora in loras) {
                    val id = if (clip != null) {
                        graph.add(
                            "LoraLoader", "model" to model, "clip" to clip, "lora_name" to lora.backendName,
                            "strength_model" to lora.strength, "strength_clip" to (lora.clipStrength ?: lora.strength),
                        )
                    } else {
                        graph.add("LoraLoaderModelOnly", "model" to model, "lora_name" to lora.backendName, "strength_model" to lora.strength)
                    }
                    inserted += id
                    model = NodeRef(id, 0)
                    if (clip != null) clip = NodeRef(id, 1)
                }
                if (p.clipSkip > 1 && clip != null && p.architecture.supportsClipSkip) {
                    val existing = graph.ofClass("CLIPSetLastLayer").firstOrNull()
                    if (existing != null) {
                        graph.set(existing, "stop_at_clip_layer", -p.clipSkip)
                    } else {
                        val id = graph.add("CLIPSetLastLayer", "clip" to clip, "stop_at_clip_layer" to -p.clipSkip)
                        inserted += id
                        clip = NodeRef(id, 0)
                    }
                }
                if (model != modelSource) graph.rewire(modelSource, model, inserted)
                if (clipSource != null && clip != null && clip != clipSource) graph.rewire(clipSource, clip, inserted)
                if (loras.isNotEmpty() && clipSource == null) warnings += "No CLIP source found; LoRAs applied to the model only."
            }
        }
        return Built(graph.toJson(), warnings)
    }

    /** Graph traversal helpers for locating the nodes a parameter should be written to. */
    private class Tracer(val g: ComfyGraph) {

        fun primarySampler(): String? {
            val samplers = g.nodes.filter { it.value.classType in SAMPLERS }.keys
            if (samplers.isEmpty()) return null
            // First-pass sampler: its latent doesn't come from another sampler (hires second passes do).
            return samplers.firstOrNull { id -> !latentChainContainsSampler(id) } ?: samplers.first()
        }

        private fun latentChainContainsSampler(id: String): Boolean {
            val visited = mutableSetOf<String>()
            fun walk(ref: NodeRef?, depth: Int): Boolean {
                if (ref == null || depth > 6 || !visited.add(ref.node)) return false
                val node = g.nodes[ref.node] ?: return false
                if (node.classType in SAMPLERS) return true
                return node.inputs.values.any { walk(NodeRef.from(it), depth + 1) }
            }
            return walk(g.ref(id, "latent_image"), 0)
        }

        private fun guider(sampler: String): String? = if (g.nodes[sampler]?.classType == "SamplerCustomAdvanced") g.ref(sampler, "guider")?.node else null

        fun textNode(sampler: String, positive: Boolean): String? {
            val owner = guider(sampler) ?: sampler
            val start = if (positive) g.ref(owner, "positive") ?: g.ref(owner, "conditioning") else g.ref(owner, "negative")
            return findUpstream(start, maxDepth = 8) { node -> TEXT_FIELDS.any { node.inputs.containsKey(it) } && node.classType != "ConditioningZeroOut" }
        }

        fun setText(nodeId: String, text: String) {
            val node = g.nodes[nodeId] ?: return
            for (field in TEXT_FIELDS) {
                val value = node.inputs[field] ?: continue
                val link = NodeRef.from(value)
                if (link == null) {
                    g.set(nodeId, field, text)
                } else {
                    // Text comes from a primitive/string node: write into that node instead.
                    val source = g.nodes[link.node] ?: continue
                    val key = listOf("value", "string", "text", "Text").firstOrNull { source.inputs[it] is JsonPrimitive }
                    if (key != null) g.set(link.node, key, text)
                }
            }
        }

        fun seedTargets(): List<Pair<String, String>> = g.nodes.flatMap { (id, node) ->
            listOf("seed", "noise_seed").filter { key -> node.inputs[key] is JsonPrimitive && (node.classType in SAMPLERS || node.classType == "RandomNoise") }
                .map { id to it }
        }

        fun stepsTarget(sampler: String): Pair<String, String>? {
            if (g.nodes[sampler]?.inputs?.get("steps") is JsonPrimitive) return sampler to "steps"
            val sigmas = g.ref(sampler, "sigmas")?.node ?: return null
            return if (g.nodes[sigmas]?.inputs?.get("steps") is JsonPrimitive) sigmas to "steps" else null
        }

        fun schedulerTarget(sampler: String): Pair<String, String>? {
            if (g.nodes[sampler]?.inputs?.get("scheduler") is JsonPrimitive) return sampler to "scheduler"
            val sigmas = g.ref(sampler, "sigmas")?.node ?: return null
            return if (g.nodes[sigmas]?.inputs?.get("scheduler") is JsonPrimitive) sigmas to "scheduler" else null
        }

        fun samplerTarget(sampler: String): Pair<String, String>? {
            if (g.nodes[sampler]?.inputs?.get("sampler_name") is JsonPrimitive) return sampler to "sampler_name"
            val select = g.ref(sampler, "sampler")?.node ?: return null
            return if (g.nodes[select]?.inputs?.get("sampler_name") is JsonPrimitive) select to "sampler_name" else null
        }

        fun cfgTarget(sampler: String): Pair<String, String>? {
            if (g.nodes[sampler]?.inputs?.get("cfg") is JsonPrimitive) return sampler to "cfg"
            val guider = guider(sampler) ?: return null
            return if (g.nodes[guider]?.inputs?.get("cfg") is JsonPrimitive) guider to "cfg" else null
        }

        fun latentSource(sampler: String): String? =
            findUpstream(g.ref(sampler, "latent_image"), maxDepth = 4) { it.inputs.containsKey("width") && it.inputs.containsKey("height") }

        /** The loader output feeding MODEL into the sampler chain (the link LoRAs are spliced after). */
        fun modelSource(sampler: String): NodeRef? {
            val owner = guider(sampler) ?: sampler
            var ref = g.ref(owner, "model") ?: return null
            val visited = mutableSetOf<String>()
            while (visited.add(ref.node)) {
                val upstream = g.ref(ref.node, "model") ?: return ref
                ref = upstream
            }
            return ref
        }

        fun clipSource(sampler: String): NodeRef? {
            val text = textNode(sampler, positive = true) ?: return null
            var ref = g.ref(text, "clip") ?: return null
            val visited = mutableSetOf<String>()
            while (visited.add(ref.node)) {
                val upstream = g.ref(ref.node, "clip") ?: return ref
                ref = upstream
            }
            return ref
        }

        private fun findUpstream(start: NodeRef?, maxDepth: Int, match: (ComfyGraph.Node) -> Boolean): String? {
            if (start == null) return null
            val queue = ArrayDeque(listOf(start.node to 0))
            val visited = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val (id, depth) = queue.removeFirst()
                if (!visited.add(id)) continue
                val node = g.nodes[id] ?: continue
                if (match(node)) return id
                if (depth >= maxDepth) continue
                node.inputs.forEach { (key, value) ->
                    if (key == "clip" || key == "model" || key == "vae") return@forEach
                    NodeRef.from(value)?.let { queue.addLast(it.node to depth + 1) }
                }
            }
            return null
        }
    }
}
