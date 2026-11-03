package com.unfiltered.studio.workers

import android.content.Context
import android.os.StatFs
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.Formatters
import com.unfiltered.studio.core.HttpErrors
import com.unfiltered.studio.core.toAppError
import com.unfiltered.studio.data.database.dao.DownloadDao
import com.unfiltered.studio.data.network.await
import com.unfiltered.studio.data.repositories.DownloadCompletionHandler
import com.unfiltered.studio.data.repositories.ModelStorage
import com.unfiltered.studio.data.storage.Hashing
import com.unfiltered.studio.data.storage.Hashing.toHex
import com.unfiltered.studio.di.DownloadClient
import com.unfiltered.studio.domain.models.DownloadStatus
import com.unfiltered.studio.domain.models.ModelKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Resumable model download (HTTP Range). Pausing cancels the work but keeps the `.part` file; the
 * next run continues from its size. Integrity is verified against the published SHA-256 when known.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    @DownloadClient private val client: OkHttpClient,
    private val completion: DownloadCompletionHandler,
    private val notifications: AppNotifications,
) : CoroutineWorker(context, params) {

    private val downloadId: String? get() = inputData.getString(KEY_DOWNLOAD_ID)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.downloadForegroundInfo(downloadId.orEmpty(), "Downloading model", null)

    override suspend fun doWork(): Result {
        val id = downloadId ?: return Result.failure()
        val entity = dao.get(id) ?: return Result.success()
        if (entity.status in setOf(DownloadStatus.PAUSED.name, DownloadStatus.CANCELLED.name, DownloadStatus.COMPLETED.name)) return Result.success()

        val kind = runCatching { ModelKind.valueOf(entity.kind) }.getOrDefault(ModelKind.OTHER)
        val dir = ModelStorage.dirFor(applicationContext, kind)
        val part = File(dir, entity.fileName + ".part")
        runCatching { setForeground(notifications.downloadForegroundInfo(id, entity.modelName, null)) }
        dao.setStatus(id, DownloadStatus.RUNNING.name, null, System.currentTimeMillis())

        return try {
            val existing = if (part.exists()) part.length() else 0L
            val remaining = (entity.totalBytes ?: 0L) - existing
            val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (remaining > 0 && remaining + 50L * 1024 * 1024 > free) {
                throw AppError.Storage("Not enough free space: ${Formatters.bytes(remaining)} needed, ${Formatters.bytes(free)} free.")
            }
            val request = Request.Builder().url(entity.url).apply { if (existing > 0) header("Range", "bytes=$existing-") }.build()
            val response = try {
                client.newCall(request).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.toAppError("Download", request.url.host)
            }
            var digest: MessageDigest? = null
            response.use { r ->
                if (r.code == 416 && existing > 0 && (entity.totalBytes == null || existing >= entity.totalBytes)) {
                    return@use // Already complete.
                }
                if (!r.isSuccessful) {
                    val body = withContext(Dispatchers.IO) { runCatching { r.body.string().take(500) }.getOrNull() }
                    throw HttpErrors.toAppError(r.code, body, request.url.host, r.header("Retry-After"), "Download URL")
                }
                val append = r.code == 206 && existing > 0
                val startAt = if (append) existing else 0L
                if (!append) digest = MessageDigest.getInstance("SHA-256")
                val contentLength = r.body.contentLength().takeIf { it > 0 }
                val total = contentLength?.plus(startAt) ?: entity.totalBytes
                var downloaded = startAt
                var lastUpdate = 0L
                var lastBytes = downloaded
                withContext(Dispatchers.IO) {
                    FileOutputStream(part, append).use { out ->
                        r.body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest?.update(buffer, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 750) {
                                    val speed = if (lastUpdate > 0) (downloaded - lastBytes) * 1000 / (now - lastUpdate) else 0
                                    lastUpdate = now
                                    lastBytes = downloaded
                                    dao.progress(id, downloaded, total, DownloadStatus.RUNNING.name, now)
                                    setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to (total ?: -1L), KEY_SPEED to speed))
                                    notifications.updateDownload(id, entity.modelName, total?.let { downloaded.toFloat() / it })
                                }
                            }
                        }
                    }
                }
            }
            val target = uniqueTarget(dir, entity.fileName)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            val sha = digest?.digest()?.toHex() ?: withContext(Dispatchers.IO) { Hashing.sha256(target) }
            if (entity.sha256 != null && !entity.sha256.equals(sha, ignoreCase = true)) {
                target.delete()
                throw AppError.Storage("Checksum mismatch — the download is corrupted. Retry the download.")
            }
            val loraId = completion.onCompleted(entity, target, sha)
            dao.complete(id, target.absolutePath, sha, loraId, target.length(), System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                when (dao.get(id)?.status) {
                    DownloadStatus.CANCELLED.name -> part.delete()
                    DownloadStatus.RUNNING.name -> dao.setStatus(id, DownloadStatus.QUEUED.name, "Waiting for network…", System.currentTimeMillis())
                    else -> Unit
                }
            }
            throw e
        } catch (e: Exception) {
            val error = e.toAppError("Download")
            if (error.isTransient && runAttemptCount < MAX_ATTEMPTS) {
                dao.setStatus(id, DownloadStatus.QUEUED.name, "${error.userMessage} Retrying…", System.currentTimeMillis())
                Result.retry()
            } else {
                dao.setStatus(id, DownloadStatus.FAILED.name, error.userMessage, System.currentTimeMillis())
                Result.failure()
            }
        }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${name.substringBeforeLast('.')}_$i.${name.substringAfterLast('.', "bin")}")
            i++
        }
        return candidate
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_SPEED = "speed"
        const val TAG = "model-download"
        private const val MAX_ATTEMPTS = 5
    }
}
