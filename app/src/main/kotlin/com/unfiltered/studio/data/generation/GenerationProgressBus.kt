package com.unfiltered.studio.data.generation

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.unfiltered.studio.domain.models.LiveProgress
import com.unfiltered.studio.workers.GenerationWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Starts (or appends) the single generation runner. Appending avoids a race with a finishing runner. */
@Singleton
class GenerationScheduler @Inject constructor(private val workManager: WorkManager) {
    fun ensureRunning() {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val UNIQUE_NAME = "generation-runner"
        const val TAG = "generation"
    }
}
