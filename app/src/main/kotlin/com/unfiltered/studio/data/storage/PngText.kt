package com.unfiltered.studio.data.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Reads and writes PNG text chunks (tEXt / zTXt / iTXt). Used to keep generation parameters inside
 * exported PNGs in the same "parameters" key AUTOMATIC1111 uses.
 */
object PngText {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean = bytes.size > 8 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    fun readText(bytes: ByteArray): Map<String, String> {
        if (!isPng(bytes)) return emptyMap()
        val result = linkedMapOf<String, String>()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            if (length < 0 || offset + 12 + length > bytes.size) break
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            val dataStart = offset + 8
            val data = bytes.copyOfRange(dataStart, dataStart + length)
            when (type) {
                "tEXt" -> parseTExt(data)?.let { result[it.first] = it.second }
                "zTXt" -> parseZTxt(data)?.let { result[it.first] = it.second }
                "iTXt" -> parseITxt(data)?.let { result[it.first] = it.second }
                "IEND" -> break
            }
            offset += 12 + length
        }
        return result
    }

    /** Inserts an uncompressed UTF-8 iTXt chunk right after IHDR. */
    fun withText(bytes: ByteArray, keyword: String, text: String): ByteArray {
        require(isPng(bytes)) { "Not a PNG" }
        val ihdrLength = ByteBuffer.wrap(bytes, 8, 4).int
        val insertAt = 8 + 12 + ihdrLength
        val key = keyword.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArrayOutputStream().apply {
            write(key); write(0)
            write(0) // compression flag
            write(0) // compression method
            write(0) // empty language tag
            write(0) // empty translated keyword
            write(text.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val chunk = chunk("iTXt", data)
        return ByteArrayOutputStream(bytes.size + chunk.size).apply {
            write(bytes, 0, insertAt)
            write(chunk)
            write(bytes, insertAt, bytes.size - insertAt)
        }.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        return ByteBuffer.allocate(12 + data.size)
            .putInt(data.size).put(typeBytes).put(data).putInt(crc.toInt())
            .array()
    }

    private fun parseTExt(data: ByteArray): Pair<String, String>? {
        val sep = indexOfZero(data, 0)
        if (sep <= 0) return null
        return String(data, 0, sep, Charsets.ISO_8859_1) to String(data, sep + 1, data.size - sep - 1, Charsets.ISO_8859_1)
    }

    private fun parseZTxt(data: ByteArray): Pair<String, String>? {
        val sep = indexOfZero(data, 0)
        if (sep <= 0 || sep + 2 > data.size) return null
        val key = String(data, 0, sep, Charsets.ISO_8859_1)
        val inflated = inflate(data.copyOfRange(sep + 2, data.size)) ?: return null
        return key to String(inflated, Charsets.ISO_8859_1)
    }

    private fun parseITxt(data: ByteArray): Pair<String, String>? {
        val sep = indexOfZero(data, 0)
        if (sep <= 0 || sep + 3 > data.size) return null
        val key = String(data, 0, sep, Charsets.ISO_8859_1)
        val compressed = data[sep + 1].toInt() == 1
        var pos = sep + 3
        val langEnd = indexOfZero(data, pos); if (langEnd < 0) return null
        pos = langEnd + 1
        val transEnd = indexOfZero(data, pos); if (transEnd < 0) return null
        pos = transEnd + 1
        val raw = data.copyOfRange(pos, data.size)
        val textBytes = if (compressed) inflate(raw) ?: return null else raw
        return key to String(textBytes, Charsets.UTF_8)
    }

    private fun indexOfZero(data: ByteArray, from: Int): Int {
        for (i in from until data.size) if (data[i].toInt() == 0) return i
        return -1
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()
}
