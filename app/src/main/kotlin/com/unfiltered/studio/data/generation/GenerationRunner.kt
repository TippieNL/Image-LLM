package com.unfiltered.studio.data.generation

import android.util.Log
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.core.toAppError
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.data.database.entities.GenerationEntity
import com.unfiltered.studio.data.database.entities.ImageEntity
import com.unfiltered.studio.data.repositories.decodeParams
import com.unfiltered.studio.data.repositories.decodeStrings
import com.unfiltered.studio.data.repositories.encodeStrings
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.domain.models.GeneratedImageData
import com.unfiltered.studio.domain.models.GenerationEvent
import com.unfiltered.studio.domain.models.GenerationRequest
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.providers.ImageGenerationProvider
import com.unfiltered.studio.domain.providers.ProviderRegistry
import com.unfiltered.studio.domain.repositories.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes queued generations one at a time (driven by [com.unfiltered.studio.workers.GenerationWorker]).
 * Each job's events are written to the database (status, remote id, images) and to the progress bus.
 */
@Singleton
class GenerationRunner @Inject constructor(
    private val dao: GenerationDao,
    private val registry: ProviderRegistry,
    private val storage: ImageStorage,
    private val exporter: ImageExporter,
    private val bus: GenerationProgressBus,
    private val settings: SettingsRepository,
) {

    /** Jobs left RUNNING by a dead process: resume when the backend supports it, otherwise fail them. */
    suspend fun recoverInterrupted() {
        for (job in dao.running()) {
            val provider = registry.get(job.providerId)
            val request = GenerationRequest(job.id, job.providerId, decodeParams(job.paramsJson))
            val flow = job.remoteJobId?.let { remote -> provider?.recover(request, remote) }
            if (provider == null || flow == null) {
                dao.finish(job.id, GenerationStatus.FAILED.name, now(), "Interrupted: the app was closed while this was generating.", null)
            } else {
                execute(job, provider, flow)
            }
        }
    }

    /** Runs the oldest queued job. Returns false when the queue is empty. */
    suspend fun runNext(): Boolean {
        val next = dao.nextQueued() ?: return false
        val provider = registry.get(next.providerId)
        if (provider == null) {
            dao.finish(next.id, GenerationStatus.FAILED.name, now(), "The provider for this job was deleted.", null)
            return true
        }
        val request = GenerationRequest(next.id, next.providerId, decodeParams(next.paramsJson))
        execute(next, provider, provider.generate(request))
        return true
    }

    private suspend fun execute(job: GenerationEntity, provider: ImageGenerationProvider, events: Flow<GenerationEvent>) = coroutineScope {
        val userCancelled = AtomicBoolean(false)
        val worker = launch { collectJob(job, provider, events, userCancelled) }
        val watcher = launch {
            bus.cancelRequested.first { job.id in it }
            userCancelled.set(true)
            worker.cancel()
        }
        worker.join()
        watcher.cancel()
    }

    private suspend fun collectJob(
        job: GenerationEntity,
        provider: ImageGenerationProvider,
        events: Flow<GenerationEvent>,
        userCancelled: AtomicBoolean,
    ) {
        val id = job.id
        val params = decodeParams(job.paramsJson)
        val warnings = decodeStrings(job.warningsJson).toMutableList()
        var remoteJobId = job.remoteJobId
        var received = 0
        dao.markRunning(id, now())
        bus.start(id)
        try {
            events.collect { event ->
                when (event) {
                    is GenerationEvent.Submitted -> if (event.remoteJobId != null) {
                        remoteJobId = event.remoteJobId
                        dao.setRemoteJobId(id, event.remoteJobId)
                    }
                    is GenerationEvent.Queued -> bus.update(id) {
                        it.copy(phase = event.position?.let { p -> "Queued (#$p)" } ?: "Queued on server", queuePosition = event.position)
                    }
                    is GenerationEvent.Status -> bus.update(id) { it.copy(phase = event.message) }
                    is GenerationEvent.Warning -> warnings += event.message
                    is GenerationEvent.Progress -> bus.update(id) {
                        it.copy(
                            phase = event.message ?: "Generating",
                            fraction = event.fraction,
                            step = event.step,
                            totalSteps = event.totalSteps,
                            etaSeconds = event.etaSeconds,
                            queuePosition = null,
                        )
                    }
                    is GenerationEvent.Preview -> bus.update(id) { it.copy(preview = event.bytes) }
                    is GenerationEvent.Image -> {
                        saveImage(job, event.image, received, params.seed, warnings)
                        received++
                        bus.update(id) { it.copy(imagesReceived = received, phase = "Received $received image(s)") }
                    }
                    is GenerationEvent.Completed -> Unit
                }
            }
            if (received == 0) throw AppError.GenerationFailed("The provider finished without returning any images.")
            finish(id, GenerationStatus.SUCCEEDED, null, null, warnings)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (userCancelled.get()) {
                    runCatching { provider.cancel(remoteJobId) }
                    finish(id, if (received > 0) GenerationStatus.SUCCEEDED else GenerationStatus.CANCELLED, if (received > 0) null else "Cancelled", null, warnings)
                } else if (remoteJobId != null && provider.recover(GenerationRequest(id, job.providerId, params), remoteJobId) != null) {
                    // Worker stopped by the system; the job keeps running server-side and is resumed next run.
                    Log.i(TAG, "Generation $id paused by the system; will resume tracking.")
                } else {
                    finish(id, GenerationStatus.FAILED, "Interrupted by the system before the result arrived.", null, warnings)
                }
            }
            throw e
        } catch (e: Exception) {
            val error = e.toAppError(provider.config.name, provider.config.host)
            if (received > 0) {
                warnings += "Stopped early: ${error.userMessage}"
                finish(id, GenerationStatus.SUCCEEDED, null, null, warnings)
            } else {
                finish(id, GenerationStatus.FAILED, error.userMessage, error.technicalDetails, warnings)
            }
        } finally {
            bus.clear(id)
            bus.clearCancel(id)
        }
    }

    private suspend fun saveImage(job: GenerationEntity, data: GeneratedImageData, index: Int, baseSeed: Long, warnings: MutableList<String>) {
        val imageId = UUID.randomUUID().toString()
        val stored = storage.saveGenerated(imageId, data)
        dao.insertImage(
            ImageEntity(
                id = imageId,
                generationId = job.id,
                filePath = stored.file.absolutePath,
                thumbnailPath = stored.thumbnail.absolutePath,
                width = stored.width,
                height = stored.height,
                seed = data.seed ?: baseSeed.takeIf { it >= 0 },
                mimeType = stored.mimeType,
                sizeBytes = stored.sizeBytes,
                createdAt = now(),
                indexInBatch = index,
            ),
        )
        if (settings.current().autoSaveToDevice) {
            try {
                exporter.export(imageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += "Auto-save to device failed: ${(e as? AppError)?.userMessage ?: e.message}"
            }
        }
    }

    private suspend fun finish(id: String, status: GenerationStatus, error: String?, details: String?, warnings: List<String>) {
        dao.finish(id, status.name, now(), error, details)
        if (warnings.isNotEmpty()) dao.setWarnings(id, encodeStrings(warnings.distinct()))
    }

    private fun now() = System.currentTimeMillis()

    private companion object { const val TAG = "GenerationRunner" }
}
