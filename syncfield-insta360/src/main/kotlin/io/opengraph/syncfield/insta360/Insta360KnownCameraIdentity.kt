package io.opengraph.syncfield.insta360

/**
 * Stable identity for a camera the host app has paired before.
 *
 * Bluetooth UUIDs are useful when the OS still remembers the peripheral, but
 * can rotate across app/device state changes. GO-family BLE names include the
 * stable serial suffix used by Insta360's wake-by-camera API, so production
 * reconnects should carry both values whenever possible.
 *
 * Mirrors `Insta360KnownCameraIdentity.swift`.
 */
data class Insta360KnownCameraIdentity(
    val uuid: String?,
    val preferredBLEName: String?,
    val serialLast6: String?,
) {

    val isUsable: Boolean
        get() = uuid != null || preferredBLEName != null || serialLast6 != null

    /**
     * Key used by the in-process pairing registry. Prefer the Bluetooth UUID
     * when present so existing manual-pair behavior stays unchanged; fall
     * back to serial/name for saved mappings that only have a BLE name.
     */
    val bindingKey: String?
        get() = when {
            uuid != null -> uuid
            serialLast6 != null -> "serial:$serialLast6"
            preferredBLEName != null -> "name:$preferredBLEName"
            else -> null
        }

    companion object {
        /**
         * Factory mirroring the Swift `init(uuid:bleName:)` — trims/normalizes
         * inputs and derives [serialLast6] from the BLE name.
         */
        fun create(uuid: String?, bleName: String?): Insta360KnownCameraIdentity {
            val trimmedUuid = uuid?.trim().takeUnless { it.isNullOrEmpty() }
            val normalizedName = normalizeBLEName(bleName)
            val serial = normalizedName?.let { extractSerialLast6(it) }
            return Insta360KnownCameraIdentity(
                uuid = trimmedUuid,
                preferredBLEName = normalizedName,
                serialLast6 = serial,
            )
        }

        /** Collapse internal whitespace and trim. */
        internal fun normalizeBLEName(name: String?): String? {
            if (name == null) return null
            val normalized = name
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return normalized.ifEmpty { null }
        }

        /**
         * Extract the 6-character serial suffix from a GO-family BLE name.
         *
         * GO 3S advertises with names like `"GO ABC123"` — the last
         * space-separated token of length 6 is the stable serial. Anything
         * not matching that shape returns null (no false positives so the
         * IdentityStore key space stays clean).
         *
         * Mirrors `Insta360BLEController.extractSerialLast6(fromBLEName:)`.
         */
        fun extractSerialLast6(bleName: String): String? {
            val trimmed = bleName.trim()
            if (trimmed.isEmpty()) return null
            val token = trimmed.split(' ').lastOrNull() ?: return null
            return token.takeIf { it.length == 6 }
        }

        /**
         * Encode a serialLast6 as a hex-encoded ASCII wake-id (Insta360 wake API).
         * Mirrors `Insta360BLEController.encodeWakeId(serialLast6:)`.
         */
        fun encodeWakeId(serialLast6: String): String =
            serialLast6.take(6).map { String.format("%02X", it.code) }.joinToString("")
    }
}
