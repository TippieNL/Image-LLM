package com.unfiltered.studio.data.network

import android.util.Log
import com.unfiltered.studio.BuildConfig
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.HttpErrors
import com.unfiltered.studio.core.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

fun JsonElement.toRequestBody(): RequestBody = AppJson.encodeToString(JsonElement.serializer(), this).toRequestBody(JSON_MEDIA_TYPE)

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** HTTP helpers that convert transport and status errors into [AppError]s. */
class HttpExecutor(val client: OkHttpClient, private val service: String) {

    suspend fun execute(request: Request): Response = try {
        client.newCall(request).await()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        throw t.toAppError(service, request.url.host)
    }

    /** Executes and throws for non-2xx responses (body is read for the error message). */
    suspend fun executeOk(request: Request, what: String = "Endpoint ${request.url.encodedPath}"): Response {
        val response = execute(request)
        if (!response.isSuccessful) {
            val body = withContext(Dispatchers.IO) { runCatching { response.body.string() }.getOrNull() }
            response.close()
            throw HttpErrors.toAppError(response.code, body, service, response.header("Retry-After"), what)
        }
        return response
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json(Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    suspend fun postJson(url: String, body: JsonElement, headers: Map<String, String> = emptyMap()): JsonElement =
        json(Request.Builder().url(url).post(body.toRequestBody()).apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    suspend fun json(request: Request): JsonElement {
        val response = executeOk(request)
        return withContext(Dispatchers.IO) {
            response.use { r ->
                val text = r.body.string()
                try {
                    AppJson.parseToJsonElement(text.ifBlank { "{}" })
                } catch (e: Exception) {
                    throw AppError.Parse(service, "Expected JSON from ${request.url.encodedPath}: ${text.take(200)}", e)
                }
            }
        }
    }

    suspend fun bytes(request: Request): Pair<ByteArray, String?> {
        val response = executeOk(request)
        return withContext(Dispatchers.IO) {
            response.use { r -> r.body.bytes() to r.header("Content-Type") }
        }
    }

    /** Fire-and-forget style request that ignores the body; failures are swallowed by callers if needed. */
    suspend fun send(request: Request) {
        executeOk(request).close()
    }
}

/**
 * Adds credentials only to requests whose host is in [hosts] — results hosted on third-party CDNs
 * (fal.media, replicate.delivery, …) never receive the user's token.
 */
class HostScopedHeadersInterceptor(
    private val hosts: Set<String>,
    private val headers: Map<String, String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (headers.isEmpty() || request.url.host.lowercase() !in hosts) return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (name, value) -> if (request.header(name) == null) builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}

class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/** Debug-only request log: method, host, path, status and timing. Never headers, query strings or bodies. */
class SafeLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.nanoTime()
        val label = "${request.method} ${request.url.scheme}://${request.url.host}${request.url.encodedPath}"
        return try {
            chain.proceed(request).also { response ->
                if (BuildConfig.DEBUG) Log.d(TAG, "$label → ${response.code} (${(System.nanoTime() - start) / 1_000_000} ms)")
            }
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "$label ✕ ${e.javaClass.simpleName}")
            throw e
        }
    }

    private companion object { const val TAG = "Http" }
}

object Retry {
    /**
     * Retries [block] on transient errors with exponential backoff + jitter, honouring Retry-After
     * and Hugging Face `estimated_time`. [retryIf] lets callers restrict retries for non-idempotent
     * requests (e.g. only when the server was never reached).
     */
    suspend fun <T> withBackoff(
        maxAttempts: Int,
        initialDelayMs: Long = 800,
        maxDelayMs: Long = 20_000,
        retryIf: (AppError) -> Boolean = { it.isTransient },
        onRetry: suspend (attempt: Int, error: AppError, delayMs: Long) -> Unit = { _, _, _ -> },
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 1
        var backoff = initialDelayMs
        while (true) {
            try {
                return block(attempt)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val error = t.toAppError()
                if (attempt >= maxAttempts || !retryIf(error)) throw error
                val wait = when (error) {
                    is AppError.RateLimited -> (error.retryAfterSeconds?.times(1000) ?: backoff).coerceAtMost(60_000)
                    is AppError.ModelLoading -> ((error.estimatedSeconds ?: 10.0) * 1000).toLong().coerceIn(2_000, 60_000)
                    else -> backoff
                } + Random.nextLong(0, 400)
                onRetry(attempt, error, wait)
                delay(wait)
                attempt++
                backoff = (backoff * 2).coerceAtMost(maxDelayMs)
            }
        }
    }

    /** For non-idempotent submissions: only retry when the request certainly never reached the server. */
    val beforeServerReached: (AppError) -> Boolean = { it is AppError.Unreachable || it is AppError.NoConnection }
}

fun String.trimBaseUrl(): String = trim().trimEnd('/')
