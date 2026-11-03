package com.unfiltered.studio.domain.repositories

import android.net.Uri
import androidx.paging.PagingData
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.BackendCatalog
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.ConnectionInfo
import com.unfiltered.studio.domain.models.DownloadItem
import com.unfiltered.studio.domain.models.DownloadRequest
import com.unfiltered.studio.domain.models.GalleryFacets
import com.unfiltered.studio.domain.models.GalleryFilter
import com.unfiltered.studio.domain.models.GalleryImage
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.models.LiveProgress
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraCategory
import com.unfiltered.studio.domain.models.LoraFilter
import com.unfiltered.studio.domain.models.LoraSyncResult
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.PromptEntry
import com.unfiltered.studio.domain.models.PromptTemplate
import com.unfiltered.studio.domain.models.ProviderConfig
import com.unfiltered.studio.domain.models.ProviderStatus
import com.unfiltered.studio.domain.models.RemoteModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface SecretChange {
    data object Keep : SecretChange
    data object Clear : SecretChange
    data class Set(val value: String) : SecretChange
}

data class ProviderSecretsUpdate(
    /** Bearer token, API key or BASIC password depending on the auth type. */
    val token: SecretChange = SecretChange.Keep,
    /** Secret custom headers by header name. */
    val headers: Map<String, SecretChange> = emptyMap(),
)

interface ProviderRepository {
    fun observeProviders(): Flow<List<ProviderConfig>>
    fun observeActiveProvider(): Flow<ProviderConfig?>
    suspend fun getProvider(id: String): ProviderConfig?
    suspend fun saveProvider(config: ProviderConfig, secrets: ProviderSecretsUpdate): ProviderConfig
    suspend fun deleteProvider(id: String)
    suspend fun setActiveProvider(id: String)
    suspend fun hasToken(providerId: String): Boolean
    suspend fun hasHeaderSecret(providerId: String, headerName: String): Boolean

    val statuses: StateFlow<Map<String, ProviderStatus>>
    suspend fun testConnection(providerId: String): ProviderStatus
    /** Tests an unsaved configuration from the editor. */
    suspend fun testUnsaved(config: ProviderConfig, secrets: ProviderSecretsUpdate): ConnectionInfo

    fun observeCatalog(providerId: String): Flow<BackendCatalog?>
    suspend fun refreshCatalog(providerId: String): BackendCatalog
    suspend fun refreshModelInfo(providerId: String, modelId: String?): BackendCatalog?
    /** Hugging Face dedicated endpoints only. */
    suspend fun resumeHfEndpoint(providerId: String)
}

interface GenerationRepository {
    /** Validates and resolves parameters (seed, LoRA names, size) for the target provider. */
    suspend fun prepare(providerId: String, params: GenerationParameters, stack: List<ActiveLora>): com.unfiltered.studio.domain.models.PreparedGeneration
    suspend fun enqueue(providerId: String, params: GenerationParameters, warnings: List<String>): String
    suspend fun cancel(generationId: String)
    suspend fun cancelAll()
    suspend fun retry(generationId: String): String?
    suspend fun regenerate(generationId: String, newSeed: Boolean): String?
    fun observeActive(): Flow<List<Generation>>
    fun observeLive(): StateFlow<Map<String, LiveProgress>>
    fun observeLatestFinished(): Flow<Generation?>
}

interface GenerationHistoryRepository {
    fun pagedImages(filter: GalleryFilter): Flow<PagingData<GalleryImage>>
    fun observeImageIds(filter: GalleryFilter): Flow<List<String>>
    fun pagedGenerations(query: String, status: GenerationStatus?): Flow<PagingData<Generation>>
    fun observeGeneration(id: String): Flow<Generation?>
    fun observeImage(id: String): Flow<GalleryImage?>
    fun observeFacets(): Flow<GalleryFacets>
    suspend fun setFavorite(imageIds: Collection<String>, favorite: Boolean)
    suspend fun deleteImages(imageIds: Collection<String>)
    suspend fun deleteGeneration(id: String)
    /** Exports to the configured device location; returns the number exported. */
    suspend fun export(imageIds: Collection<String>): Int
    suspend fun shareUris(imageIds: Collection<String>): List<Uri>
}

interface LoraRepository {
    fun observeLoras(filter: LoraFilter): Flow<List<Lora>>
    fun observeLora(id: String): Flow<Lora?>
    suspend fun getLora(id: String): Lora?
    fun observeCategories(): Flow<List<LoraCategory>>
    suspend fun save(lora: Lora)
    suspend fun delete(id: String, deleteLocalFile: Boolean)
    suspend fun importFile(uri: Uri): Lora
    suspend fun syncFromServer(providerId: String): LoraSyncResult
    suspend fun addFromRemote(model: RemoteModel, versionIndex: Int = 0): Lora
    suspend fun setLink(loraId: String, providerId: String, backendName: String?)
    suspend fun refreshServerMetadata(loraId: String, providerId: String): Lora?
    suspend fun lookupOnCivitai(loraId: String): Lora?
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun createCategory(name: String): LoraCategory
    suspend fun renameCategory(id: String, name: String)
    suspend fun deleteCategory(id: String)

    fun observeStack(): Flow<List<ActiveLora>>
    suspend fun addToStack(loraId: String)
    suspend fun removeFromStack(loraId: String)
    suspend fun updateStackEntry(loraId: String, strength: Float? = null, clipStrength: Float? = null, enabled: Boolean? = null)
    suspend fun replaceStack(entries: List<Triple<String, Float, Boolean>>)
    suspend fun clearStack()
}

interface ModelRepository {
    fun browse(query: BrowseQuery): Flow<PagingData<RemoteModel>>
    suspend fun details(source: ModelSource, id: String): RemoteModel
}

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadItem>>
    suspend fun enqueue(request: DownloadRequest): String
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
    suspend fun remove(id: String, deleteFile: Boolean)
    fun availableBytes(): Long
}

interface PromptRepository {
    fun observeHistory(limit: Int = 100): Flow<List<PromptEntry>>
    fun observeFavorites(): Flow<List<PromptEntry>>
    fun observeTemplates(): Flow<List<PromptTemplate>>
    suspend fun record(prompt: String, negativePrompt: String)
    suspend fun saveFavorite(prompt: String, negativePrompt: String, title: String?)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun delete(id: String)
    suspend fun clearHistory()
    suspend fun saveTemplate(template: PromptTemplate)
    suspend fun deleteTemplate(id: String)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    val draftParams: Flow<GenerationParameters?>
    suspend fun saveDraft(params: GenerationParameters)
}

data class HfAccount(val username: String, val displayName: String?, val tokenName: String?, val canUseInference: Boolean?)

data class AccountsState(
    val hasHfToken: Boolean = false,
    val hfAccount: HfAccount? = null,
    val hasCivitaiKey: Boolean = false,
)

interface AccountRepository {
    val state: Flow<AccountsState>
    suspend fun setHfToken(token: String?): HfAccount?
    suspend fun validateHfToken(): HfAccount
    suspend fun setCivitaiKey(key: String?)
}
