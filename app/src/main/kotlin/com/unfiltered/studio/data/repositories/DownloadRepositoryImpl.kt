package com.unfiltered.studio.data.repositories

import android.content.Context
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.data.database.dao.DownloadDao
import com.unfiltered.studio.data.database.dao.LoraDao
import com.unfiltered.studio.data.database.entities.DownloadEntity
import com.unfiltered.studio.data.storage.LoraMetadataParser
import com.unfiltered.studio.data.storage.SafetensorsReader
import com.unfiltered.studio.domain.models.DownloadItem
import com.unfiltered.studio.domain.models.DownloadRequest
import com.unfiltered.studio.domain.models.DownloadStatus
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraSource
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ModelKind
import com.unfiltered.studio.domain.models.ModelSource
import com.unfiltered.studio.domain.repositories.DownloadRepository
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.workers.ModelDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Metadata stored with a download so the LoRA library entry can be created when it completes. */
@Serializable
data class DownloadMeta(
    val modelId: String? = null,
    val modelName: String? = null,
    val versionId: String? = null,
    val baseModel: String? = null,
    val architecture: ModelArchitecture? = null,
    val triggerWords: List<String> = emptyList(),
    val previewUrl: String? = null,
    val description: String? = null,
    val hfRepoId: String? = null,
    val hfFile: String? = null,
    val nsfw: Boolean = false,
    val loraId: String? = null,
)

object ModelStorage {
    fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun dirFor(context: Context, kind: ModelKind): File = File(
        root(context),
        when (kind) {
            ModelKind.LORA -> "loras"
            ModelKind.CHECKPOINT, ModelKind.IMG2IMG -> "checkpoints"
            ModelKind.VAE -> "vae"
            ModelKind.TEXTUAL_INVERSION -> "embeddings"
            ModelKind.CONTROLNET -> "controlnet"
            ModelKind.OTHER -> "other"
        },
    ).apply { mkdirs() }

    fun sanitize(name: String): String = name.substringAfterLast('/').replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "model.safetensors" }
}

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
) : DownloadRepository {

    override fun observeDownloads(): Flow<List<DownloadItem>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun enqueue(request: DownloadRequest): String {
        val file = request.file
        val available = availableBytes()
        val size = file.sizeBytes
        if (size != null && size + 100L * 1024 * 1024 > available) {
            throw AppError.Storage("Not enough free space: needs ${Formatters.bytes(size)}, ${Formatters.bytes(available)} available.")
        }
        val meta = DownloadMeta(
            modelId = request.model?.id,
            modelName = request.model?.name ?: request.displayName,
            versionId = request.version?.id,
            baseModel = request.version?.baseModelLabel ?: request.model?.baseModelLabel,
            architecture = request.model?.architecture,
            triggerWords = request.version?.triggerWords.orEmpty(),
            previewUrl = (request.model?.previewUrls?.firstOrNull { !it.nsfw } ?: request.model?.previewUrls?.firstOrNull())?.url,
            description = request.model?.description?.take(2000),
            hfRepoId = if (request.source == ModelSource.HUGGING_FACE) request.model?.id else null,
            hfFile = if (request.source == ModelSource.HUGGING_FACE) file.name else null,
            nsfw = request.model?.nsfw ?: false,
            loraId = request.loraId,
        )
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsert(
            DownloadEntity(
                id = id,
                fileName = ModelStorage.sanitize(file.name),
                url = file.downloadUrl,
                kind = request.kind.name,
                source = request.source?.name,
                modelName = request.displayName ?: request.model?.name ?: file.name,
                totalBytes = file.sizeBytes,
                downloadedBytes = 0,
                status = DownloadStatus.QUEUED.name,
                error = null,
                filePath = null,
                sha256 = file.sha256,
                metaJson = AppJson.encodeToString(DownloadMeta.serializer(), meta),
                loraId = request.loraId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        schedule(id)
        return id
    }

    private suspend fun schedule(id: String) {
        val wifiOnly = settings.current().downloadOnWifiOnly
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_DOWNLOAD_ID to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ModelDownloadWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun pause(id: String) {
        dao.setStatus(id, DownloadStatus.PAUSED.name, null, System.currentTimeMillis())
        workManager.cancelUniqueWork(workName(id))
    }

    override suspend fun resume(id: String) {
        dao.setStatus(id, DownloadStatus.QUEUED.name, null, System.currentTimeMillis())
        schedule(id)
    }

    override suspend fun cancel(id: String) {
        dao.setStatus(id, DownloadStatus.CANCELLED.name, null, System.currentTimeMillis())
        workManager.cancelUniqueWork(workName(id))
        dao.get(id)?.let { partialFile(it).delete() }
    }

    override suspend fun remove(id: String, deleteFile: Boolean) {
        workManager.cancelUniqueWork(workName(id))
        val entity = dao.get(id) ?: return
        partialFile(entity).delete()
        if (deleteFile) entity.filePath?.let { File(it).delete() }
        dao.delete(id)
    }

    override fun availableBytes(): Long = runCatching { StatFs(ModelStorage.root(context).absolutePath).availableBytes }.getOrDefault(0L)

    private fun partialFile(entity: DownloadEntity) =
        File(ModelStorage.dirFor(context, runCatching { ModelKind.valueOf(entity.kind) }.getOrDefault(ModelKind.OTHER)), entity.fileName + ".part")

    private fun DownloadEntity.toDomain() = DownloadItem(
        id = id,
        fileName = fileName,
        url = url,
        kind = runCatching { ModelKind.valueOf(kind) }.getOrDefault(ModelKind.OTHER),
        source = source?.let { runCatching { ModelSource.valueOf(it) }.getOrNull() },
        modelName = modelName,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
        error = error,
        filePath = filePath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sha256 = sha256,
        loraId = loraId,
    )

    companion object {
        fun workName(id: String) = "download-$id"
    }
}

/** Creates or updates the LoRA library entry once a LoRA download has completed. */
@Singleton
class DownloadCompletionHandler @Inject constructor(private val loraDao: LoraDao) {

    suspend fun onCompleted(entity: DownloadEntity, file: File, sha256: String): String? {
        if (entity.kind != ModelKind.LORA.name) return null
        val meta = entity.metaJson?.let { runCatching { AppJson.decodeFromString(DownloadMeta.serializer(), it) }.getOrNull() } ?: DownloadMeta()
        val source = when (entity.source) {
            ModelSource.CIVITAI.name -> LoraSource.CIVITAI
            ModelSource.HUGGING_FACE.name -> LoraSource.HUGGING_FACE
            else -> LoraSource.MANUAL
        }
        val existing = meta.loraId?.let { loraDao.get(it) }
            ?: meta.versionId?.let { loraDao.findBySourceVersion(source.name, if (source == LoraSource.CIVITAI) it else "${meta.modelId}:${meta.hfFile}") }
            ?: loraDao.findBySha(sha256)
        val parsed = if (file.name.endsWith(".safetensors")) {
            runCatching { file.inputStream().use { SafetensorsReader.readHeader(it) } }.getOrNull()?.let { LoraMetadataParser.parse(it.metadata) }
        } else null
        val now = System.currentTimeMillis()
        val base = existing?.toDomain(emptyList()) ?: Lora(
            id = UUID.randomUUID().toString(),
            name = meta.modelName ?: parsed?.title ?: file.nameWithoutExtension,
            description = meta.description.orEmpty(),
            previewImageUrl = meta.previewUrl,
            triggerWords = meta.triggerWords,
            architecture = meta.architecture ?: ModelArchitecture.fromCivitaiBaseModel(meta.baseModel).takeIf { it != ModelArchitecture.OTHER },
            baseModelLabel = meta.baseModel,
            downloadUrl = entity.url,
            source = source,
            sourceId = meta.modelId,
            sourceVersionId = if (source == LoraSource.CIVITAI) meta.versionId else meta.modelId?.let { "$it:${meta.hfFile}" },
            hfRepoId = meta.hfRepoId,
            hfWeightFile = meta.hfFile,
            nsfw = meta.nsfw,
            createdAt = now,
        )
        val updated = base.copy(
            localFilePath = file.absolutePath,
            fileName = file.name,
            fileSizeBytes = file.length(),
            sha256 = sha256,
            triggerWords = base.triggerWords.ifEmpty { parsed?.triggerWords.orEmpty() },
            architecture = base.architecture ?: parsed?.architecture,
            downloadUrl = base.downloadUrl ?: entity.url,
        )
        loraDao.upsert(updated.toEntity())
        return updated.id
    }
}
