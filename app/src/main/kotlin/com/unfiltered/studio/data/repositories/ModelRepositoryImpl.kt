package com.unfiltered.studio.data.repositories

import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.unfiltered.studio.data.network.civitai.CivitaiApi
import com.unfiltered.studio.data.network.civitai.CivitaiMapper
import com.unfiltered.studio.data.network.huggingface.HfHubApi
import com.unfiltered.studio.data.network.huggingface.HfMapper
import com.unfiltered.studio.data.network.huggingface.HfQueries
import com.unfiltered.studio.data.paging.CivitaiPagingSource
import com.unfiltered.studio.data.paging.HfModelPagingSource
import com.unfiltered.studio.data.providers.retrofitCall
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.RemoteModel
import com.unfiltered.studio.domain.repositories.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val hf: HfHubApi,
    private val civitai: CivitaiApi,
) : ModelRepository {

    /** Metadata cache: the HTTP cache covers restarts, this avoids re-parsing within a session. */
    private val details = LruCache<String, Pair<Long, RemoteModel>>(64)

    override fun browse(query: BrowseQuery): Flow<PagingData<RemoteModel>> =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false)) {
            when (query.source) {
                ModelSource.HUGGING_FACE -> HfModelPagingSource(hf, query)
                ModelSource.CIVITAI -> CivitaiPagingSource(civitai, query)
            }
        }.flow

    override suspend fun details(source: ModelSource, id: String): RemoteModel {
        val key = "${source.name}:$id"
        details.get(key)?.let { (at, model) -> if (System.currentTimeMillis() - at < CACHE_MS) return model }
        val model = when (source) {
            ModelSource.HUGGING_FACE -> coroutineScope {
                val dto = async { retrofitCall("Hugging Face", "huggingface.co", "Model $id") { hf.model(id, HfQueries.DETAIL_EXPAND) } }
                val tree = async { optional { hf.tree(id) } }
                val readme = async {
                    optional { withContext(Dispatchers.IO) { hf.raw("${HfQueries.BASE}$id/raw/main/README.md").string() } }
                }
                HfMapper.toRemote(dto.await(), readme.await()?.let { HfMapper.stripFrontMatter(it).take(30_000) }, tree.await())
            }
            ModelSource.CIVITAI -> CivitaiMapper.toRemote(
                retrofitCall("Civitai", "civitai.com", "Model $id") { civitai.model(id.toLong()) },
            )
        }
        details.put(key, System.currentTimeMillis() to model)
        return model
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        null
    }

    private companion object { const val CACHE_MS = 10 * 60_000L }
}
