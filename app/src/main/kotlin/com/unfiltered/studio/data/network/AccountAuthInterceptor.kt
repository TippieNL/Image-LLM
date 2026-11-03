package com.unfiltered.studio.data.network

import com.unfiltered.studio.data.storage.SecureCredentialStore
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Adds the account tokens from Settings → Accounts to Hub/Civitai requests (gated models, NSFW
 * listings, downloads). Exact host match only; OkHttp drops Authorization on cross-host redirects,
 * so signed CDN URLs never see the token.
 */
class AccountAuthInterceptor(private val credentials: SecureCredentialStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val key = when (request.url.host.lowercase()) {
            "huggingface.co" -> SecureCredentialStore.HF_TOKEN
            "civitai.com" -> SecureCredentialStore.CIVITAI_KEY
            else -> null
        } ?: return chain.proceed(request)
        // The store caches decrypted values in memory; this runs on OkHttp's worker thread.
        val token = runBlocking { credentials.get(key) } ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Model-browser metadata is cached for 10 minutes even when the API sends no-cache headers, and the
 * cached copy is used when offline. Only GET API calls of the Hub/Civitai are affected.
 */
class MetadataCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val request = chain.request()
        val cacheable = request.method == "GET" && response.isSuccessful &&
            (request.url.host == "huggingface.co" && request.url.encodedPath.startsWith("/api/models") ||
                request.url.host == "civitai.com" && request.url.encodedPath.startsWith("/api/v1/models"))
        return if (cacheable) {
            response.newBuilder()
                .removeHeader("Pragma")
                .header("Cache-Control", CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build().toString())
                .build()
        } else response
    }
}

class OfflineCacheInterceptor(private val isOnline: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || isOnline()) return chain.proceed(request)
        val offline = request.newBuilder()
            .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(7, TimeUnit.DAYS).build())
            .build()
        return chain.proceed(offline)
    }
}
