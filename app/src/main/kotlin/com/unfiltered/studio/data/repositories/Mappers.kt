package com.unfiltered.studio.data.repositories

import com.unfiltered.studio.core.AppJson
import com.unfiltered.studio.data.database.entities.GenerationEntity
import com.unfiltered.studio.data.database.entities.GenerationWithImages
import com.unfiltered.studio.data.database.entities.ImageEntity
import com.unfiltered.studio.data.database.entities.ImageRow
import com.unfiltered.studio.data.database.entities.LoraEntity
import com.unfiltered.studio.data.database.entities.LoraLinkEntity
import com.unfiltered.studio.domain.models.GalleryImage
import com.unfiltered.studio.domain.models.Generation
import com.unfiltered.studio.domain.models.GenerationParameters
import com.unfiltered.studio.domain.models.GenerationStatus
import com.unfiltered.studio.domain.models.Lora
import com.unfiltered.studio.domain.models.LoraSource
import com.unfiltered.studio.domain.models.ModelArchitecture
import com.unfiltered.studio.domain.models.ProviderType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val stringList = ListSerializer(String.serializer())

fun encodeStrings(values: List<String>): String = AppJson.encodeToString(stringList, values)
fun decodeStrings(json: String?): List<String> = json?.let { runCatching { AppJson.decodeFromString(stringList, it) }.getOrNull() }.orEmpty()

fun encodeParams(params: GenerationParameters): String = AppJson.encodeToString(GenerationParameters.serializer(), params)
fun decodeParams(json: String): GenerationParameters =
    runCatching { AppJson.decodeFromString(GenerationParameters.serializer(), json) }.getOrDefault(GenerationParameters())

fun GenerationEntity.toDomain(images: List<ImageEntity> = emptyList()): Generation = Generation(
    id = id,
    createdAt = createdAt,
    providerId = providerId,
    providerName = providerName,
    providerType = runCatching { ProviderType.valueOf(providerType) }.getOrDefault(ProviderType.CUSTOM_REST),
    status = runCatching { GenerationStatus.valueOf(status) }.getOrDefault(GenerationStatus.FAILED),
    params = decodeParams(paramsJson),
    error = error,
    errorDetails = errorDetails,
    startedAt = startedAt,
    finishedAt = finishedAt,
    remoteJobId = remoteJobId,
    warnings = decodeStrings(warningsJson),
    images = images.sortedBy { it.indexInBatch }.map { it.toDomain(prompt, model, providerName) },
)

fun GenerationWithImages.toDomain(): Generation = generation.toDomain(images)

fun ImageEntity.toDomain(prompt: String, modelName: String?, providerName: String?): GalleryImage = GalleryImage(
    id = id,
    generationId = generationId,
    filePath = filePath,
    thumbnailPath = thumbnailPath,
    width = width,
    height = height,
    seed = seed,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    isFavorite = isFavorite,
    exportedUri = exportedUri,
    createdAt = createdAt,
    indexInBatch = indexInBatch,
    prompt = prompt,
    modelName = modelName,
    providerName = providerName,
)

fun ImageRow.toDomain(): GalleryImage = image.toDomain(prompt, modelName, providerName)

fun LoraEntity.toDomain(links: List<LoraLinkEntity>): Lora = Lora(
    id = id,
    name = name,
    description = description,
    previewImageUrl = previewImageUrl,
    triggerWords = decodeStrings(triggerWordsJson),
    architecture = architecture?.let { runCatching { ModelArchitecture.valueOf(it) }.getOrNull() },
    baseModelLabel = baseModelLabel,
    downloadUrl = downloadUrl,
    source = runCatching { LoraSource.valueOf(source) }.getOrDefault(LoraSource.MANUAL),
    sourceId = sourceId,
    sourceVersionId = sourceVersionId,
    hfRepoId = hfRepoId,
    hfWeightFile = hfWeightFile,
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    sha256 = sha256,
    localFilePath = localFilePath,
    defaultStrength = defaultStrength,
    categoryId = categoryId,
    tags = decodeStrings(tagsJson),
    isFavorite = isFavorite,
    nsfw = nsfw,
    createdAt = createdAt,
    updatedAt = updatedAt,
    backendLinks = links.filter { it.loraId == id }.associate { it.providerId to it.backendName },
)

fun Lora.toEntity(): LoraEntity = LoraEntity(
    id = id,
    name = name.trim(),
    description = description,
    previewImageUrl = previewImageUrl,
    triggerWordsJson = encodeStrings(triggerWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()),
    architecture = architecture?.name,
    baseModelLabel = baseModelLabel,
    downloadUrl = downloadUrl,
    source = source.name,
    sourceId = sourceId,
    sourceVersionId = sourceVersionId,
    hfRepoId = hfRepoId,
    hfWeightFile = hfWeightFile,
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    sha256 = sha256,
    localFilePath = localFilePath,
    defaultStrength = defaultStrength,
    categoryId = categoryId,
    tagsJson = encodeStrings(tags),
    isFavorite = isFavorite,
    nsfw = nsfw,
    createdAt = createdAt,
    updatedAt = System.currentTimeMillis(),
)
