package com.unfiltered.studio.data.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.documentfile.provider.DocumentFile
import com.unfiltered.studio.core.AppError
import com.unfiltered.studio.domain.models.GeneratedImageData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Owns every image file the app writes: generated originals, grid thumbnails, img2img inputs and
 * exports. Originals are stored byte-for-byte as returned by the backend (PNG text chunks with the
 * server's own metadata are preserved).
 */
@Singleton
class ImageStorage @Inject constructor(@ApplicationContext private val context: Context) {

    data class StoredImage(
        val file: File,
        val thumbnail: File,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private val imagesDir: File get() = File(context.filesDir, "generations").apply { mkdirs() }
    private val thumbsDir: File get() = File(context.filesDir, "thumbnails").apply { mkdirs() }
    private val inputsDir: File get() = File(context.filesDir, "init_images").apply { mkdirs() }

    suspend fun saveGenerated(imageId: String, data: GeneratedImageData): StoredImage = withContext(Dispatchers.IO) {
        ensureSpace(data.bytes.size.toLong() * 2)
        val mime = sniffMime(data.bytes) ?: data.mimeType
        val file = File(imagesDir, "$imageId.${extensionFor(mime)}")
        writeAtomically(file, data.bytes)
        val bounds = decodeBounds(data.bytes)
        if (bounds.first <= 0) {
            file.delete()
            throw AppError.Parse("the server", "Returned data is not a decodable image (${data.bytes.size} bytes, $mime)")
        }
        val thumb = File(thumbsDir, "$imageId.webp")
        writeThumbnail(data.bytes, thumb)
        StoredImage(file, thumb, bounds.first, bounds.second, mime, file.length())
    }

    /** Copies a picked image into app storage (bounded to 2048 px) for img2img uploads. */
    suspend fun importInitImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw AppError.Storage("Couldn't read the selected image.")
        val bitmap = decodeSampled(bytes, 2048) ?: throw AppError.Storage("The selected file isn't a supported image.")
        val scaled = scaleToMax(bitmap, 2048)
        val target = File(inputsDir, "init_${System.currentTimeMillis()}.png")
        FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        target.absolutePath
    }

    fun readBytes(path: String): ByteArray = File(path).readBytes()

    fun shareUri(path: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

    suspend fun delete(paths: Collection<String>) = withContext(Dispatchers.IO) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    /** Writes into Pictures/Unfiltered AI Studio via MediaStore (no storage permission needed on API 29+). */
    suspend fun exportToPictures(bytes: ByteArray, displayName: String, mime: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Unfiltered AI Studio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw AppError.Storage("Couldn't create the image in Pictures.")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("No output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw AppError.Storage("Saving to Pictures failed: ${e.message}", e)
        }
        uri
    }

    /** Writes into a folder picked with ACTION_OPEN_DOCUMENT_TREE (persisted permission). */
    suspend fun exportToTree(treeUri: Uri, bytes: ByteArray, displayName: String, mime: String): Uri = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw AppError.Storage("The selected folder is no longer available. Pick it again in Settings → Storage.")
        if (!tree.canWrite()) throw AppError.Storage("No permission to write to the selected folder. Pick it again in Settings → Storage.")
        val doc = tree.createFile(mime, displayName.substringBeforeLast('.'))
            ?: throw AppError.Storage("Couldn't create a file in the selected folder.")
        context.contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
            ?: throw AppError.Storage("Couldn't write to the selected folder.")
        doc.uri
    }

    fun appStorageBytes(): Long = listOf(imagesDir, thumbsDir, inputsDir).sumOf { dirSize(it) }

    fun cacheBytes(): Long = dirSize(context.cacheDir)

    suspend fun clearCaches() = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun ensureSpace(needed: Long) {
        val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (available < needed + 20L * 1024 * 1024) throw AppError.Storage("Not enough free storage to save the image.")
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun writeThumbnail(bytes: ByteArray, target: File) {
        val bitmap = decodeSampled(bytes, THUMB_SIZE * 2) ?: return
        val scaled = scaleToMax(bitmap, THUMB_SIZE)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream()
        scaled.compress(format, 82, out)
        writeAtomically(target, out.toByteArray())
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val (w, h) = decodeBounds(bytes)
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / largest
        return bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), filter = true)
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val THUMB_SIZE = 384

        fun sniffMime(bytes: ByteArray): String? = when {
            bytes.size >= 8 && PngText.isPng(bytes) -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            bytes.size >= 6 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
            else -> null
        }

        fun extensionFor(mime: String): String = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
    }
}
