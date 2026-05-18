package io.opengraph.syncfield.insta360

internal object Insta360VideoURIFallback {
    fun bestCandidate(uris: List<String>): String? =
        rankedCandidates(uris).firstOrNull()

    fun rankedCandidates(uris: List<String>): List<String> =
        uris
            .filter(::isLikelyDownloadableVideoURI)
            .sortedWith(compareByDescending<String> { sortKey(it) })

    private fun isLikelyDownloadableVideoURI(uri: String): Boolean {
        val lower = uri.lowercase()
        return (lower.endsWith(".mp4") || lower.endsWith(".insv")) && !lower.contains("lrv")
    }

    private fun sortKey(uri: String): String {
        val lower = uri.lowercase()
        val extRank = if (lower.endsWith(".mp4")) "2" else "1"
        val timestamp = timestampToken(lower) ?: "00000000000000"
        return "$timestamp|$extRank|$lower"
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
