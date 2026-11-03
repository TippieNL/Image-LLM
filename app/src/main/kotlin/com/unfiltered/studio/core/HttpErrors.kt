package com.unfiltered.studio.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts non-2xx HTTP responses from heterogeneous backends into [AppError]s. Each backend uses a
 * different error envelope (FastAPI `detail`, HF `error`, OpenAI `error.message`, ComfyUI
 * `error.message` + `node_errors`), so the message extraction is deliberately permissive.
 */
object HttpErrors {

    fun toAppError(
        code: Int,
        body: String?,
        service: String,
        retryAfterHeader: String? = null,
        what: String = "Endpoint",
    ): AppError {
        val message = extractMessage(body)
        val lower = (body ?: "").lowercase()
        return when (code) {
            400 -> AppError.BadRequest(service, message)
            401 -> AppError.Unauthorized(service, message)
            402 -> AppError.PaymentRequired(service, message)
            403 -> AppError.Forbidden(service, message)
            404 -> AppError.NotFound(what, message)
            408, 504 -> AppError.Timeout("$service request")
            413 -> AppError.BadRequest(service, "Request too large${message?.let { ": $it" } ?: ""}")
            422 -> AppError.Validation("$service rejected the parameters (422)", listOfNotNull(message))
            429 -> AppError.RateLimited(service, retryAfterHeader?.trim()?.toLongOrNull())
            503 -> if ("loading" in lower || "estimated_time" in lower || "initializ" in lower || "scaled to zero" in lower) {
                AppError.ModelLoading(service, extractEstimatedTime(body))
            } else {
                AppError.Server(code, service, message)
            }
            else -> AppError.Server(code, service, message)
        }
    }

    /** Best-effort human readable message from an error body (JSON or HTML or text). */
    fun extractMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val element = runCatching { AppJson.parseToJsonElement(trimmed) }.getOrNull()
            if (element != null) messageFrom(element)?.let { return it.take(MAX_LEN) }
        }
        if (trimmed.startsWith("<")) {
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
            val text = title ?: trimmed.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            return text.take(MAX_LEN).ifBlank { null }
        }
        return trimmed.take(MAX_LEN)
    }

    private fun messageFrom(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.stringOrNull
        is JsonArray -> element.mapNotNull { messageFrom(it) }.joinToString("; ").ifBlank { null }
        is JsonObject -> {
            val direct = listOf("error", "message", "detail", "details", "error_description", "errors", "msg")
                .firstNotNullOfOrNull { key ->
                    element[key]?.let { value ->
                        when (value) {
                            is JsonPrimitive -> value.stringOrNull
                            is JsonObject -> value.str("message") ?: value.str("msg") ?: value.str("detail") ?: messageFrom(value)
                            is JsonArray -> value.mapNotNull { item ->
                                (item as? JsonObject)?.let { o ->
                                    val loc = o.arr("loc")?.joinToString(".") { it.stringOrNull ?: "" }
                                    val msg = o.str("msg") ?: o.str("message")
                                    listOfNotNull(loc, msg).joinToString(": ").ifBlank { null }
                                } ?: item.stringOrNull
                            }.joinToString("; ").ifBlank { null }
                        }
                    }
                }
            direct
        }
    }

    private fun extractEstimatedTime(body: String?): Double? {
        if (body == null) return null
        val element = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return element["estimated_time"].doubleValueOrNull
    }

    private const val MAX_LEN = 600
}
