package com.unfiltered.studio.data.storage

import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.domain.models.ModelArchitecture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the JSON header of a .safetensors file without loading the tensors. */
object SafetensorsReader {
    data class Header(val metadata: Map<String, String>, val tensorCount: Int)

    private const val MAX_HEADER = 100L * 1024 * 1024

    fun readHeader(input: InputStream): Header {
        val lengthBytes = readFully(input, 8)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).long
        if (length <= 1 || length > MAX_HEADER) throw IOException("Not a safetensors file (header length $length)")
        val header = readFully(input, length.toInt())
        val json = AppJson.parseToJsonElement(String(header, Charsets.UTF_8)) as? JsonObject
            ?: throw IOException("Invalid safetensors header")
        val metadata = (json["__metadata__"] as? JsonObject)?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.stringOrNull ?: v.toString()
        }.orEmpty()
        return Header(metadata, json.keys.count { it != "__metadata__" })
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) throw IOException("Unexpected end of file")
            read += n
        }
        return buffer
    }
}

/**
 * Extracts useful library metadata from kohya-ss / ModelSpec safetensors metadata
 * (`ss_*` and `modelspec.*` keys), as returned by A1111 `/sdapi/v1/loras` or ComfyUI
 * `/view_metadata/loras`.
 */
object LoraMetadataParser {

    data class Parsed(
        val title: String?,
        val architecture: ModelArchitecture?,
        val baseModelLabel: String?,
        val triggerWords: List<String>,
        val description: String?,
        val thumbnailDataUri: String?,
        val tags: List<String>,
    )

    fun parse(meta: Map<String, String>): Parsed {
        val archSource = meta["modelspec.architecture"] ?: meta["ss_base_model_version"]
        val architecture = archSource?.let { detect(it) } ?: meta["ss_sd_model_name"]?.let { ModelArchitecture.detect(it) }
        val explicitTrigger = meta["modelspec.trigger_phrase"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
        return Parsed(
            title = meta["modelspec.title"]?.ifBlank { null } ?: meta["ss_output_name"]?.ifBlank { null },
            architecture = architecture,
            baseModelLabel = archSource,
            triggerWords = explicitTrigger ?: triggerWordsFromTags(meta["ss_tag_frequency"]),
            description = meta["modelspec.description"]?.ifBlank { null },
            thumbnailDataUri = meta["modelspec.thumbnail"]?.takeIf { it.startsWith("data:image") },
            tags = meta["modelspec.tags"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    private fun detect(value: String): ModelArchitecture? {
        val v = value.lowercase()
        return when {
            "xl" in v -> if ("pony" in v) ModelArchitecture.PONY else ModelArchitecture.SDXL
            v.startsWith("sd_v1") || "stable-diffusion-v1" in v -> ModelArchitecture.SD15
            v.startsWith("sd_v2") || "stable-diffusion-v2" in v -> ModelArchitecture.SD2
            "sd3" in v || "stable-diffusion-v3" in v || "stable-diffusion-3" in v -> ModelArchitecture.SD3
            else -> ModelArchitecture.detect(v)
        }
    }

    /**
     * kohya dataset folders are named "{repeats}_{trigger} {class}", so the folder name is the most
     * reliable trigger. Falls back to the three most frequent caption tags.
     */
    internal fun triggerWordsFromTags(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { AppJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val fromFolders = root.keys.mapNotNull { key ->
            Regex("""^\d+_(.+)$""").find(key)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }
        val counts = mutableMapOf<String, Long>()
        root.values.forEach { folder ->
            (folder as? JsonObject)?.forEach { (tag, count) ->
                val n = (count as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                counts[tag.trim()] = (counts[tag.trim()] ?: 0L) + n
            }
        }
        val topTags = counts.entries.sortedByDescending { it.value }.take(3).map { it.key }
        return (fromFolders + topTags).filter { it.isNotBlank() }.distinct().take(5)
    }
}
