package com.unfiltered.studio.data.network

import com.unfiltered.studio.data.storage.SecureCredentialStore
import com.unfiltered.studio.domain.models.AuthType
import com.unfiltered.studio.domain.models.HfMode
import com.unfiltered.studio.domain.models.HuggingFaceSettings
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.providers.ProviderSecretsOverride
import com.unfiltered.studio.domain.repositories.SettingsRepository
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** HTTP clients configured for one provider: auth headers scoped to its hosts plus user timeouts. */
class ProviderHttp(
    val api: HttpExecutor,
    /** Same auth, unlimited read timeout + ping — for WebSockets. */
    val streaming: OkHttpClient,
    /** No credentials — for downloading result images from third-party CDNs. */
    val anonymous: HttpExecutor,
    val maxRetries: Int,
)

@Singleton
class ProviderHttpFactory @Inject constructor(
    private val baseClient: OkHttpClient,
    private val credentials: SecureCredentialStore,
    private val settings: SettingsRepository,
) {

    suspend fun create(config: ProviderConfig, override: ProviderSecretsOverride? = null): ProviderHttp {
        val appSettings = settings.current()
        val headers = resolveHeaders(config, override)
        val hosts = authHosts(config)
        val authed = baseClient.newBuilder()
            .connectTimeout(appSettings.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(appSettings.readTimeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(HostScopedHeadersInterceptor(hosts, headers))
            .build()
        val streaming = authed.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val anonymous = baseClient.newBuilder()
            .connectTimeout(appSettings.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(appSettings.readTimeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        return ProviderHttp(
            api = HttpExecutor(authed, config.name),
            streaming = streaming,
            anonymous = HttpExecutor(anonymous, config.name),
            maxRetries = appSettings.maxRetries.coerceIn(1, 8),
        )
    }

    private suspend fun resolveHeaders(config: ProviderConfig, override: ProviderSecretsOverride?): Map<String, String> {
        val auth = config.auth
        val headers = linkedMapOf<String, String>()
        // With an override (editor "Test" before saving) the override is authoritative: null = no secret.
        val token = if (override != null) override.token else credentials.get(SecureCredentialStore.providerToken(config.id))
        val accountToken = if (auth.useAccountToken) credentials.get(SecureCredentialStore.HF_TOKEN) else null

        when (auth.type) {
            AuthType.NONE -> Unit
            AuthType.BEARER -> (token ?: accountToken)?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer ${it.trim()}" }
            AuthType.BASIC -> if (!auth.username.isNullOrBlank() && token != null) {
                headers["Authorization"] = Credentials.basic(auth.username, token)
            }
            AuthType.API_KEY_HEADER -> {
                val name = auth.headerName?.trim().orEmpty()
                if (name.isNotEmpty() && !token.isNullOrBlank()) headers[name] = (auth.headerValuePrefix ?: "") + token.trim()
            }
        }
        if (auth.type == AuthType.NONE && accountToken != null) headers["Authorization"] = "Bearer ${accountToken.trim()}"

        for (header in auth.extraHeaders) {
            if (header.name.isBlank()) continue
            val value = if (header.secret) {
                if (override != null) override.headerValues[header.name]
                else credentials.get(SecureCredentialStore.providerHeader(config.id, header.name))
            } else header.value
            if (!value.isNullOrEmpty()) headers[header.name.trim()] = value
        }
        return headers
    }

    private fun authHosts(config: ProviderConfig): Set<String> {
        val hosts = mutableSetOf<String>()
        runCatching { URI(config.baseUrl.trim()).host?.lowercase() }.getOrNull()?.let { hosts += it }
        if (config.type == ProviderType.HUGGING_FACE) {
            val settings = config.settings as? HuggingFaceSettings
            hosts += HF_API_HOSTS
            if (settings?.mode == HfMode.INFERENCE_PROVIDERS) hosts += "router.huggingface.co"
        }
        return hosts
    }

    companion object {
        val HF_API_HOSTS = setOf("huggingface.co", "router.huggingface.co", "api.endpoints.huggingface.cloud")
    }
}
