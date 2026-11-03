package com.unfiltered.studio.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.unfiltered.studio.BuildConfig
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.data.database.AppDatabase
import com.unfiltered.studio.data.database.dao.CatalogDao
import com.unfiltered.studio.data.database.dao.DownloadDao
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.data.database.dao.LoraDao
import com.unfiltered.studio.data.database.dao.PromptDao
import com.unfiltered.studio.data.database.dao.ProviderDao
import com.unfiltered.studio.data.network.AccountAuthInterceptor
import com.unfiltered.studio.data.network.MetadataCacheInterceptor
import com.unfiltered.studio.data.network.NetworkMonitor
import com.unfiltered.studio.data.network.OfflineCacheInterceptor
import com.unfiltered.studio.data.network.SafeLoggingInterceptor
import com.unfiltered.studio.data.network.UserAgentInterceptor
import com.unfiltered.studio.data.network.civitai.CivitaiApi
import com.unfiltered.studio.data.network.civitai.CivitaiQueries
import com.unfiltered.studio.data.network.huggingface.HfHubApi
import com.unfiltered.studio.data.network.huggingface.HfQueries
import com.unfiltered.studio.data.providers.ProviderAdapterFactory
import com.unfiltered.studio.data.providers.ProviderRegistryImpl
import com.unfiltered.studio.data.providers.ProviderTypeKey
import com.unfiltered.studio.data.providers.a1111.A1111ProviderFactory
import com.unfiltered.studio.data.providers.comfyui.ComfyUiProviderFactory
import com.unfiltered.studio.data.providers.custom.CustomRestProviderFactory
import com.unfiltered.studio.data.providers.huggingface.HuggingFaceProviderFactory
import com.unfiltered.studio.data.providers.openai.OpenAiImagesProviderFactory
import com.unfiltered.studio.data.repositories.AccountRepositoryImpl
import com.unfiltered.studio.data.repositories.DownloadRepositoryImpl
import com.unfiltered.studio.data.repositories.GenerationHistoryRepositoryImpl
import com.unfiltered.studio.data.repositories.GenerationRepositoryImpl
import com.unfiltered.studio.data.repositories.LoraRepositoryImpl
import com.unfiltered.studio.data.repositories.ModelRepositoryImpl
import com.unfiltered.studio.data.repositories.PromptRepositoryImpl
import com.unfiltered.studio.data.repositories.ProviderRepositoryImpl
import com.unfiltered.studio.data.repositories.SettingsRepositoryImpl
import com.unfiltered.studio.data.storage.SecureCredentialStore
import com.unfiltered.studio.domain.models.ProviderType
import com.unfiltered.studio.domain.providers.ProviderRegistry
import com.unfiltered.studio.domain.repositories.AccountRepository
import com.unfiltered.studio.domain.repositories.DownloadRepository
import com.unfiltered.studio.domain.repositories.GenerationHistoryRepository
import com.unfiltered.studio.domain.repositories.GenerationRepository
import com.unfiltered.studio.domain.repositories.LoraRepository
import com.unfiltered.studio.domain.repositories.ModelRepository
import com.unfiltered.studio.domain.repositories.PromptRepository
import com.unfiltered.studio.domain.repositories.ProviderRepository
import com.unfiltered.studio.domain.repositories.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class HubClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DownloadClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ImageClient

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides fun providerDao(db: AppDatabase): ProviderDao = db.providerDao()
    @Provides fun generationDao(db: AppDatabase): GenerationDao = db.generationDao()
    @Provides fun loraDao(db: AppDatabase): LoraDao = db.loraDao()
    @Provides fun promptDao(db: AppDatabase): PromptDao = db.promptDao()
    @Provides fun downloadDao(db: AppDatabase): DownloadDao = db.downloadDao()
    @Provides fun catalogDao(db: AppDatabase): CatalogDao = db.catalogDao()

    @Provides @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("settings") },
    )

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private val userAgent = "UnfilteredAIStudio/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    /** Base client: shared connection pool, UA, safe debug logging. Providers derive their own from it. */
    @Provides @Singleton
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .apply { if (BuildConfig.DEBUG) addInterceptor(SafeLoggingInterceptor()) }
        .build()

    @Provides @Singleton @HubClient
    fun hubClient(
        base: OkHttpClient,
        credentials: SecureCredentialStore,
        monitor: NetworkMonitor,
        @ApplicationContext context: Context,
    ): OkHttpClient = base.newBuilder()
        .cache(Cache(File(context.cacheDir, "http_metadata"), 25L * 1024 * 1024))
        .addInterceptor(OfflineCacheInterceptor { monitor.isOnline.value })
        .addInterceptor(AccountAuthInterceptor(credentials))
        .addNetworkInterceptor(MetadataCacheInterceptor())
        .build()

    @Provides @Singleton @DownloadClient
    fun downloadClient(base: OkHttpClient, credentials: SecureCredentialStore): OkHttpClient = base.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor(AccountAuthInterceptor(credentials))
        .build()

    @Provides @Singleton @ImageClient
    fun imageClient(base: OkHttpClient, credentials: SecureCredentialStore): OkHttpClient = base.newBuilder()
        .addInterceptor(AccountAuthInterceptor(credentials))
        .build()

    @Provides @Singleton
    fun hfHubApi(@HubClient client: OkHttpClient): HfHubApi = Retrofit.Builder()
        .baseUrl(HfQueries.BASE)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HfHubApi::class.java)

    @Provides @Singleton
    fun civitaiApi(@HubClient client: OkHttpClient): CivitaiApi = Retrofit.Builder()
        .baseUrl(CivitaiQueries.BASE)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CivitaiApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun providers(impl: ProviderRepositoryImpl): ProviderRepository
    @Binds abstract fun accounts(impl: AccountRepositoryImpl): AccountRepository
    @Binds abstract fun generations(impl: GenerationRepositoryImpl): GenerationRepository
    @Binds abstract fun history(impl: GenerationHistoryRepositoryImpl): GenerationHistoryRepository
    @Binds abstract fun loras(impl: LoraRepositoryImpl): LoraRepository
    @Binds abstract fun models(impl: ModelRepositoryImpl): ModelRepository
    @Binds abstract fun downloads(impl: DownloadRepositoryImpl): DownloadRepository
    @Binds abstract fun prompts(impl: PromptRepositoryImpl): PromptRepository
    @Binds abstract fun registry(impl: ProviderRegistryImpl): ProviderRegistry

    // Provider adapters. Adding a backend = one adapter class + one line here.
    @Binds @IntoMap @ProviderTypeKey(ProviderType.A1111)
    abstract fun a1111(factory: A1111ProviderFactory): ProviderAdapterFactory

    @Binds @IntoMap @ProviderTypeKey(ProviderType.COMFYUI)
    abstract fun comfy(factory: ComfyUiProviderFactory): ProviderAdapterFactory

    @Binds @IntoMap @ProviderTypeKey(ProviderType.HUGGING_FACE)
    abstract fun huggingFace(factory: HuggingFaceProviderFactory): ProviderAdapterFactory

    @Binds @IntoMap @ProviderTypeKey(ProviderType.OPENAI_COMPATIBLE)
    abstract fun openAi(factory: OpenAiImagesProviderFactory): ProviderAdapterFactory

    @Binds @IntoMap @ProviderTypeKey(ProviderType.CUSTOM_REST)
    abstract fun custom(factory: CustomRestProviderFactory): ProviderAdapterFactory
}
