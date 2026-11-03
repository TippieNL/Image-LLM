package com.unfiltered.studio.workers

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.unfiltered.studio.MainActivity
import com.unfiltered.studio.R
import com.unfiltered.studio.domain.models.LiveProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_GENERATION, "Generation progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while images are being generated"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_DOWNLOADS, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of model and LoRA downloads"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_RESULTS, "Finished generations", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Posted when a generation finishes while the app is in the background"
                },
            ),
        )
    }

    fun generationForegroundInfo(progress: LiveProgress?, active: Int): ForegroundInfo =
        ForegroundInfo(ID_GENERATION, generationNotification(progress, active), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun updateGeneration(progress: LiveProgress?, active: Int) = notify(ID_GENERATION, generationNotification(progress, active))

    private fun generationNotification(progress: LiveProgress?, active: Int): Notification {
        val text = buildList {
            if (progress?.step != null && progress.totalSteps != null) add("Step ${progress.step}/${progress.totalSteps}")
            progress?.queuePosition?.let { add("Server queue #$it") }
            if (active > 1) add("${active - 1} more queued")
        }.joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL_GENERATION)
            .setSmallIcon(R.drawable.ic_stat_generate)
            .setContentTitle(progress?.phase ?: "Preparing generation…")
            .setContentText(text.ifBlank { "Unfiltered AI Studio" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel all", cancelAllIntent())
        val fraction = progress?.fraction
        if (fraction != null) builder.setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    fun downloadForegroundInfo(downloadId: String, title: String, fraction: Float?): ForegroundInfo =
        ForegroundInfo(downloadNotificationId(downloadId), downloadNotification(title, fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun updateDownload(downloadId: String, title: String, fraction: Float?) =
        notify(downloadNotificationId(downloadId), downloadNotification(title, fraction))

    private fun downloadNotification(title: String, fraction: Float?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(fraction?.let { "${(it * 100).toInt()}%" } ?: "Downloading…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
        if (fraction != null) builder.setProgress(100, (fraction * 100).toInt(), false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    fun notifyResult(title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_generate)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notify(ID_RESULT, n)
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelAllIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, GenerationActionReceiver::class.java).setAction(GenerationActionReceiver.ACTION_CANCEL_ALL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun downloadNotificationId(id: String) = 2000 + (id.hashCode() and 0x0FFF)

    companion object {
        const val CHANNEL_GENERATION = "generation"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_RESULTS = "results"
        const val ID_GENERATION = 1001
        const val ID_RESULT = 1002
    }
}
