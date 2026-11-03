package com.unfiltered.studio.domain.models

enum class ThemeMode(val label: String) { DARK("Dark"), AMOLED("AMOLED black"), LIGHT("Light"), SYSTEM("Follow system") }

enum class SaveLocation(val label: String, val description: String) {
    APP_ONLY("App gallery only", "Images stay in the app until you export them"),
    PICTURES("Pictures folder", "Pictures/Unfiltered AI Studio (visible in your gallery apps)"),
    CUSTOM_FOLDER("Custom folder", "A folder you pick with the system file picker"),
}

data class GenerationDefaults(
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 25,
    val cfgScale: Float = 7f,
    val batchSize: Int = 1,
    val negativePrompt: String = "",
    val sampler: String? = null,
    val scheduler: String? = null,
    val clipSkip: Int = 1,
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val activeProviderId: String? = null,
    val defaults: GenerationDefaults = GenerationDefaults(),
    val saveLocation: SaveLocation = SaveLocation.APP_ONLY,
    val customFolderUri: String? = null,
    val autoSaveToDevice: Boolean = false,
    val embedMetadata: Boolean = true,
    val connectTimeoutSec: Int = 15,
    /** Read timeout for generation calls (A1111 txt2img blocks until done). */
    val readTimeoutSec: Int = 300,
    val maxRetries: Int = 3,
    val downloadOnWifiOnly: Boolean = false,
    val showNsfwPreviews: Boolean = false,
    val galleryColumns: Int = 0,
)
