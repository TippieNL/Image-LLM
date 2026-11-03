package com.unfiltered.studio.data.repositories

import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.displayMessage
import com.unfiltered.studio.data.database.dao.CatalogDao
import com.unfiltered.studio.data.database.dao.ProviderDao
import com.unfiltered.studio.data.database.entities.CatalogCacheEntity
import com.unfiltered.studio.data.database.entities.ProviderEntity
import com.unfiltered.studio.data.providers.ProviderRegistryImpl
import com.unfiltered.studio.data.providers.comfyui.ComfyWorkflowBuilder
import com.unfiltered.studio.data.providers.huggingface.HuggingFaceProvider
import com.unfiltered.studio.data.storage.SecureCredentialStore
import com.unfiltered.studio.domain.models.AuthConfig
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.ComfySettings
import com.unfiltered.studio.domain.models.ComfyWorkflowMode
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.ConnectionState
import com.unfiltered.studio.domain.models.CustomRestSettings
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderSettings
import com.unfiltered.studio.domain.models.ProviderStatus
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.providers.ProviderSecretsOverride
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.repositories.ProviderSecretsUpdate
import com.unfiltered.studio.domain.repositories.SecretChange
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.core.JsonPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Maps provider rows ⇄ domain configs (shared by the repository and the adapter registry). */
@Singleton
class ProviderStore @Inject constructor(private val dao: ProviderDao) {
    fun observeAll(): Flow<List<ProviderConfig>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }
    suspend fun all(): List<ProviderConfig> = dao.getAll().mapNotNull { it.toDomain() }
    suspend fun get(id: String): ProviderConfig? = dao.get(id)?.toDomain()
    suspend fun upsert(config: ProviderConfig) = dao.upsert(config.toEntity())
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun nextSortOrder(): Int = dao.maxSortOrder() + 1

    private fun ProviderEntity.toDomain(): ProviderConfig? = runCatching {
        ProviderConfig(
            id = id,
            name = name,
            type = ProviderType.valueOf(type),
            baseUrl = baseUrl,
            auth = AppJson.decodeFromString(AuthConfig.serializer(), authJson),
            settings = AppJson.decodeFromString<ProviderSettings>(settingsJson),
            createdAt = createdAt,
            sortOrder = sortOrder,
        )
    }.getOrNull()

    private fun ProviderConfig.toEntity() = ProviderEntity(
        id = id,
        name = name,
        type = type.name,
        baseUrl = baseUrl.trim(),
        authJson = AppJson.encodeToString(AuthConfig.serializer(), auth),
        settingsJson = AppJson.encodeToString<ProviderSettings>(settings),
        createdAt = createdAt,
        sortOrder = sortOrder,
    )
}

@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val store: ProviderStore,
    private val credentials: SecureCredentialStore,
    private val registry: ProviderRegistryImpl,
    private val catalogDao: CatalogDao,
    private val settings: SettingsRepository,
) : ProviderRepository {

    private val _statuses = MutableStateFlow<Map<String, ProviderStatus>>(emptyMap())
    override val statuses: StateFlow<Map<String, ProviderStatus>> = _statuses.asStateFlow()

    override fun observeProviders(): Flow<List<ProviderConfig>> = store.observeAll()

    override fun observeActiveProvider(): Flow<ProviderConfig?> =
        combine(store.observeAll(), settings.settings.map { it.activeProviderId }.distinctUntilChanged()) { providers, activeId ->
            providers.firstOrNull { it.id == activeId } ?: providers.firstOrNull()
        }.distinctUntilChanged()

    override suspend fun getProvider(id: String): ProviderConfig? = store.get(id)

    override suspend fun saveProvider(config: ProviderConfig, secrets: ProviderSecretsUpdate): ProviderConfig {
        validate(config)
        val previous = store.get(config.id)
        val toSave = if (previous == null) config.copy(sortOrder = store.nextSortOrder()) else config
        store.upsert(toSave)
        applySecret(SecureCredentialStore.providerToken(config.id), secrets.token)
        secrets.headers.forEach { (name, change) -> applySecret(SecureCredentialStore.providerHeader(config.id, name), change) }
        // Drop stored values for headers that were removed or are no longer secret.
        previous?.auth?.extraHeaders?.filter { it.secret }?.forEach { old ->
            if (toSave.auth.extraHeaders.none { it.secret && it.name.equals(old.name, ignoreCase = true) }) {
                credentials.remove(SecureCredentialStore.providerHeader(config.id, old.name))
            }
        }
        if (previous != null && (previous.baseUrl != toSave.baseUrl || previous.type != toSave.type)) catalogDao.delete(config.id)
        registry.invalidate(config.id)
        _statuses.update { it - config.id }
        if (settings.current().activeProviderId == null) setActiveProvider(config.id)
        return toSave
    }

    private suspend fun applySecret(key: String, change: SecretChange) {
        when (change) {
            SecretChange.Keep -> Unit
            SecretChange.Clear -> credentials.remove(key)
            is SecretChange.Set -> if (change.value.isBlank()) credentials.remove(key) else credentials.put(key, change.value.trim())
        }
    }

    private fun validate(config: ProviderConfig) {
        if (config.name.isBlank()) throw AppError.Configuration("Give the provider a name.")
        val url = config.baseUrl.trim().toHttpUrlOrNull()
            ?: throw AppError.Configuration("Enter a full URL including http:// or https:// (e.g. http://192.168.1.20:8188).")
        if (url.host.isBlank()) throw AppError.Configuration("The URL has no host.")
        when (val s = config.settings) {
            is ComfySettings -> if (s.workflowMode == ComfyWorkflowMode.CUSTOM) {
                val json = s.customWorkflowJson?.takeIf { it.isNotBlank() }
                    ?: throw AppError.Configuration("Paste a workflow exported with Export (API), or switch to the built-in templates.")
                ComfyWorkflowBuilder.parseCustom(json)
            }
            is CustomRestSettings -> {
                if (s.method.uppercase() != "GET") {
                    runCatching { AppJson.parseToJsonElement(s.bodyTemplate) }.getOrElse {
                        throw AppError.Configuration("The body template must be valid JSON (quote numeric placeholders: \"{{steps}}\").")
                    }
                }
                listOfNotNull(s.imagePath, s.async?.jobIdPath, s.async?.statusPath, s.async?.statusUrlPath, s.async?.resultUrlPath)
                    .forEach { if (!JsonPath.isValid(it)) throw AppError.Configuration("Invalid JSON path: $it") }
            }
            else -> Unit
        }
    }

    override suspend fun deleteProvider(id: String) {
        store.delete(id)
        credentials.removeWithPrefix(SecureCredentialStore.providerPrefix(id))
        catalogDao.delete(id)
        registry.invalidate(id)
        _statuses.update { it - id }
        if (settings.current().activeProviderId == id) {
            val next = store.all().firstOrNull()?.id
            settings.update { it.copy(activeProviderId = next) }
        }
    }

    override suspend fun setActiveProvider(id: String) = settings.update { it.copy(activeProviderId = id) }

    override suspend fun hasToken(providerId: String): Boolean = credentials.contains(SecureCredentialStore.providerToken(providerId))

    override suspend fun hasHeaderSecret(providerId: String, headerName: String): Boolean =
        credentials.contains(SecureCredentialStore.providerHeader(providerId, headerName))

    override suspend fun testConnection(providerId: String): ProviderStatus {
        _statuses.update { it + (providerId to ProviderStatus(ConnectionState.CHECKING)) }
        val status = try {
            val provider = registry.get(providerId) ?: throw AppError.Configuration("Provider not found.")
            ProviderStatus(ConnectionState.ONLINE, provider.testConnection(), checkedAt = System.currentTimeMillis())
        } catch (e: kotlinx.coroutines.CancellationException) {
            _statuses.update { it - providerId }
            throw e
        } catch (e: Exception) {
            ProviderStatus(ConnectionState.ERROR, error = e.displayMessage, checkedAt = System.currentTimeMillis())
        }
        _statuses.update { it + (providerId to status) }
        return status
    }

    override suspend fun testUnsaved(config: ProviderConfig, secrets: ProviderSecretsUpdate): ConnectionInfo {
        validate(config)
        suspend fun resolve(key: String, change: SecretChange): String? = when (change) {
            SecretChange.Keep -> credentials.get(key)
            SecretChange.Clear -> null
            is SecretChange.Set -> change.value.trim().ifBlank { null }
        }
        val headers = config.auth.extraHeaders.filter { it.secret }.associate { header ->
            header.name to resolve(SecureCredentialStore.providerHeader(config.id, header.name), secrets.headers[header.name] ?: SecretChange.Keep)
        }.filterValues { it != null }.mapValues { it.value!! }
        val override = ProviderSecretsOverride(
            token = resolve(SecureCredentialStore.providerToken(config.id), secrets.token),
            headerValues = headers,
        )
        return registry.create(config, override).testConnection()
    }

    override fun observeCatalog(providerId: String): Flow<BackendCatalog?> =
        catalogDao.observe(providerId).map { row -> row?.let { decodeCatalog(it.json) } }.distinctUntilChanged()

    override suspend fun refreshCatalog(providerId: String): BackendCatalog {
        val provider = registry.get(providerId) ?: throw AppError.Configuration("Provider not found.")
        val catalog = provider.fetchCatalog()
        catalogDao.upsert(CatalogCacheEntity(providerId, AppJson.encodeToString(BackendCatalog.serializer(), catalog), catalog.fetchedAt))
        _statuses.update {
            it + (providerId to ProviderStatus(ConnectionState.ONLINE, it[providerId]?.info, checkedAt = System.currentTimeMillis()))
        }
        return catalog
    }

    override suspend fun refreshModelInfo(providerId: String, modelId: String?): BackendCatalog? {
        val provider = registry.get(providerId) ?: return null
        val current = catalogDao.get(providerId)?.let { decodeCatalog(it.json) } ?: BackendCatalog(providerId, System.currentTimeMillis())
        val updated = provider.refreshModelInfo(current, modelId)
        if (updated != current) {
            catalogDao.upsert(CatalogCacheEntity(providerId, AppJson.encodeToString(BackendCatalog.serializer(), updated), updated.fetchedAt))
        }
        return updated
    }

    override suspend fun resumeHfEndpoint(providerId: String) {
        val provider = registry.get(providerId) as? HuggingFaceProvider
            ?: throw AppError.Unsupported("Resuming endpoints", "this provider")
        provider.resumeEndpoint()
    }

    private fun decodeCatalog(json: String): BackendCatalog? =
        runCatching { AppJson.decodeFromString(BackendCatalog.serializer(), json) }.getOrNull()
}
