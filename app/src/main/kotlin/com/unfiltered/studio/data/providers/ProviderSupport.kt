package com.unfiltered.studio.data.providers

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.data.network.HttpExecutor
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.LoraSelection
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import dagger.MapKey
import okhttp3.Request
import java.util.Base64
import java.util.Locale

/** Creates an adapter for one provider type. Registered in a Hilt map keyed by [ProviderTypeKey]. */
interface ProviderAdapterFactory {
    fun create(config: ProviderConfig, http: ProviderHttp): ImageGenerationProvider
}

@MapKey
annotation class ProviderTypeKey(val value: ProviderType)

object ImagePayloads {
    private val DATA_URI = Regex("""^data:([\w/+.-]+)?(;base64)?,""")

    fun isUrl(value: String) = value.startsWith("http://") || value.startsWith("https://")

    /** Decodes plain base64 or a data URI; returns bytes and a mime type guess. */
    fun decode(value: String): Pair<ByteArray, String> {
        val trimmed = value.trim()
        val match = DATA_URI.find(trimmed)
        val payload = if (match != null) trimmed.substring(match.range.last + 1) else trimmed
        val bytes = try {
            Base64.getMimeDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw AppError.Parse("the server", "Image data is not valid base64", e)
        }
        val mime = ImageStorage.sniffMime(bytes) ?: match?.groupValues?.get(1)?.ifBlank { null } ?: "image/png"
        return bytes to mime
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun dataUri(bytes: ByteArray): String = "data:${ImageStorage.sniffMime(bytes) ?: "image/png"};base64,${encode(bytes)}"

    /**
     * Resolves an image reference from a response: URL (downloaded — with credentials only when it
     * points at the provider's own host), data URI, or raw base64.
     */
    suspend fun resolve(value: String, http: ProviderHttp, providerHost: String?): Pair<ByteArray, String> {
        if (!isUrl(value)) return decode(value)
        val host = runCatching { java.net.URI(value).host }.getOrNull()
        val executor: HttpExecutor = if (host != null && host.equals(providerHost, ignoreCase = true)) http.api else http.anonymous
        val (bytes, type) = executor.bytes(Request.Builder().url(value).get().build())
        val mime = ImageStorage.sniffMime(bytes) ?: type?.substringBefore(';') ?: "image/png"
        if (!mime.startsWith("image/")) throw AppError.Parse("the image host", "Expected an image but got $mime")
        return bytes to mime
    }
}

object LoraSyntax {
    /** `<lora:name:0.75>` tags for AUTOMATIC1111/Forge, skipping LoRAs already written in the prompt. */
    fun appendTags(prompt: String, loras: List<LoraSelection>): String {
        val tags = loras.filter { it.enabled && !it.backendName.isNullOrBlank() }
            .filterNot { prompt.contains("<lora:${it.backendName}:", ignoreCase = true) }
            .joinToString(" ") { "<lora:${it.backendName}:${format(it.strength)}>" }
        return if (tags.isEmpty()) prompt else "${prompt.trimEnd()} $tags"
    }

    fun format(value: Float): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
