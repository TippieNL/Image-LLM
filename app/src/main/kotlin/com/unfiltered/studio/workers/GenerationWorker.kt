package com.unfiltered.studio.workers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.data.generation.GenerationProgressBus
import com.unfiltered.studio.data.generation.GenerationRunner
import com.unfiltered.studio.di.ApplicationScope
import com.unfiltered.studio.domain.repositories.GenerationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drains the generation queue as a foreground (dataSync) worker so long ComfyUI/A1111 jobs keep
 * running when the user leaves the app. One runner at a time (unique work, appended).
 */
@HiltWorker
class GenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: GenerationRunner,
    private val bus: GenerationProgressBus,
    private val notifications: AppNotifications,
    private val dao: GenerationDao,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.generationForegroundInfo(null, 1)

    override suspend fun doWork(): Result = coroutineScope {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Background FGS start can be refused (Android 12+); the work still runs while allowed.
            Log.w(TAG, "Running generation without a foreground service: ${e.javaClass.simpleName}")
        }
        val notifier = launch {
            var last = 0L
            bus.live.collect { live ->
                val now = System.currentTimeMillis()
                if (now - last >= 1000) {
                    last = now
                    notifications.updateGeneration(live.values.firstOrNull(), dao.activeCount())
                }
            }
        }
        var processed = 0
        try {
            runner.recoverInterrupted()
            while (runner.runNext()) processed++
        } finally {
            notifier.cancel()
        }
        if (processed > 0 && !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            notifications.notifyResult("Generation finished", if (processed == 1) "Your image is ready in the gallery." else "$processed jobs finished — see the gallery.")
        }
        Result.success()
    }

    private companion object { const val TAG = "GenerationWorker" }
}

/** Handles the "Cancel all" notification action. */
@AndroidEntryPoint
class GenerationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var generations: GenerationRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_ALL) return
        val pending = goAsync()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { generations.cancelAll() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_ALL = "com.unfiltered.studio.action.CANCEL_ALL_GENERATIONS"
    }
}
