package com.unfiltered.studio.data.providers

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.HttpErrors
import com.unfiltered.studio.core.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit = try {
    Retrofit.Builder()
        .baseUrl(baseUrl.trim().ensureTrailingSlash())
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
} catch (e: IllegalArgumentException) {
    throw AppError.Configuration("Invalid server URL \"$baseUrl\". Use a full URL such as http://192.168.1.20:7860")
}

/** Runs a Retrofit call translating HTTP and transport failures into [AppError]. */
suspend fun <T> retrofitCall(service: String, host: String?, what: String = "Endpoint", block: suspend () -> T): T = try {
    block()
} catch (c: CancellationException) {
    throw c
} catch (e: HttpException) {
    val body = withContext(Dispatchers.IO) { runCatching { e.response()?.errorBody()?.string() }.getOrNull() }
    throw HttpErrors.toAppError(e.code(), body, service, e.response()?.headers()?.get("Retry-After"), what)
} catch (t: Throwable) {
    throw t.toAppError(service, host)
}

/** Like [retrofitCall] but returns null for failures of optional endpoints (missing on older servers). */
suspend fun <T> optionalCall(service: String, host: String?, block: suspend () -> T): Result<T> = try {
    Result.success(retrofitCall(service, host, block = block))
} catch (c: CancellationException) {
    throw c
} catch (e: AppError) {
    if (e is AppError.Unauthorized || e is AppError.Forbidden) throw e
    Result.failure(e)
}
