package com.unfiltered.studio.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Typed application error. Every failure that reaches the UI is converted into one of these so
 * screens can show an actionable message instead of a raw exception string.
 *
 * [userMessage] is safe to display. [technicalDetails] may contain server output and is shown
 * behind a "Details" affordance. Neither ever contains credentials.
 */
sealed class AppError(
    val userMessage: String,
    val technicalDetails: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    /** Whether retrying the same request later can reasonably succeed. */
    open val isTransient: Boolean get() = false

    class NoConnection(details: String? = null, cause: Throwable? = null) :
        AppError("No network connection. Check Wi-Fi or mobile data and try again.", details, cause) {
        override val isTransient get() = true
    }

    class ConnectionInterrupted(details: String? = null, cause: Throwable? = null) :
        AppError("The connection was interrupted before the server finished responding.", details, cause) {
        override val isTransient get() = true
    }

    class Timeout(operation: String, cause: Throwable? = null) :
        AppError(
            "$operation timed out. The server may be busy or the job is long-running — " +
                "raise the timeout in Settings → Network if this keeps happening.",
            cause?.message,
            cause,
        ) {
        override val isTransient get() = true
    }

    class Unreachable(val host: String, details: String? = null, cause: Throwable? = null) :
        AppError(
            "Can't reach $host. Check that the server is running, the URL/port is correct and " +
                "your phone is on the same network (or VPN).",
            details,
            cause,
        ) {
        override val isTransient get() = true
    }

    class Tls(host: String, details: String?, cause: Throwable?) :
        AppError(
            "Secure connection to $host failed. For self-signed certificates install your CA as a " +
                "user certificate, or use http:// on a trusted local network.",
            details,
            cause,
        )

    class Unauthorized(val service: String, details: String? = null) :
        AppError(
            "$service rejected the credentials (401). The token or password is invalid or has " +
                "expired — update it in Settings.",
            details,
        )

    class Forbidden(val service: String, details: String? = null) :
        AppError(
            "$service denied access (403). The token may lack the required permission, or the " +
                "model is gated and its license must be accepted on the website first.",
            details,
        )

    class PaymentRequired(val service: String, details: String? = null) :
        AppError("$service reports that credits are exhausted or billing is required (402).", details)

    class NotFound(val what: String, details: String? = null) :
        AppError("$what was not found (404).", details)

    class RateLimited(val service: String, val retryAfterSeconds: Long?) :
        AppError(
            buildString {
                append("$service is rate limiting requests (429).")
                if (retryAfterSeconds != null) append(" Try again in ${retryAfterSeconds}s.")
            },
        ) {
        override val isTransient get() = true
    }

    class ModelLoading(val service: String, val estimatedSeconds: Double?) :
        AppError(
            buildString {
                append("The model is still loading on $service.")
                if (estimatedSeconds != null) append(" Estimated ${estimatedSeconds.toInt()}s.")
            },
        ) {
        override val isTransient get() = true
    }

    class BadRequest(val service: String, serverMessage: String?) :
        AppError("$service rejected the request: ${serverMessage ?: "invalid parameters"}", serverMessage)

    class Server(val code: Int, val service: String, serverMessage: String?) :
        AppError("$service returned error $code${serverMessage?.let { ": $it" } ?: ""}", serverMessage) {
        override val isTransient get() = code == 502 || code == 503 || code == 504
    }

    /** The request was understood but parameters/workflow are invalid (e.g. ComfyUI node errors). */
    class Validation(summary: String, val issues: List<String>) :
        AppError(summary, issues.joinToString("\n").ifBlank { null })

    /** The backend accepted the job but failed while executing it. */
    class GenerationFailed(reason: String, details: String? = null) : AppError(reason, details)

    class Unsupported(feature: String, providerName: String) :
        AppError("$feature is not supported by $providerName.")

    class Configuration(reason: String) : AppError(reason)

    class Parse(what: String, details: String?, cause: Throwable? = null) :
        AppError("Unexpected response from $what. The server may be running an incompatible version.", details, cause)

    class Storage(reason: String, cause: Throwable? = null) : AppError(reason, cause?.message, cause)

    class Unknown(cause: Throwable) :
        AppError(cause.message ?: cause::class.java.simpleName, cause.stackTraceToString().take(4000), cause)
}

/**
 * Maps any throwable to an [AppError]. Cancellation is re-thrown, never converted, so structured
 * concurrency keeps working.
 */
fun Throwable.toAppError(service: String = "Server", host: String? = null): AppError = when (this) {
    is AppError -> this
    is CancellationException -> throw this
    is UnknownHostException -> AppError.Unreachable(host ?: message ?: service, "Unknown host", this)
    is ConnectException, is NoRouteToHostException -> AppError.Unreachable(host ?: service, message, this)
    is SocketTimeoutException -> AppError.Timeout("Request to $service", this)
    is InterruptedIOException ->
        if (message?.contains("timeout", ignoreCase = true) == true) AppError.Timeout("Request to $service", this)
        else AppError.ConnectionInterrupted(message, this)
    is SSLException -> AppError.Tls(host ?: service, message, this)
    is SerializationException -> AppError.Parse(service, message, this)
    is IOException -> AppError.ConnectionInterrupted(message, this)
    else -> AppError.Unknown(this)
}

/** Runs [block] converting failures (except cancellation) into [AppError]. */
inline fun <T> appCatching(service: String = "Server", host: String? = null, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t.toAppError(service, host))
    }

val Throwable.displayMessage: String
    get() = (this as? AppError)?.userMessage ?: message ?: this::class.java.simpleName
