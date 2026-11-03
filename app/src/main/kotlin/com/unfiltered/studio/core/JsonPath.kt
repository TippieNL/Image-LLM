package com.unfiltered.studio.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal JSON path used by the configurable REST adapter to locate job ids, statuses and images.
 *
 * Supported syntax: `a.b.c`, `a[0].b`, `a[*].b`, `a.*`, `$` (root). A trailing array match is
 * returned as its elements by [selectFlattened], which is what image extraction wants
 * (`output` may be either a single URL or a list of URLs).
 */
object JsonPath {

    private sealed interface Token {
        data class Key(val name: String) : Token
        data class Index(val index: Int) : Token
        data object Wildcard : Token
    }

    fun select(root: JsonElement, path: String): List<JsonElement> {
        var current = listOf(root)
        for (token in tokenize(path)) {
            current = current.flatMap { element -> step(element, token) }
            if (current.isEmpty()) break
        }
        return current
    }

    /** Like [select] but expands arrays found at the end of the path into their elements. */
    fun selectFlattened(root: JsonElement, path: String): List<JsonElement> =
        select(root, path).flatMap { if (it is JsonArray) it.toList() else listOf(it) }

    fun selectString(root: JsonElement, path: String?): String? {
        if (path.isNullOrBlank()) return null
        return select(root, path).firstNotNullOfOrNull { it.stringOrNull }
    }

    fun isValid(path: String): Boolean = runCatching { tokenize(path) }.isSuccess

    private fun step(element: JsonElement, token: Token): List<JsonElement> = when (token) {
        is Token.Key -> listOfNotNull((element as? JsonObject)?.get(token.name))
        is Token.Index -> {
            val array = element as? JsonArray
            val index = if (token.index < 0) (array?.size ?: 0) + token.index else token.index
            listOfNotNull(array?.getOrNull(index))
        }
        Token.Wildcard -> when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> element.values.toList()
            else -> emptyList()
        }
    }

    private fun tokenize(path: String): List<Token> {
        val trimmed = path.trim().removePrefix("$").removePrefix(".")
        if (trimmed.isEmpty()) return emptyList()
        val tokens = mutableListOf<Token>()
        var i = 0
        val name = StringBuilder()
        fun flushName() {
            if (name.isNotEmpty()) {
                val n = name.toString()
                tokens += if (n == "*") Token.Wildcard else Token.Key(n)
                name.clear()
            }
        }
        while (i < trimmed.length) {
            val c = trimmed[i]
            when (c) {
                '.' -> flushName()
                '[' -> {
                    flushName()
                    val end = trimmed.indexOf(']', i)
                    require(end > i) { "Unclosed '[' in path '$path'" }
                    val inner = trimmed.substring(i + 1, end).trim().removeSurrounding("\"").removeSurrounding("'")
                    tokens += when {
                        inner == "*" -> Token.Wildcard
                        inner.toIntOrNull() != null -> Token.Index(inner.toInt())
                        inner.isNotEmpty() -> Token.Key(inner)
                        else -> throw IllegalArgumentException("Empty brackets in path '$path'")
                    }
                    i = end
                }
                else -> name.append(c)
            }
            i++
        }
        flushName()
        return tokens
    }
}
