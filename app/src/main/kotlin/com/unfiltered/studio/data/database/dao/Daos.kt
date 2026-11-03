package com.unfiltered.studio.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.unfiltered.studio.data.database.entities.ActiveLoraEntity
import com.unfiltered.studio.data.database.entities.CatalogCacheEntity
import com.unfiltered.studio.data.database.entities.DownloadEntity
import com.unfiltered.studio.data.database.entities.GenerationEntity
import com.unfiltered.studio.data.database.entities.GenerationLoraEntity
import com.unfiltered.studio.data.database.entities.GenerationWithImages
import com.unfiltered.studio.data.database.entities.ImageEntity
import com.unfiltered.studio.data.database.entities.ImageRow
import com.unfiltered.studio.data.database.entities.LoraCategoryEntity
import com.unfiltered.studio.data.database.entities.LoraEntity
import com.unfiltered.studio.data.database.entities.LoraLinkEntity
import com.unfiltered.studio.data.database.entities.PromptEntity
import com.unfiltered.studio.data.database.entities.PromptTemplateEntity
import com.unfiltered.studio.data.database.entities.ProviderEntity
import com.unfiltered.studio.data.database.entities.ProviderFacetRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY sortOrder, createdAt")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observe(id: String): Flow<ProviderEntity?>

    @Upsert
    suspend fun upsert(entity: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM providers")
    suspend fun maxSortOrder(): Int
}

/** Shared WHERE clause for gallery queries (kept in sync between paging and id queries). */
private const val IMAGE_FILTER = """
    FROM images INNER JOIN generations ON images.generationId = generations.id
    WHERE (:query = '' OR generations.prompt LIKE '%' || :query || '%' OR generations.negativePrompt LIKE '%' || :query || '%'
           OR generations.model LIKE '%' || :query || '%')
      AND (:favoritesOnly = 0 OR images.isFavorite = 1)
      AND (:providerId IS NULL OR generations.providerId = :providerId)
      AND (:model IS NULL OR generations.model = :model)
      AND (:lora IS NULL OR EXISTS (SELECT 1 FROM generation_loras gl WHERE gl.generationId = generations.id AND gl.name = :lora))
      AND (:fromMs IS NULL OR images.createdAt >= :fromMs)
      AND (:toMs IS NULL OR images.createdAt <= :toMs)
"""

private const val IMAGE_ORDER = """
    ORDER BY CASE WHEN :newestFirst = 1 THEN images.createdAt END DESC,
             CASE WHEN :newestFirst = 0 THEN images.createdAt END ASC,
             images.indexInBatch ASC
"""

@Dao
interface GenerationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GenerationEntity)

    @Query("SELECT * FROM generations WHERE id = :id")
    suspend fun get(id: String): GenerationEntity?

    @Transaction
    @Query("SELECT * FROM generations WHERE id = :id")
    fun observeWithImages(id: String): Flow<GenerationWithImages?>

    @Query("SELECT * FROM generations WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): GenerationEntity?

    @Query("SELECT * FROM generations WHERE status = 'RUNNING'")
    suspend fun running(): List<GenerationEntity>

    @Query("SELECT COUNT(*) FROM generations WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun activeCount(): Int

    @Query("SELECT * FROM generations WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<GenerationEntity>>

    /** Latest result for the Generate preview. Cancelled jobs are skipped so stopping a job keeps the last image (History lists them). */
    @Transaction
    @Query("SELECT * FROM generations WHERE status IN ('SUCCEEDED', 'FAILED') ORDER BY COALESCE(finishedAt, createdAt) DESC LIMIT 1")
    fun observeLatestFinished(): Flow<GenerationWithImages?>

    @Query("UPDATE generations SET status = 'RUNNING', startedAt = :startedAt, error = NULL, errorDetails = NULL WHERE id = :id")
    suspend fun markRunning(id: String, startedAt: Long)

    @Query("UPDATE generations SET remoteJobId = :remoteJobId WHERE id = :id")
    suspend fun setRemoteJobId(id: String, remoteJobId: String?)

    @Query("UPDATE generations SET status = :status, finishedAt = :finishedAt, error = :error, errorDetails = :details WHERE id = :id")
    suspend fun finish(id: String, status: String, finishedAt: Long, error: String?, details: String?)

    @Query("UPDATE generations SET status = 'CANCELLED', finishedAt = :now WHERE status = 'QUEUED'")
    suspend fun cancelAllQueued(now: Long)

    @Query("UPDATE generations SET status = 'CANCELLED', finishedAt = :now WHERE id = :id AND status = 'QUEUED'")
    suspend fun cancelIfQueued(id: String, now: Long): Int

    @Query("UPDATE generations SET seed = :seed WHERE id = :id")
    suspend fun updateSeed(id: String, seed: Long)

    @Query("UPDATE generations SET warningsJson = :json WHERE id = :id")
    suspend fun setWarnings(id: String, json: String)

    @Query("DELETE FROM generations WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoras(rows: List<GenerationLoraEntity>)

    // ---- images ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: ImageEntity)

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun imagesByIds(ids: List<String>): List<ImageEntity>

    @Query("SELECT * FROM images WHERE generationId = :generationId ORDER BY indexInBatch")
    suspend fun imagesFor(generationId: String): List<ImageEntity>

    @Query(
        """SELECT images.*, generations.prompt AS prompt, generations.model AS modelName, generations.providerName AS providerName
           FROM images INNER JOIN generations ON images.generationId = generations.id WHERE images.id = :id""",
    )
    fun observeImage(id: String): Flow<ImageRow?>

    @Query("UPDATE images SET isFavorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    @Query("UPDATE images SET exportedUri = :uri WHERE id = :id")
    suspend fun setExported(id: String, uri: String)

    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteImages(ids: List<String>)

    @Query("DELETE FROM generations WHERE id = :generationId AND NOT EXISTS (SELECT 1 FROM images WHERE generationId = :generationId)")
    suspend fun deleteIfEmpty(generationId: String)

    @Query(
        "SELECT images.*, generations.prompt AS prompt, generations.model AS modelName, generations.providerName AS providerName " +
            IMAGE_FILTER + IMAGE_ORDER,
    )
    fun pagedImages(
        query: String,
        favoritesOnly: Boolean,
        providerId: String?,
        model: String?,
        lora: String?,
        fromMs: Long?,
        toMs: Long?,
        newestFirst: Boolean,
    ): PagingSource<Int, ImageRow>

    @Query("SELECT images.id " + IMAGE_FILTER + IMAGE_ORDER)
    fun observeImageIds(
        query: String,
        favoritesOnly: Boolean,
        providerId: String?,
        model: String?,
        lora: String?,
        fromMs: Long?,
        toMs: Long?,
        newestFirst: Boolean,
    ): Flow<List<String>>

    @Transaction
    @Query(
        """SELECT * FROM generations
           WHERE status NOT IN ('QUEUED', 'RUNNING')
             AND (:query = '' OR prompt LIKE '%' || :query || '%' OR model LIKE '%' || :query || '%')
             AND (:status IS NULL OR status = :status)
           ORDER BY createdAt DESC""",
    )
    fun pagedHistory(query: String, status: String?): PagingSource<Int, GenerationWithImages>

    @Query("SELECT DISTINCT model FROM generations WHERE model IS NOT NULL AND status = 'SUCCEEDED' ORDER BY model COLLATE NOCASE")
    fun observeModels(): Flow<List<String>>

    @Query("SELECT DISTINCT name FROM generation_loras ORDER BY name COLLATE NOCASE")
    fun observeLoraNames(): Flow<List<String>>

    @Query("SELECT DISTINCT providerId, providerName FROM generations WHERE status = 'SUCCEEDED' ORDER BY providerName COLLATE NOCASE")
    fun observeProviders(): Flow<List<ProviderFacetRow>>
}

@Dao
interface LoraDao {
    @Query("SELECT * FROM loras ORDER BY isFavorite DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<LoraEntity>>

    @Query("SELECT * FROM loras")
    suspend fun getAll(): List<LoraEntity>

    @Query("SELECT * FROM loras WHERE id = :id")
    fun observe(id: String): Flow<LoraEntity?>

    @Query("SELECT * FROM loras WHERE id = :id")
    suspend fun get(id: String): LoraEntity?

    @Query("SELECT * FROM loras WHERE sourceVersionId = :versionId AND source = :source LIMIT 1")
    suspend fun findBySourceVersion(source: String, versionId: String): LoraEntity?

    @Query("SELECT * FROM loras WHERE sha256 = :sha LIMIT 1")
    suspend fun findBySha(sha: String): LoraEntity?

    @Upsert
    suspend fun upsert(entity: LoraEntity)

    @Query("DELETE FROM loras WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE loras SET isFavorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long)

    @Query("UPDATE loras SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: String)

    // links
    @Query("SELECT * FROM lora_links")
    fun observeLinks(): Flow<List<LoraLinkEntity>>

    @Query("SELECT * FROM lora_links WHERE loraId = :loraId")
    fun observeLinksFor(loraId: String): Flow<List<LoraLinkEntity>>

    @Query("SELECT * FROM lora_links WHERE providerId = :providerId")
    suspend fun linksFor(providerId: String): List<LoraLinkEntity>

    @Query("SELECT * FROM lora_links WHERE loraId = :loraId")
    suspend fun linksForLora(loraId: String): List<LoraLinkEntity>

    @Upsert
    suspend fun upsertLink(link: LoraLinkEntity)

    @Query("DELETE FROM lora_links WHERE loraId = :loraId AND providerId = :providerId")
    suspend fun deleteLink(loraId: String, providerId: String)

    // categories
    @Query(
        """SELECT c.id AS id, c.name AS name, c.sortOrder AS sortOrder FROM lora_categories c ORDER BY c.sortOrder, c.name COLLATE NOCASE""",
    )
    fun observeCategories(): Flow<List<LoraCategoryEntity>>

    @Query("SELECT * FROM lora_categories WHERE id = :id")
    suspend fun category(id: String): LoraCategoryEntity?

    @Upsert
    suspend fun upsertCategory(entity: LoraCategoryEntity)

    @Query("DELETE FROM lora_categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM lora_categories")
    suspend fun maxCategoryOrder(): Int

    // active stack
    @Query("SELECT * FROM active_loras ORDER BY position")
    fun observeStack(): Flow<List<ActiveLoraEntity>>

    @Query("SELECT * FROM active_loras ORDER BY position")
    suspend fun stack(): List<ActiveLoraEntity>

    @Upsert
    suspend fun upsertStack(entity: ActiveLoraEntity)

    @Query("DELETE FROM active_loras WHERE loraId = :loraId")
    suspend fun removeFromStack(loraId: String)

    @Query("DELETE FROM active_loras")
    suspend fun clearStack()

    @Query("SELECT COALESCE(MAX(position), -1) FROM active_loras")
    suspend fun maxStackPosition(): Int
}

@Dao
interface PromptDao {
    @Query("SELECT * FROM prompts ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE isFavorite = 1 ORDER BY lastUsedAt DESC")
    fun observeFavorites(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE prompt = :prompt AND negativePrompt = :negative LIMIT 1")
    suspend fun find(prompt: String, negative: String): PromptEntity?

    @Upsert
    suspend fun upsert(entity: PromptEntity)

    @Query("UPDATE prompts SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM prompts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM prompts WHERE isFavorite = 0")
    suspend fun clearNonFavorites()

    @Query("SELECT * FROM prompt_templates ORDER BY category, name COLLATE NOCASE")
    fun observeTemplates(): Flow<List<PromptTemplateEntity>>

    @Upsert
    suspend fun upsertTemplate(entity: PromptTemplateEntity)

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun progress(id: String, downloaded: Long, total: Long?, status: String, now: Long)

    @Query("UPDATE downloads SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?, now: Long)

    @Query("UPDATE downloads SET status = 'COMPLETED', filePath = :path, sha256 = COALESCE(:sha, sha256), loraId = :loraId, downloadedBytes = :size, totalBytes = :size, error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun complete(id: String, path: String, sha: String?, loraId: String?, size: Long, now: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_cache WHERE providerId = :providerId")
    fun observe(providerId: String): Flow<CatalogCacheEntity?>

    @Query("SELECT * FROM catalog_cache WHERE providerId = :providerId")
    suspend fun get(providerId: String): CatalogCacheEntity?

    @Upsert
    suspend fun upsert(entity: CatalogCacheEntity)

    @Query("DELETE FROM catalog_cache WHERE providerId = :providerId")
    suspend fun delete(providerId: String)
}
