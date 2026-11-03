package com.unfiltered.studio.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.GenerationDefaults
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.SaveLocation
import com.unfiltered.studio.domain.models.ThemeMode
import com.unfiltered.studio.domain.repositories.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val activeProvider = stringPreferencesKey("active_provider_id")
        val defWidth = intPreferencesKey("def_width")
        val defHeight = intPreferencesKey("def_height")
        val defSteps = intPreferencesKey("def_steps")
        val defCfg = floatPreferencesKey("def_cfg")
        val defBatch = intPreferencesKey("def_batch")
        val defNegative = stringPreferencesKey("def_negative")
        val defSampler = stringPreferencesKey("def_sampler")
        val defScheduler = stringPreferencesKey("def_scheduler")
        val defClipSkip = intPreferencesKey("def_clip_skip")
        val saveLocation = stringPreferencesKey("save_location")
        val customFolder = stringPreferencesKey("custom_folder_uri")
        val autoSave = booleanPreferencesKey("auto_save")
        val embedMetadata = booleanPreferencesKey("embed_metadata")
        val connectTimeout = intPreferencesKey("connect_timeout")
        val readTimeout = intPreferencesKey("read_timeout")
        val maxRetries = intPreferencesKey("max_retries")
        val wifiOnly = booleanPreferencesKey("download_wifi_only")
        val showNsfw = booleanPreferencesKey("show_nsfw_previews")
        val galleryColumns = intPreferencesKey("gallery_columns")
        val draft = stringPreferencesKey("draft_params")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    override val draftParams: Flow<GenerationParameters?> = dataStore.data.map { prefs ->
        prefs[Keys.draft]?.let { runCatching { AppJson.decodeFromString(GenerationParameters.serializer(), it) }.getOrNull() }
    }

    override suspend fun saveDraft(params: GenerationParameters) {
        dataStore.edit { it[Keys.draft] = AppJson.encodeToString(GenerationParameters.serializer(), params) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = GenerationDefaults()
        val s = AppSettings()
        return AppSettings(
            themeMode = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: s.themeMode,
            dynamicColor = this[Keys.dynamicColor] ?: s.dynamicColor,
            activeProviderId = this[Keys.activeProvider],
            defaults = GenerationDefaults(
                width = this[Keys.defWidth] ?: d.width,
                height = this[Keys.defHeight] ?: d.height,
                steps = this[Keys.defSteps] ?: d.steps,
                cfgScale = this[Keys.defCfg] ?: d.cfgScale,
                batchSize = this[Keys.defBatch] ?: d.batchSize,
                negativePrompt = this[Keys.defNegative] ?: d.negativePrompt,
                sampler = this[Keys.defSampler],
                scheduler = this[Keys.defScheduler],
                clipSkip = this[Keys.defClipSkip] ?: d.clipSkip,
            ),
            saveLocation = this[Keys.saveLocation]?.let { runCatching { SaveLocation.valueOf(it) }.getOrNull() } ?: s.saveLocation,
            customFolderUri = this[Keys.customFolder],
            autoSaveToDevice = this[Keys.autoSave] ?: s.autoSaveToDevice,
            embedMetadata = this[Keys.embedMetadata] ?: s.embedMetadata,
            connectTimeoutSec = this[Keys.connectTimeout] ?: s.connectTimeoutSec,
            readTimeoutSec = this[Keys.readTimeout] ?: s.readTimeoutSec,
            maxRetries = this[Keys.maxRetries] ?: s.maxRetries,
            downloadOnWifiOnly = this[Keys.wifiOnly] ?: s.downloadOnWifiOnly,
            showNsfwPreviews = this[Keys.showNsfw] ?: s.showNsfwPreviews,
            galleryColumns = this[Keys.galleryColumns] ?: s.galleryColumns,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.theme] = s.themeMode.name
        this[Keys.dynamicColor] = s.dynamicColor
        setOrRemove(Keys.activeProvider, s.activeProviderId)
        this[Keys.defWidth] = s.defaults.width
        this[Keys.defHeight] = s.defaults.height
        this[Keys.defSteps] = s.defaults.steps
        this[Keys.defCfg] = s.defaults.cfgScale
        this[Keys.defBatch] = s.defaults.batchSize
        this[Keys.defNegative] = s.defaults.negativePrompt
        setOrRemove(Keys.defSampler, s.defaults.sampler)
        setOrRemove(Keys.defScheduler, s.defaults.scheduler)
        this[Keys.defClipSkip] = s.defaults.clipSkip
        this[Keys.saveLocation] = s.saveLocation.name
        setOrRemove(Keys.customFolder, s.customFolderUri)
        this[Keys.autoSave] = s.autoSaveToDevice
        this[Keys.embedMetadata] = s.embedMetadata
        this[Keys.connectTimeout] = s.connectTimeoutSec
        this[Keys.readTimeout] = s.readTimeoutSec
        this[Keys.maxRetries] = s.maxRetries
        this[Keys.wifiOnly] = s.downloadOnWifiOnly
        this[Keys.showNsfw] = s.showNsfwPreviews
        this[Keys.galleryColumns] = s.galleryColumns
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }
}
