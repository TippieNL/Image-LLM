package com.unfiltered.studio.data.repositories

import com.unfiltered.studio.data.database.dao.PromptDao
import com.unfiltered.studio.data.database.entities.PromptEntity
import com.unfiltered.studio.data.database.entities.PromptTemplateEntity
import com.unfiltered.studio.domain.models.PromptEntry
import com.unfiltered.studio.domain.models.PromptTemplate
import com.unfiltered.studio.domain.repositories.PromptRepository
import com.unfiltered.studio.domain.usecases.TemplateApplier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptRepositoryImpl @Inject constructor(private val dao: PromptDao) : PromptRepository {

    override fun observeHistory(limit: Int): Flow<List<PromptEntry>> = dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<PromptEntry>> = dao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    override fun observeTemplates(): Flow<List<PromptTemplate>> = dao.observeTemplates().map { rows ->
        TemplateApplier.builtIns + rows.map { PromptTemplate(it.id, it.name, it.category, it.prompt, it.negativePrompt, builtIn = false) }
    }

    override suspend fun record(prompt: String, negativePrompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        val n = negativePrompt.trim()
        val now = System.currentTimeMillis()
        val existing = dao.find(p, n)
        dao.upsert(
            existing?.copy(useCount = existing.useCount + 1, lastUsedAt = now)
                ?: PromptEntity(UUID.randomUUID().toString(), p, n, null, isFavorite = false, useCount = 1, lastUsedAt = now, createdAt = now),
        )
    }

    override suspend fun saveFavorite(prompt: String, negativePrompt: String, title: String?) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        val n = negativePrompt.trim()
        val now = System.currentTimeMillis()
        val existing = dao.find(p, n)
        dao.upsert(
            existing?.copy(isFavorite = true, title = title ?: existing.title, lastUsedAt = now)
                ?: PromptEntity(UUID.randomUUID().toString(), p, n, title, isFavorite = true, useCount = 0, lastUsedAt = now, createdAt = now),
        )
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite)

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun clearHistory() = dao.clearNonFavorites()

    override suspend fun saveTemplate(template: PromptTemplate) {
        if (template.builtIn) return
        dao.upsertTemplate(
            PromptTemplateEntity(
                id = template.id.ifBlank { UUID.randomUUID().toString() },
                name = template.name.trim().ifBlank { "Untitled" },
                category = template.category.trim().ifBlank { "Custom" },
                prompt = template.prompt,
                negativePrompt = template.negativePrompt,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun deleteTemplate(id: String) = dao.deleteTemplate(id)

    private fun PromptEntity.toDomain() = PromptEntry(id, prompt, negativePrompt, isFavorite, title, useCount, lastUsedAt)
}
