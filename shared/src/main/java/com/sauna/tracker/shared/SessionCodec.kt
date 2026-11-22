package com.sauna.tracker.shared

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(session: Session): ByteArray =
        json.encodeToString(Session.serializer(), session).toByteArray()

    fun decode(bytes: ByteArray): Session =
        json.decodeFromString(Session.serializer(), bytes.decodeToString())

    /** Compact form for the watch -> phone transfer (heart-rate JSON compresses ~5x). */
    fun encodeCompressed(session: Session): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(encode(session)) }
        return out.toByteArray()
    }

    fun decodeCompressed(bytes: ByteArray): Session =
        decode(GZIPInputStream(bytes.inputStream()).use { it.readBytes() })

    fun encodeSettings(s: Settings): ByteArray = "${s.timeLimitMin},${s.hrLimit},${s.updatedAt}".toByteArray()

    fun decodeSettings(bytes: ByteArray): Settings? {
        val parts = bytes.decodeToString().split(',')
        if (parts.size != 3) return null
        return Settings(
            timeLimitMin = parts[0].toIntOrNull() ?: return null,
            hrLimit = parts[1].toIntOrNull() ?: return null,
            updatedAt = parts[2].toLongOrNull() ?: return null,
        )
    }

    fun encodeIds(ids: List<String>): ByteArray = ids.joinToString(",").toByteArray()
    fun decodeIds(bytes: ByteArray): List<String> = bytes.decodeToString().split(',').filter { it.isNotBlank() }
}
