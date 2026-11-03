package com.unfiltered.studio.testutil

import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.data.network.HostScopedHeadersInterceptor
import com.unfiltered.studio.data.network.HttpExecutor
import com.unfiltered.studio.data.network.ProviderHttp
import com.unfiltered.studio.domain.models.AuthConfig
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderSettings
import com.unfiltered.studio.domain.models.ProviderType
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

object TestHttp {
    /** A real (decodable) 2×2 PNG. */
    val PNG: ByteArray by lazy {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0x8B5CF6) }
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    val PNG_B64: String get() = Base64.getEncoder().encodeToString(PNG)

    /**
     * Client for adapter tests. When [redirectTo] is set every request (e.g. to router.huggingface.co
     * or a CDN) is rewritten to the mock server, keeping the original path and query.
     */
    fun client(
        redirectTo: MockWebServer? = null,
        authHosts: Set<String> = emptySet(),
        authHeaders: Map<String, String> = emptyMap(),
    ): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        // Same host-scoped credential injection as production, applied before the redirect so it sees real hosts.
        .apply { if (authHeaders.isNotEmpty()) addInterceptor(HostScopedHeadersInterceptor(authHosts, authHeaders)) }
        .apply {
            if (redirectTo != null) addInterceptor { chain ->
                val original = chain.request()
                val url = original.url.newBuilder().scheme("http").host(redirectTo.hostName).port(redirectTo.port).build()
                chain.proceed(original.newBuilder().url(url).header("X-Original-Host", original.url.host).build())
            }
        }
        .build()

    fun http(client: OkHttpClient, anonymous: OkHttpClient = client) =
        ProviderHttp(HttpExecutor(client, "Test"), client, HttpExecutor(anonymous, "Test"), maxRetries = 2)

    fun config(type: ProviderType, baseUrl: String, settings: ProviderSettings, auth: AuthConfig = AuthConfig()) =
        ProviderConfig("provider-1", "Test ${type.name}", type, baseUrl, auth, settings, createdAt = 0L)

    fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    fun png() = MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG))

    fun RecordedRequest.bodyJson(): JsonObject = AppJson.parseToJsonElement(body.readUtf8()) as JsonObject
}

/** Routes requests by "METHOD path-prefix" and records everything it saw. */
class RoutingDispatcher(private val routes: (RecordedRequest) -> MockResponse?) : Dispatcher() {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val bodies = CopyOnWriteArrayList<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = request.body.clone().readUtf8()
        requests += request
        bodies += body
        return routes(request) ?: MockResponse().setResponseCode(404).setBody("{\"detail\":\"Not Found\"}")
    }
}
