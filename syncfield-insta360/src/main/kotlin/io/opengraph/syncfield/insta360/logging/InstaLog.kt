package io.opengraph.syncfield.insta360.logging

import android.util.Log

/**
 * Categories for the Insta360 connection coordinator family of logs.
 *
 * Mirrors `InstaLogCategory` from `syncfield-swift/Sources/SyncFieldInsta360/Insta360Logger.swift`.
 * Tag format uses **underscore** instead of dot because Logcat does not allow `.` in tags
 * (`adb logcat -s 'INSTA360_*:V'` becomes greppable).
 */
enum class InstaLogCategory(val tag: String) {
    COORD("INSTA360_COORD"),
    SUP("INSTA360_SUP"),
    BLE("INSTA360_BLE"),
    WAKE("INSTA360_WAKE"),
    RADIO("INSTA360_RADIO"),
    WIFI("INSTA360_WIFI"),
    BG("INSTA360_BG"),
    SCAN("INSTA360_SCAN"),
    COLLECT("INSTA360_COLLECT"),
    STREAM("INSTA360_STREAM"),
    BRIDGE("INSTA360_BRIDGE"),
}

enum class InstaLogLevel(val rank: Int, val label: String) : Comparable<InstaLogLevel> {
    DEBUG(0, "debug"),
    INFO(1, "info "),
    STATE(2, "state"),
    WARN(3, "warn "),
    ERROR(4, "error"),
}

/**
 * Single entry-point for every Insta360-related log line. Goals:
 *
 * 1. **Greppable**: every line starts with `[INSTA360_<CATEGORY>(_<role>)]` so a single
 *    Logcat filter (`-s 'INSTA360_*:V'`) shows the scenario tape.
 * 2. **Structured**: event name + field map, written deterministically as `key=value`
 *    pairs (keys sorted) so failed scenarios can be diff'd line-by-line.
 * 3. **State-transition emphasis**: `STATE` level lines render with horizontal separators
 *    so they stand out during live scrolling.
 *
 * `minLevel` defaults to [InstaLogLevel.INFO]. Scenario mode (set via the
 * coordinator config) flips it to [InstaLogLevel.DEBUG] for the S1–S14 runbook.
 */
object InstaLog {
    @Volatile
    var minLevel: InstaLogLevel = InstaLogLevel.INFO

    /**
     * @param category routing tag, see [InstaLogCategory]
     * @param role optional role suffix (e.g. "left") — produces `INSTA360_SUP_left`
     * @param level filter floor; lines below [minLevel] are dropped
     * @param event short event name (`scan_hit`, `heartbeat_miss`, …)
     * @param fields key/value map rendered as `key=value` pairs, keys sorted
     */
    fun log(
        category: InstaLogCategory,
        role: String? = null,
        level: InstaLogLevel = InstaLogLevel.INFO,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (level.rank < minLevel.rank) return
        val tag = composeTag(category, role)
        val body = formatFields(fields)
        val message = "${level.label} $event" + if (body.isEmpty()) "" else " $body"
        emit(tag, level, message)
    }

    /**
     * State-transition convenience. Always emits at [InstaLogLevel.STATE] so it
     * stays visible even at non-debug log floors.
     */
    fun state(
        category: InstaLogCategory,
        role: String? = null,
        from: String,
        to: String,
        reason: String? = null,
    ) {
        if (InstaLogLevel.STATE.rank < minLevel.rank) return
        val tag = composeTag(category, role)
        val reasonSuffix = reason?.let { " reason=\"$it\"" } ?: ""
        val core = "STATE $from → $to$reasonSuffix"
        val separator = "──────"
        emit(tag, InstaLogLevel.STATE, "$separator $core $separator")
    }

    // --- Internals ----------------------------------------------------------

    private fun composeTag(category: InstaLogCategory, role: String?): String {
        if (role.isNullOrBlank()) return category.tag
        // Normalize role to underscore-safe form for Logcat
        val safeRole = role.replace(' ', '_').replace('.', '_')
        return "${category.tag}_$safeRole"
    }

    internal fun formatFields(fields: Map<String, Any?>): String {
        if (fields.isEmpty()) return ""
        return fields.keys.sorted().joinToString(" ") { key ->
            "$key=${stringify(fields[key])}"
        }
    }

    internal fun stringify(value: Any?): String {
        return when (value) {
            null -> "nil"
            is String -> {
                if (value.isEmpty() || value.any { it == ' ' || it == '=' || it == '[' }) {
                    "\"$value\""
                } else value
            }
            is Boolean -> if (value) "true" else "false"
            is List<*> -> "[" + value.joinToString(",") { stringify(it) } + "]"
            is Array<*> -> "[" + value.joinToString(",") { stringify(it) } + "]"
            else -> value.toString()
        }
    }

    private fun emit(tag: String, level: InstaLogLevel, message: String) {
        when (level) {
            InstaLogLevel.DEBUG -> Log.d(tag, message)
            InstaLogLevel.INFO -> Log.i(tag, message)
            InstaLogLevel.STATE -> Log.i(tag, message)
            InstaLogLevel.WARN -> Log.w(tag, message)
            InstaLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
