package io.opengraph.syncfield.insta360

import java.time.LocalDateTime
import java.time.ZoneOffset

internal object Insta360PendingResolver {
    data class Window(
        val startWallMs: Long,
        val endWallMs: Long,
        val expectedDurationSec: Int? = null,
        val expectedSegments: Int? = null,
        val toleranceMs: Long = 30_000L,
    )

    fun matchSegments(uris: List<String>, window: Window): List<String> {
        val candidates = uris.mapNotNull { uri ->
            if (!isDownloadableVideo(uri)) return@mapNotNull null
            val timestampMs = parseFilenameTimestampMs(uri) ?: return@mapNotNull null
            uri to timestampMs
        }

        val lo = (window.startWallMs - window.toleranceMs).coerceAtLeast(0L)
        val hi = window.endWallMs + window.toleranceMs
        val inWindow = candidates
            .filter { (_, timestampMs) -> timestampMs in lo..hi }
            .sortedBy { (_, timestampMs) -> timestampMs }

        if (window.expectedSegments == 1) {
            return inWindow
                .minByOrNull { (_, timestampMs) -> kotlin.math.abs(timestampMs - window.startWallMs) }
                ?.let { listOf(it.first) }
                ?: emptyList()
        }

        return inWindow.map { it.first }
    }

    fun parseFilenameTimestampMs(uri: String): Long? {
        val token = timestampToken(uri) ?: return null
        val year = token.substring(0, 4).toIntOrNull() ?: return null
        val month = token.substring(4, 6).toIntOrNull() ?: return null
        val day = token.substring(6, 8).toIntOrNull() ?: return null
        val hour = token.substring(8, 10).toIntOrNull() ?: return null
        val minute = token.substring(10, 12).toIntOrNull() ?: return null
        val second = token.substring(12, 14).toIntOrNull() ?: return null
        return runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
    }

    private fun isDownloadableVideo(uri: String): Boolean {
        val lower = uri.lowercase()
        return (lower.endsWith(".mp4") || lower.endsWith(".insv")) && !lower.contains("lrv")
    }

    private fun timestampToken(uri: String): String? {
        val chars = uri.toCharArray()
        if (chars.size < 14) return null
        for (start in chars.indices) {
            val digits = StringBuilder(14)
            var cursor = start
            while (cursor < chars.size && digits.length < 14) {
                val ch = chars[cursor]
                when {
                    ch in '0'..'9' -> digits.append(ch)
                    ch == '_' || ch == '-' -> Unit
                    else -> break
                }
                cursor += 1
            }
            if (digits.length >= 14) return digits.substring(0, 14)
        }
        return null
    }
}
