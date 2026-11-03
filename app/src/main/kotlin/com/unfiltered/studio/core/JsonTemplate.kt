package com.unfiltered.studio.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Placeholder templating over a JSON tree (used for custom REST bodies and ComfyUI workflows).
 *
 * Rules:
 *  - A string value that is exactly one placeholder (`"{{steps}}"`) is replaced by the typed value
 *    (number stays a number, lists become arrays, objects stay objects).
 *  - A cast can be forced: `"{{seed|str}}"`, `"{{cfg|int}}"`, `"{{steps|float}}"`.
 *  - Placeholders embedded in longer strings (`"a photo of {{prompt}}"`) are interpolated as text.
 *  - Unknown placeholders are left untouched and reported via [Result.unresolved].
 */
object JsonTemplate {

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*(?:\|\s*(str|int|float|json)\s*)?}}""")

    data class Result(val json: JsonElement, val used: Set<String>, val unresolved: Set<String>)

    fun placeholdersIn(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    /** Parses [template] as JSON and substitutes placeholders. Throws [AppError.Configuration] on bad JSON. */
    fun render(template: String, vars: Map<String, Any?>): Result {
        val root = try {
            AppJson.parseToJsonElement(template)
        } catch (e: Exception) {
            throw AppError.Configuration(
                "The request body template is not valid JSON (${e.message?.take(160)}). " +
                    "Quote numeric placeholders, e.g. \"steps\": \"{{steps}}\".",
            )
        }
        return renderTree(root, vars)
    }

    fun renderTree(root: JsonElement, vars: Map<String, Any?>): Result {
        val used = mutableSetOf<String>()
        val unresolved = mutableSetOf<String>()
        val out = walk(root, vars, used, unresolved)
        return Result(out, used, unresolved)
    }

    /** Plain text interpolation (URLs, header values). Missing variables become empty strings. */
    fun renderText(text: String, vars: Map<String, Any?>): String = PLACEHOLDER.replace(text) { match ->
        val value = vars[match.groupValues[1]]
        when (value) {
            null -> ""
            is JsonPrimitive -> value.stringOrNull.orEmpty()
            is JsonElement -> value.toString()
            else -> value.toString()
        }
    }

    private fun walk(
        element: JsonElement,
        vars: Map<String, Any?>,
        used: MutableSet<String>,
        unresolved: MutableSet<String>,
    ): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> walk(v, vars, used, unresolved) })
        is JsonArray -> JsonArray(element.map { walk(it, vars, used, unresolved) })
        is JsonPrimitive -> if (element.isString) substitute(element.content, vars, used, unresolved) else element
    }

    private fun substitute(
        text: String,
        vars: Map<String, Any?>,
        used: MutableSet<String>,
        unresolved: MutableSet<String>,
    ): JsonElement {
        val whole = PLACEHOLDER.matchEntire(text.trim())
        if (whole != null) {
            val key = whole.groupValues[1]
            val cast = whole.groupValues[2].ifEmpty { null }
            if (!vars.containsKey(key)) {
                unresolved += key
                return JsonPrimitive(text)
            }
            used += key
            return cast(vars[key].toJsonElement(), cast)
        }
        if (!text.contains("{{")) return JsonPrimitive(text)
        val replaced = PLACEHOLDER.replace(text) { match ->
            val key = match.groupValues[1]
            if (!vars.containsKey(key)) {
                unresolved += key
                match.value
            } else {
                used += key
                when (val v = vars[key].toJsonElement()) {
                    is JsonNull -> ""
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
            }
        }
        return JsonPrimitive(replaced)
    }

    private fun cast(value: JsonElement, cast: String?): JsonElement = when (cast) {
        null -> value
        "str" -> JsonPrimitive(
            when (value) {
                is JsonNull -> ""
                is JsonPrimitive -> value.content
                else -> value.toString()
            },
        )
        "int" -> value.doubleValueOrNull?.let { JsonPrimitive(it.toLong()) } ?: value
        "float" -> value.doubleValueOrNull?.let { JsonPrimitive(it) } ?: value
        "json" -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let {
            runCatching { AppJson.parseToJsonElement(it.content) }.getOrNull()
        } ?: value
        else -> value
    }
}
