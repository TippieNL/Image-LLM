package com.unfiltered.studio

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.unfiltered.studio.data.generation.GenerationScheduler
import com.unfiltered.studio.di.ApplicationScope
import com.unfiltered.studio.di.ImageClient
import com.unfiltered.studio.workers.AppNotifications
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class UnfilteredApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifications: AppNotifications
    @Inject @ImageClient lateinit var imageClient: dagger.Lazy<OkHttpClient>
    @Inject lateinit var generationScheduler: dagger.Lazy<GenerationScheduler>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        appScope.launch {
            try {
                generationScheduler.get().resumeOnStartup()
            } catch (e: Exception) {
                Log.w("UnfilteredApp", "Couldn't resume the generation queue: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Thumbnails and previews are cached in memory and on disk; network images reuse the app's OkHttp. */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient.get() })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
