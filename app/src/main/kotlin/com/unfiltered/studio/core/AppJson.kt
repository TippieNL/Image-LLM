package com.unfiltered.studio.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Shared JSON configuration: tolerant of server additions, compact when encoding. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    classDiscriminator = "kind"
}

/** Converts plain Kotlin values into a JSON tree. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

val JsonElement.objOrNull: JsonObject? get() = this as? JsonObject
val JsonElement.arrOrNull: JsonArray? get() = this as? JsonArray
val JsonElement.primOrNull: JsonPrimitive? get() = this as? JsonPrimitive

/** String content of a primitive (numbers/booleans included), null for objects/arrays/null. */
val JsonElement?.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

val JsonElement?.longValueOrNull: Long?
    get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

val JsonElement?.doubleValueOrNull: Double?
    get() = (this as? JsonPrimitive)?.doubleOrNull

val JsonElement?.boolValueOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.booleanOrNull

fun JsonObject.str(key: String): String? = this[key].stringOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
