package com.unfiltered.studio.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPathTest {
    private val doc = AppJson.parseToJsonElement(
        """{"data":[{"b64_json":"AAA"},{"b64_json":"BBB"}],"urls":{"get":"https://x/1"},"output":"https://img/1.png","images":[{"url":"u1"},{"url":"u2"}]}""",
    )

    @Test fun keysIndicesAndWildcards() {
        assertEquals("AAA", JsonPath.selectString(doc, "data[0].b64_json"))
        assertEquals(listOf("AAA", "BBB"), JsonPath.select(doc, "data[*].b64_json").map { it.stringOrNull })
        assertEquals("https://x/1", JsonPath.selectString(doc, "urls.get"))
        assertEquals(listOf("u1", "u2"), JsonPath.selectFlattened(doc, "images[*].url").map { it.stringOrNull })
        assertEquals("BBB", JsonPath.selectString(doc, "data[-1].b64_json"))
    }

    @Test fun trailingArrayIsFlattenedAndScalarKept() {
        assertEquals(1, JsonPath.selectFlattened(doc, "output").size)
        assertEquals(2, JsonPath.selectFlattened(doc, "images").size)
        assertTrue(JsonPath.select(doc, "missing.path").isEmpty())
    }

    @Test fun validatesSyntax() {
        assertTrue(JsonPath.isValid("a.b[0].c"))
        assertFalse(JsonPath.isValid("a[0"))
    }
}

class JsonTemplateTest {
    @Test fun typedSubstitutionAndInterpolation() {
        val result = JsonTemplate.render(
            """{"prompt":"a photo of {{prompt}}","steps":"{{steps}}","cfg":"{{cfg|float}}","seed":"{{seed|str}}","loras":"{{loras}}","x":"{{unknown}}"}""",
            mapOf("prompt" to "a \"quoted\" cat", "steps" to 30, "cfg" to 7, "seed" to 42L, "loras" to listOf(mapOf("name" to "a", "strength" to 0.5))),
        )
        val o = result.json as JsonObject
        assertEquals("a photo of a \"quoted\" cat", o.str("prompt"))
        assertEquals(30, o["steps"]!!.jsonPrimitive.int)
        assertFalse((o["steps"] as JsonPrimitive).isString)
        assertEquals(7.0, o["cfg"].doubleValueOrNull!!, 0.0)
        assertTrue((o["seed"] as JsonPrimitive).isString)
        assertTrue(o["loras"] is JsonArray)
        assertEquals(setOf("unknown"), result.unresolved)
        assertEquals("{{unknown}}", o.str("x"))
    }

    @Test(expected = AppError.Configuration::class)
    fun rejectsInvalidJson() {
        JsonTemplate.render("""{"steps": {{steps}} }""", mapOf("steps" to 1))
    }

    @Test fun textRendering() {
        assertEquals("https://h/status/abc", JsonTemplate.renderText("{{base_url}}/status/{{job_id}}", mapOf("base_url" to "https://h", "job_id" to "abc")))
    }
}

class HttpErrorsTest {
    @Test fun fastApiValidationDetail() {
        val e = HttpErrors.toAppError(422, """{"detail":[{"loc":["body","steps"],"msg":"value is not a valid integer","type":"type_error"}]}""", "WebUI")
        assertTrue(e is AppError.Validation)
        assertTrue(e.technicalDetails!!.contains("body.steps: value is not a valid integer"))
    }

    @Test fun huggingFaceLoadingBecomesModelLoading() {
        val e = HttpErrors.toAppError(503, """{"error":"Model x is currently loading","estimated_time":20.5}""", "HF")
        assertTrue(e is AppError.ModelLoading)
        assertEquals(20.5, (e as AppError.ModelLoading).estimatedSeconds!!, 0.01)
    }

    @Test fun openAiStyleNestedError() {
        val e = HttpErrors.toAppError(400, """{"error":{"message":"Invalid size","type":"invalid_request_error"}}""", "API")
        assertTrue(e is AppError.BadRequest)
        assertTrue(e.userMessage.contains("Invalid size"))
    }

    @Test fun authAndRateLimit() {
        assertTrue(HttpErrors.toAppError(401, """{"error":"Invalid credentials"}""", "HF") is AppError.Unauthorized)
        val limited = HttpErrors.toAppError(429, "slow down", "API", retryAfterHeader = "12")
        assertEquals(12L, (limited as AppError.RateLimited).retryAfterSeconds)
        assertTrue(limited.isTransient)
    }

    @Test fun htmlBodiesUseTitle() {
        assertEquals("502 Bad Gateway", HttpErrors.extractMessage("<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"))
    }

    @Test fun cancellationIsNeverConverted() {
        val thrown = runCatching { kotlinx.coroutines.CancellationException("stop").toAppError() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }
}
