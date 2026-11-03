package com.unfiltered.studio.data.generation

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.domain.models.LiveProgress
import com.unfiltered.studio.workers.GenerationWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory channel between the generation worker and the UI: live step progress and preview
 * frames (too large/frequent for WorkManager progress data), plus cancel requests.
 */
@Singleton
class GenerationProgressBus @Inject constructor() {
    private val _live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = _live.asStateFlow()

    /** Ids of running jobs the user asked to cancel. Sticky, so a request can't be missed. */
    private val _cancelRequested = MutableStateFlow<Set<String>>(emptySet())
    val cancelRequested: StateFlow<Set<String>> = _cancelRequested.asStateFlow()

    fun start(id: String) = _live.update { it + (id to LiveProgress(id)) }

    fun update(id: String, transform: (LiveProgress) -> LiveProgress) = _live.update { map ->
        val current = map[id] ?: LiveProgress(id)
        map + (id to transform(current))
    }

    fun clear(id: String) = _live.update { it - id }

    fun requestCancel(id: String) = _cancelRequested.update { it + id }

    fun clearCancel(id: String) = _cancelRequested.update { it - id }
}

/**
 * Starts (or appends) the single generation runner. Appending avoids a race with a finishing runner.
 *
 * Deliberately no network constraint: WorkManager's CONNECTED maps to JobScheduler's "validated
 * internet", which never becomes true on LAN-only Wi-Fi, behind captive portals or when Google's
 * connectivity check is blocked — exactly where self-hosted servers live — and it would also stop
 * a running job whenever validation flaps. Unreachable servers fail the job with a clear error instead.
 */
@Singleton
class GenerationScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val dao: GenerationDao,
) {
    fun ensureRunning() {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Called on app start: drops the runner chain of 1.0.0 (which carried the network constraint and
     * could be stuck waiting forever) and restarts the runner when the queue still holds jobs.
     */
    suspend fun resumeOnStartup() {
        workManager.cancelUniqueWork(LEGACY_UNIQUE_NAME)
        if (dao.activeCount() == 0) return
        val runnerPending = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first().any { !it.state.isFinished }
        if (!runnerPending) ensureRunning()
    }

    companion object {
        const val UNIQUE_NAME = "generation-runner-v2"
        private const val LEGACY_UNIQUE_NAME = "generation-runner"
        const val TAG = "generation"
    }
}
