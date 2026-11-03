package com.unfiltered.studio.data.generation

import android.net.Uri
import com.unfiltered.studio.BuildConfig
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.data.repositories.decodeParams
import com.unfiltered.studio.data.storage.ImageStorage
import com.unfiltered.studio.data.storage.PngText
import com.unfiltered.studio.domain.models.SaveLocation
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.domain.usecases.InfotextBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Copies a generated image to the user's chosen device location, embedding generation metadata. */
@Singleton
class ImageExporter @Inject constructor(
    private val dao: GenerationDao,
    private val storage: ImageStorage,
    private val settings: SettingsRepository,
) {
    suspend fun export(imageId: String): Uri = withContext(Dispatchers.IO) {
        val image = dao.imagesByIds(listOf(imageId)).firstOrNull() ?: throw AppError.Storage("Image not found.")
        val generation = dao.get(image.generationId) ?: throw AppError.Storage("Generation record not found.")
        val file = File(image.filePath)
        if (!file.exists()) throw AppError.Storage("The image file is missing from app storage.")
        val appSettings = settings.current()
        var bytes = file.readBytes()
        if (appSettings.embedMetadata && PngText.isPng(bytes) && !PngText.readText(bytes).containsKey("parameters")) {
            val params = decodeParams(generation.paramsJson)
            val infotext = InfotextBuilder.build(params, image.seed, generation.model, generation.providerName, BuildConfig.VERSION_NAME)
            bytes = PngText.withText(bytes, "parameters", infotext)
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(image.createdAt))
        val name = "unfiltered_${stamp}_${image.seed ?: image.indexInBatch}.${ImageStorage.extensionFor(image.mimeType)}"
        val uri = when (appSettings.saveLocation) {
            SaveLocation.CUSTOM_FOLDER -> {
                val tree = appSettings.customFolderUri?.let(Uri::parse)
                    ?: throw AppError.Storage("No custom folder selected. Pick one in Settings → Storage.")
                storage.exportToTree(tree, bytes, name, image.mimeType)
            }
            SaveLocation.APP_ONLY, SaveLocation.PICTURES -> storage.exportToPictures(bytes, name, image.mimeType)
        }
        dao.setExported(imageId, uri.toString())
        uri
    }
}
