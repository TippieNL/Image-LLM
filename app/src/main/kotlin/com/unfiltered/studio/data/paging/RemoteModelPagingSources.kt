package com.unfiltered.studio.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.unfiltered.studio.core.HttpErrors
import com.unfiltered.studio.core.toAppError
import com.unfiltered.studio.data.network.civitai.CivitaiApi
import com.unfiltered.studio.data.network.civitai.CivitaiMapper
import com.unfiltered.studio.data.network.civitai.CivitaiQueries
import com.unfiltered.studio.data.network.huggingface.HfHubApi
import com.unfiltered.studio.data.network.huggingface.HfMapper
import com.unfiltered.studio.data.network.huggingface.HfQueries
import com.unfiltered.studio.data.providers.retrofitCall
import com.unfiltered.studio.domain.models.BrowseQuery
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.RemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cursor-paged Hugging Face Hub listing. Page keys are the full URLs from the `Link: rel="next"` header. */
class HfModelPagingSource(private val api: HfHubApi, private val query: BrowseQuery) : PagingSource<String, RemoteModel>() {
    override fun getRefreshKey(state: PagingState<String, RemoteModel>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, RemoteModel> = try {
        val url = params.key ?: HfQueries.firstPageUrl(query, params.loadSize.coerceIn(10, 50))
        val response = api.listModels(url)
        if (!response.isSuccessful) {
            val body = withContext(Dispatchers.IO) { runCatching { response.errorBody()?.string() }.getOrNull() }
            throw HttpErrors.toAppError(response.code(), body, "Hugging Face", response.headers()["Retry-After"])
        }
        val models = response.body().orEmpty().map { HfMapper.toRemote(it) }.let { list ->
            // The Hub can't exclude tags server-side; drop adapters from checkpoint listings.
            if (query.kind == ModelKind.CHECKPOINT) list.filter { it.kind != ModelKind.LORA } else list
        }
        LoadResult.Page(models, prevKey = null, nextKey = HfQueries.nextLink(response.headers()["Link"]))
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        LoadResult.Error(e.toAppError("Hugging Face", "huggingface.co"))
    }
}

/** Cursor-paged Civitai listing. Page keys are `metadata.nextPage` URLs. */
class CivitaiPagingSource(private val api: CivitaiApi, private val query: BrowseQuery) : PagingSource<String, RemoteModel>() {
    override fun getRefreshKey(state: PagingState<String, RemoteModel>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, RemoteModel> = try {
        val url = params.key ?: CivitaiQueries.firstPageUrl(query, params.loadSize.coerceIn(10, 50))
        val page = retrofitCall("Civitai", "civitai.com", "Civitai model list") { api.models(url) }
        LoadResult.Page(page.items.map { CivitaiMapper.toRemote(it) }, prevKey = null, nextKey = page.metadata?.nextPage)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        LoadResult.Error(e.toAppError("Civitai", "civitai.com"))
    }
}
