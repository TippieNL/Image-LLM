package com.unfiltered.studio.data.repositories

import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.data.database.entities.GenerationEntity
import com.unfiltered.studio.data.database.entities.GenerationLoraEntity
import com.unfiltered.studio.data.generation.GenerationProgressBus
import com.unfiltered.studio.data.generation.GenerationScheduler
import com.unfiltered.studio.data.generation.ImageExporter
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.GalleryFacets
import com.unfiltered.studio.domain.models.GalleryFilter
import com.unfiltered.studio.domain.models.GalleryImage
import com.unfiltered.studio.domain.models.GallerySort
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.models.LiveProgress
import com.unfiltered.studio.domain.models.PreparedGeneration
import com.unfiltered.studio.domain.providers.ProviderRegistry
import com.unfiltered.studio.domain.repositories.GenerationHistoryRepository
import com.unfiltered.studio.domain.repositories.GenerationRepository
import com.unfiltered.studio.domain.repositories.PromptRepository
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.usecases.GenerationPreparer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class GenerationRepositoryImpl @Inject constructor(
    private val dao: GenerationDao,
    private val store: ProviderStore,
    private val registry: ProviderRegistry,
    private val providers: ProviderRepository,
    private val prompts: PromptRepository,
    private val scheduler: GenerationScheduler,
    private val bus: GenerationProgressBus,
) : GenerationRepository {

    private val preparer = GenerationPreparer()

    override suspend fun prepare(providerId: String, params: GenerationParameters, stack: List<ActiveLora>): PreparedGeneration {
        val config = store.get(providerId)
            ?: return PreparedGeneration(params, emptyList(), listOf("Add and select a provider in Settings first."))
        val provider = registry.get(providerId)
            ?: return PreparedGeneration(params, emptyList(), listOf("The provider couldn't be initialised."))
        val catalog = runCatching { providers.refreshModelInfo(providerId, params.checkpoint) }.getOrNull()
            ?: providers.observeCatalog(providerId).first()
        val caps = provider.capabilities(catalog, params)
        return preparer.prepare(config, caps, catalog, params, stack)
    }

    override suspend fun enqueue(providerId: String, params: GenerationParameters, warnings: List<String>): String {
        val config = store.get(providerId) ?: throw AppError.Configuration("Provider not found.")
        val id = UUID.randomUUID().toString()
        dao.insert(
            GenerationEntity(
                id = id,
                createdAt = System.currentTimeMillis(),
                providerId = providerId,
                providerName = config.name,
                providerType = config.type.name,
                status = GenerationStatus.QUEUED.name,
                paramsJson = encodeParams(params),
                prompt = params.prompt,
                negativePrompt = params.negativePrompt,
                model = params.checkpoint?.ifBlank { null },
                seed = params.seed,
                width = params.width,
                height = params.height,
                steps = params.steps,
                cfgScale = params.cfgScale,
                sampler = params.sampler,
                warningsJson = warnings.takeIf { it.isNotEmpty() }?.let { encodeStrings(it) },
            ),
        )
        val loraRows = params.loras.filter { it.enabled }.distinctBy { it.name }.map { GenerationLoraEntity(id, it.name, it.loraId, it.strength) }
        if (loraRows.isNotEmpty()) dao.insertLoras(loraRows)
        prompts.record(params.prompt, params.negativePrompt)
        scheduler.ensureRunning()
        return id
    }

    override suspend fun cancel(generationId: String) {
        if (dao.cancelIfQueued(generationId, System.currentTimeMillis()) == 0) bus.requestCancel(generationId)
    }

    override suspend fun cancelAll() {
        dao.cancelAllQueued(System.currentTimeMillis())
        dao.running().forEach { bus.requestCancel(it.id) }
    }

    override suspend fun retry(generationId: String): String? {
        val g = dao.get(generationId) ?: return null
        return enqueue(g.providerId, decodeParams(g.paramsJson), emptyList())
    }

    override suspend fun regenerate(generationId: String, newSeed: Boolean): String? {
        val g = dao.get(generationId) ?: return null
        val params = decodeParams(g.paramsJson).let { p ->
            if (newSeed) p.copy(seed = Random.nextLong(0, GenerationPreparer.MAX_SEED)) else p
        }
        return enqueue(g.providerId, params, emptyList())
    }

    override fun observeActive(): Flow<List<Generation>> = dao.observeActive().map { rows -> rows.map { it.toDomain() } }

    override fun observeLive(): StateFlow<Map<String, LiveProgress>> = bus.live

    override fun observeLatestFinished(): Flow<Generation?> = dao.observeLatestFinished().map { it?.toDomain() }
}

@Singleton
class GenerationHistoryRepositoryImpl @Inject constructor(
    private val dao: GenerationDao,
    private val storage: ImageStorage,
    private val exporter: ImageExporter,
) : GenerationHistoryRepository {

    private val pagingConfig = PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)

    override fun pagedImages(filter: GalleryFilter): Flow<PagingData<GalleryImage>> = Pager(pagingConfig) {
        dao.pagedImages(
            query = filter.query.trim(),
            favoritesOnly = filter.favoritesOnly,
            providerId = filter.providerId,
            model = filter.model,
            lora = filter.loraName,
            fromMs = filter.fromMs,
            toMs = filter.toMs,
            newestFirst = filter.sort == GallerySort.NEWEST,
        )
    }.flow.map { data -> data.map { it.toDomain() } }

    override fun observeImageIds(filter: GalleryFilter): Flow<List<String>> = dao.observeImageIds(
        query = filter.query.trim(),
        favoritesOnly = filter.favoritesOnly,
        providerId = filter.providerId,
        model = filter.model,
        lora = filter.loraName,
        fromMs = filter.fromMs,
        toMs = filter.toMs,
        newestFirst = filter.sort == GallerySort.NEWEST,
    )

    override fun pagedGenerations(query: String, status: GenerationStatus?): Flow<PagingData<Generation>> =
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) { dao.pagedHistory(query.trim(), status?.name) }
            .flow.map { data -> data.map { it.toDomain() } }

    override fun observeGeneration(id: String): Flow<Generation?> = dao.observeWithImages(id).map { it?.toDomain() }

    override fun observeImage(id: String): Flow<GalleryImage?> = dao.observeImage(id).map { it?.toDomain() }

    override fun observeFacets(): Flow<GalleryFacets> = combine(dao.observeModels(), dao.observeLoraNames(), dao.observeProviders()) { models, loras, providers ->
        GalleryFacets(models, loras, providers.map { it.providerId to it.providerName })
    }

    override suspend fun setFavorite(imageIds: Collection<String>, favorite: Boolean) = dao.setFavorite(imageIds.toList(), favorite)

    override suspend fun deleteImages(imageIds: Collection<String>) {
        val images = dao.imagesByIds(imageIds.toList())
        dao.deleteImages(images.map { it.id })
        storage.delete(images.flatMap { listOf(it.filePath, it.thumbnailPath) })
        images.map { it.generationId }.distinct().forEach { dao.deleteIfEmpty(it) }
    }

    override suspend fun deleteGeneration(id: String) {
        val images = dao.imagesFor(id)
        storage.delete(images.flatMap { listOf(it.filePath, it.thumbnailPath) })
        dao.delete(id)
    }

    override suspend fun export(imageIds: Collection<String>): Int {
        var count = 0
        var lastError: Exception? = null
        for (id in imageIds) {
            try {
                exporter.export(id)
                count++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (count == 0 && lastError != null) throw lastError
        return count
    }

    override suspend fun shareUris(imageIds: Collection<String>): List<Uri> =
        dao.imagesByIds(imageIds.toList()).map { storage.shareUri(it.filePath) }
}
