package com.unfiltered.studio.data.providers

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.data.network.ProviderHttpFactory
import com.unfiltered.studio.data.repositories.ProviderStore
import com.unfiltered.studio.di.ApplicationScope
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import com.unfiltered.studio.domain.providers.ProviderRegistry
import com.unfiltered.studio.domain.providers.ProviderSecretsOverride
import com.unfiltered.studio.domain.repositories.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches one adapter per provider id and rebuilds it when its configuration, credentials or the
 * global network settings change. New backends plug in through the [factories] map only.
 */
@Singleton
class ProviderRegistryImpl @Inject constructor(
    private val factories: Map<ProviderType, @JvmSuppressWildcards ProviderAdapterFactory>,
    private val store: ProviderStore,
    private val httpFactory: ProviderHttpFactory,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : ProviderRegistry {

    private val cache = ConcurrentHashMap<String, Pair<ProviderConfig, ImageGenerationProvider>>()

    init {
        scope.launch {
            settings.settings
                .map { Triple(it.connectTimeoutSec, it.readTimeoutSec, it.maxRetries) }
                .distinctUntilChanged()
                .drop(1)
                .collect { cache.clear() }
        }
    }

    override suspend fun get(providerId: String): ImageGenerationProvider? {
        val config = store.get(providerId) ?: return null
        cache[providerId]?.let { (cachedConfig, provider) -> if (cachedConfig == config) return provider }
        val provider = create(config, null)
        cache[providerId] = config to provider
        return provider
    }

    override suspend fun create(config: ProviderConfig, secrets: ProviderSecretsOverride?): ImageGenerationProvider {
        val factory = factories[config.type]
            ?: throw AppError.Configuration("No adapter is registered for ${config.type.displayName}.")
        return factory.create(config, httpFactory.create(config, secrets))
    }

    override fun invalidate(providerId: String) {
        cache.remove(providerId)
    }

    fun invalidateAll() = cache.clear()
}
