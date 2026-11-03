package com.unfiltered.studio.data.repositories

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.data.database.dao.LoraDao
import com.unfiltered.studio.data.database.entities.ActiveLoraEntity
import com.unfiltered.studio.data.database.entities.LoraCategoryEntity
import com.unfiltered.studio.data.database.entities.LoraLinkEntity
import com.unfiltered.studio.data.network.civitai.CivitaiApi
import com.unfiltered.studio.data.network.civitai.CivitaiMapper
import com.unfiltered.studio.data.providers.ImagePayloads
import com.unfiltered.studio.data.providers.retrofitCall
import com.unfiltered.studio.data.storage.Hashing
import com.unfiltered.studio.data.storage.Hashing.toHex
import com.unfiltered.studio.data.storage.LoraMetadataParser
import com.unfiltered.studio.data.storage.SafetensorsReader
import com.unfiltered.studio.domain.models.ActiveLora
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraAvailabilityFilter
import com.unfiltered.studio.domain.models.LoraCategory
import com.unfiltered.studio.domain.models.LoraFilter
import com.unfiltered.studio.domain.models.LoraSource
import com.unfiltered.studio.domain.models.LoraSyncResult
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.models.RemoteModel
import com.unfiltered.studio.domain.providers.ProviderRegistry
import com.unfiltered.studio.domain.repositories.LoraRepository
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.usecases.GenerationPreparer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoraRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LoraDao,
    private val providers: ProviderRepository,
    private val registry: ProviderRegistry,
    private val civitai: CivitaiApi,
) : LoraRepository {

    private val lorasDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/loras").apply { mkdirs() }
    private val previewsDir: File get() = File(context.filesDir, "lora_previews").apply { mkdirs() }

    private val allLoras: Flow<List<Lora>> = combine(dao.observeAll(), dao.observeLinks()) { rows, links ->
        val byLora = links.groupBy { it.loraId }
        rows.map { it.toDomain(byLora[it.id].orEmpty()) }
    }

    override fun observeLoras(filter: LoraFilter): Flow<List<Lora>> = allLoras.map { list -> list.filter { matches(it, filter) } }

    private fun matches(lora: Lora, f: LoraFilter): Boolean {
        if (f.favoritesOnly && !lora.isFavorite) return false
        if (f.categoryId != null) {
            if (f.categoryId == UNCATEGORIZED) { if (lora.categoryId != null) return false } else if (lora.categoryId != f.categoryId) return false
        }
        if (f.family != null && lora.architecture?.family != f.family) return false
        when (f.availability) {
            LoraAvailabilityFilter.ALL -> Unit
            LoraAvailabilityFilter.ON_SERVER -> if (!lora.isOnServer(f.providerId)) return false
            LoraAvailabilityFilter.ON_DEVICE -> if (!lora.isOnDevice) return false
            LoraAvailabilityFilter.REMOTE_ONLY -> if (lora.isOnDevice || lora.isOnServer(f.providerId)) return false
        }
        val q = f.query.trim()
        if (q.isNotEmpty()) {
            val haystack = buildString {
                append(lora.name).append(' ').append(lora.description).append(' ')
                append(lora.triggerWords.joinToString(" ")).append(' ').append(lora.tags.joinToString(" ")).append(' ')
                append(lora.baseModelLabel.orEmpty()).append(' ').append(lora.fileName.orEmpty())
            }
            if (q.split(' ').filter { it.isNotBlank() }.any { !haystack.contains(it, ignoreCase = true) }) return false
        }
        return true
    }

    override fun observeLora(id: String): Flow<Lora?> = combine(dao.observe(id), dao.observeLinksFor(id)) { row, links -> row?.toDomain(links) }

    override suspend fun getLora(id: String): Lora? = dao.get(id)?.toDomain(dao.linksForLora(id))

    override fun observeCategories(): Flow<List<LoraCategory>> = combine(dao.observeCategories(), dao.observeAll()) { categories, loras ->
        categories.map { c -> LoraCategory(c.id, c.name, c.sortOrder, loras.count { it.categoryId == c.id }) }
    }

    override suspend fun save(lora: Lora) {
        if (lora.name.isBlank()) throw AppError.Configuration("A LoRA needs a name.")
        dao.upsert(lora.toEntity())
    }

    override suspend fun delete(id: String, deleteLocalFile: Boolean) {
        val lora = dao.get(id)
        if (deleteLocalFile) lora?.localFilePath?.let { runCatching { File(it).delete() } }
        lora?.previewImageUrl?.takeIf { it.startsWith(previewsDir.absolutePath) }?.let { runCatching { File(it).delete() } }
        dao.delete(id)
    }

    override suspend fun importFile(uri: Uri): Lora = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "lora_${System.currentTimeMillis()}.safetensors"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("safetensors", "pt", "ckpt", "bin")) {
            throw AppError.Configuration("Choose a LoRA file (.safetensors, .pt, .ckpt).")
        }
        val target = uniqueFile(lorasDir, displayName)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            } ?: throw AppError.Storage("Couldn't open the selected file.")
        } catch (e: Exception) {
            target.delete()
            throw if (e is AppError) e else AppError.Storage("Copying the file failed: ${e.message}", e)
        }
        val sha = digest.digest().toHex()
        dao.findBySha(sha)?.let { existing ->
            // The library already knows this file: keep exactly one device copy.
            val hasCopy = existing.localFilePath?.let { File(it).exists() } == true
            val path = if (hasCopy) {
                target.delete()
                existing.localFilePath
            } else {
                target.absolutePath
            }
            val updated = existing.copy(localFilePath = path, fileSizeBytes = File(path!!).length(), updatedAt = System.currentTimeMillis())
            dao.upsert(updated)
            return@withContext updated.toDomain(dao.linksForLora(existing.id))
        }
        val header = if (extension == "safetensors") runCatching { target.inputStream().use { SafetensorsReader.readHeader(it) } }.getOrNull() else null
        if (extension == "safetensors" && header == null) {
            target.delete()
            throw AppError.Configuration("The file isn't a valid .safetensors file.")
        }
        val parsed = header?.let { LoraMetadataParser.parse(it.metadata) }
        val id = UUID.randomUUID().toString()
        val lora = Lora(
            id = id,
            name = parsed?.title ?: displayName.substringBeforeLast('.'),
            description = parsed?.description.orEmpty(),
            previewImageUrl = parsed?.thumbnailDataUri?.let { savePreview(id, it) },
            triggerWords = parsed?.triggerWords.orEmpty(),
            architecture = parsed?.architecture ?: ModelArchitecture.detect(displayName),
            baseModelLabel = parsed?.baseModelLabel,
            source = LoraSource.LOCAL_IMPORT,
            fileName = target.name,
            fileSizeBytes = target.length(),
            sha256 = sha,
            localFilePath = target.absolutePath,
            tags = parsed?.tags.orEmpty(),
        )
        dao.upsert(lora.toEntity())
        lora
    }

    override suspend fun syncFromServer(providerId: String): LoraSyncResult {
        val catalog = providers.refreshCatalog(providerId)
        val serverLoras = catalog.loras
        val provider = registry.get(providerId)
        val existingLinks = dao.linksFor(providerId)
        val library = dao.getAll()
        val linkedIds = existingLinks.map { it.loraId }.toMutableSet()
        val now = System.currentTimeMillis()
        var added = 0
        var linked = 0
        var metadataFetches = 0
        for (backend in serverLoras) {
            if (existingLinks.any { it.backendName == backend.name }) continue
            val stem = GenerationPreparer.stem(backend.name)
            val match = library.firstOrNull { entry ->
                entry.id !in linkedIds && (entry.fileName?.let(GenerationPreparer::stem) == stem || GenerationPreparer.stem(entry.name) == stem)
            }
            if (match != null) {
                dao.upsertLink(LoraLinkEntity(match.id, providerId, backend.name, now))
                linkedIds += match.id
                linked++
                continue
            }
            var metadata = backend.metadata
            if (metadata.isEmpty() && provider != null && metadataFetches < MAX_METADATA_FETCHES) {
                metadataFetches++
                metadata = try {
                    provider.loraMetadata(backend.name).orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
            }
            val parsed = LoraMetadataParser.parse(metadata)
            val id = UUID.randomUUID().toString()
            val fileName = backend.name.substringAfterLast('/').substringAfterLast('\\')
            val lora = Lora(
                id = id,
                name = parsed.title ?: backend.displayName.substringBeforeLast('.'),
                description = parsed.description.orEmpty(),
                previewImageUrl = parsed.thumbnailDataUri?.let { savePreview(id, it) },
                triggerWords = parsed.triggerWords,
                architecture = backend.architecture ?: parsed.architecture,
                baseModelLabel = parsed.baseModelLabel,
                source = LoraSource.BACKEND,
                fileName = fileName,
                tags = parsed.tags,
                defaultStrength = 0.8f,
            )
            dao.upsert(lora.toEntity())
            dao.upsertLink(LoraLinkEntity(id, providerId, backend.name, now))
            linkedIds += id
            added++
        }
        val names = serverLoras.map { it.name }.toSet()
        val stale = existingLinks.filter { it.backendName !in names }
        stale.forEach { dao.deleteLink(it.loraId, providerId) }
        return LoraSyncResult(total = serverLoras.size, added = added, linked = linked, removedLinks = stale.size)
    }

    override suspend fun addFromRemote(model: RemoteModel, versionIndex: Int): Lora {
        val version = model.versions.getOrNull(versionIndex) ?: model.versions.firstOrNull()
        val file = version?.files?.firstOrNull { it.primary } ?: version?.files?.firstOrNull()
        val source = if (model.source == ModelSource.CIVITAI) LoraSource.CIVITAI else LoraSource.HUGGING_FACE
        val existing = version?.id?.let { dao.findBySourceVersion(source.name, if (source == LoraSource.CIVITAI) it else "${model.id}:${file?.name}") }
            ?: file?.sha256?.let { dao.findBySha(it) }
        val description = model.description?.let {
            if (model.descriptionIsHtml) HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else it
        }.orEmpty().take(4000)
        val preview = model.previewUrls.firstOrNull { !it.nsfw } ?: model.previewUrls.firstOrNull()
        val lora = Lora(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = existing?.name ?: buildString {
                append(model.name)
                if (model.versions.size > 1 && version != null) append(" (").append(version.name).append(')')
            },
            description = existing?.description?.ifBlank { null } ?: description,
            previewImageUrl = existing?.previewImageUrl ?: preview?.url,
            triggerWords = existing?.let { decodeStrings(it.triggerWordsJson) }?.ifEmpty { null } ?: version?.triggerWords.orEmpty(),
            architecture = if (source == LoraSource.CIVITAI) ModelArchitecture.fromCivitaiBaseModel(version?.baseModelLabel ?: model.baseModelLabel) else model.architecture,
            baseModelLabel = version?.baseModelLabel ?: model.baseModelLabel,
            downloadUrl = file?.downloadUrl,
            source = source,
            sourceId = model.id,
            sourceVersionId = if (source == LoraSource.CIVITAI) version?.id else "${model.id}:${file?.name}",
            hfRepoId = if (source == LoraSource.HUGGING_FACE) model.id else null,
            hfWeightFile = if (source == LoraSource.HUGGING_FACE) file?.name else null,
            fileName = existing?.fileName ?: file?.name?.substringAfterLast('/'),
            fileSizeBytes = file?.sizeBytes,
            sha256 = file?.sha256 ?: existing?.sha256,
            localFilePath = existing?.localFilePath,
            defaultStrength = existing?.defaultStrength ?: 0.8f,
            categoryId = existing?.categoryId,
            tags = model.tags.take(12),
            isFavorite = existing?.isFavorite ?: false,
            nsfw = model.nsfw,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        dao.upsert(lora.toEntity())
        return lora.copy(backendLinks = existing?.let { e -> dao.linksForLora(e.id).associate { it.providerId to it.backendName } }.orEmpty())
    }

    override suspend fun setLink(loraId: String, providerId: String, backendName: String?) {
        if (backendName.isNullOrBlank()) dao.deleteLink(loraId, providerId)
        else dao.upsertLink(LoraLinkEntity(loraId, providerId, backendName, System.currentTimeMillis()))
    }

    override suspend fun refreshServerMetadata(loraId: String, providerId: String): Lora? {
        val lora = getLora(loraId) ?: return null
        val backendName = lora.backendLinks[providerId] ?: throw AppError.Configuration("This LoRA isn't linked to that server.")
        val provider = registry.get(providerId) ?: throw AppError.Configuration("Provider not found.")
        val metadata = provider.loraMetadata(backendName)
            ?: providers.refreshCatalog(providerId).loras.firstOrNull { it.name == backendName }?.metadata
        if (metadata.isNullOrEmpty()) throw AppError.NotFound("Metadata for $backendName", "The file has no embedded training metadata.")
        val parsed = LoraMetadataParser.parse(metadata)
        val merged = lora.copy(
            triggerWords = lora.triggerWords.ifEmpty { parsed.triggerWords },
            architecture = lora.architecture ?: parsed.architecture,
            baseModelLabel = lora.baseModelLabel ?: parsed.baseModelLabel,
            description = lora.description.ifBlank { parsed.description.orEmpty() },
            previewImageUrl = lora.previewImageUrl ?: parsed.thumbnailDataUri?.let { savePreview(lora.id, it) },
            tags = lora.tags.ifEmpty { parsed.tags },
        )
        dao.upsert(merged.toEntity())
        return merged
    }

    override suspend fun lookupOnCivitai(loraId: String): Lora? {
        val lora = getLora(loraId) ?: return null
        val sha = lora.sha256 ?: lora.localFilePath?.let { path ->
            withContext(Dispatchers.IO) { File(path).takeIf { it.exists() }?.let { Hashing.sha256(it) } }
        } ?: throw AppError.Configuration("A file hash is needed: import or download the LoRA file to this device first.")
        val version = try {
            retrofitCall("Civitai", "civitai.com", "Civitai model version") { civitai.versionByHash(sha) }
        } catch (e: AppError.NotFound) {
            throw AppError.NotFound("A Civitai model with this file hash")
        }
        val mapped = CivitaiMapper.version(version)
        val file = mapped.files.firstOrNull { it.sha256.equals(sha, ignoreCase = true) } ?: mapped.files.firstOrNull()
        val merged = lora.copy(
            name = if (lora.source == LoraSource.BACKEND || lora.source == LoraSource.LOCAL_IMPORT) (version.model?.name ?: lora.name) else lora.name,
            triggerWords = lora.triggerWords.ifEmpty { mapped.triggerWords },
            architecture = ModelArchitecture.fromCivitaiBaseModel(mapped.baseModelLabel).takeIf { it != ModelArchitecture.OTHER } ?: lora.architecture,
            baseModelLabel = mapped.baseModelLabel ?: lora.baseModelLabel,
            previewImageUrl = lora.previewImageUrl ?: (mapped.previews.firstOrNull { !it.nsfw } ?: mapped.previews.firstOrNull())?.url,
            downloadUrl = lora.downloadUrl ?: file?.downloadUrl,
            sourceId = version.modelId?.toString() ?: lora.sourceId,
            sourceVersionId = version.id.toString(),
            sha256 = sha,
            nsfw = version.model?.nsfw ?: lora.nsfw,
        )
        dao.upsert(merged.toEntity())
        return merged
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite, System.currentTimeMillis())

    override suspend fun createCategory(name: String): LoraCategory {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw AppError.Configuration("Enter a category name.")
        val entity = LoraCategoryEntity(UUID.randomUUID().toString(), trimmed, dao.maxCategoryOrder() + 1)
        dao.upsertCategory(entity)
        return LoraCategory(entity.id, entity.name, entity.sortOrder)
    }

    override suspend fun renameCategory(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = dao.category(id) ?: return
        dao.upsertCategory(current.copy(name = trimmed))
    }

    override suspend fun deleteCategory(id: String) {
        dao.clearCategory(id)
        dao.deleteCategory(id)
    }

    override fun observeStack(): Flow<List<ActiveLora>> = combine(dao.observeStack(), allLoras) { stack, loras ->
        val byId = loras.associateBy { it.id }
        stack.mapNotNull { entry -> byId[entry.loraId]?.let { ActiveLora(it, entry.strength, entry.clipStrength, entry.enabled, entry.position) } }
    }

    override suspend fun addToStack(loraId: String) {
        if (dao.stack().any { it.loraId == loraId }) return
        val lora = dao.get(loraId) ?: return
        dao.upsertStack(ActiveLoraEntity(loraId, lora.defaultStrength, null, true, dao.maxStackPosition() + 1))
    }

    override suspend fun removeFromStack(loraId: String) = dao.removeFromStack(loraId)

    override suspend fun updateStackEntry(loraId: String, strength: Float?, clipStrength: Float?, enabled: Boolean?) {
        val current = dao.stack().firstOrNull { it.loraId == loraId } ?: return
        dao.upsertStack(
            current.copy(
                strength = strength ?: current.strength,
                clipStrength = clipStrength ?: current.clipStrength,
                enabled = enabled ?: current.enabled,
            ),
        )
    }

    override suspend fun replaceStack(entries: List<Triple<String, Float, Boolean>>) {
        dao.clearStack()
        entries.forEachIndexed { index, (id, strength, enabled) ->
            if (dao.get(id) != null) dao.upsertStack(ActiveLoraEntity(id, strength, null, enabled, index))
        }
    }

    override suspend fun clearStack() = dao.clearStack()

    private fun savePreview(id: String, dataUri: String): String? = runCatching {
        val (bytes, _) = ImagePayloads.decode(dataUri)
        val file = File(previewsDir, "$id.img")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        var candidate = File(dir, safe)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${safe.substringBeforeLast('.')}_$i.${safe.substringAfterLast('.', "safetensors")}")
            i++
        }
        return candidate
    }

    companion object {
        const val UNCATEGORIZED = "__uncategorized__"
        private const val MAX_METADATA_FETCHES = 40
    }
}
