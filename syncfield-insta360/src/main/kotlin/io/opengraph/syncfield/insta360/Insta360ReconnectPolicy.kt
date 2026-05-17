package io.opengraph.syncfield.insta360

/**
 * Backoff schedule + persistent classifier for `Insta360CameraSupervisor`'s
 * auto-reconnect path. Stateless / pure — easy to unit test.
 *
 * Schedule mirrors the plan's "Backoff" section: 0.5 → 1 → 2 → 4 → 8 →
 * 15 → 30 → 60 → 60 → 60 s. After exhausting the ramp the supervisor stays
 * on 60 s indefinitely while the app is foregrounded.
 *
 * Mirrors `Insta360ReconnectPolicy.swift`.
 */
data class Insta360ReconnectPolicy(
    val backoffScheduleSeconds: List<Double> = DEFAULT_BACKOFF,
    val maxAttempts: Int = Int.MAX_VALUE,
) {
    init {
        require(backoffScheduleSeconds.isNotEmpty()) { "backoff schedule must not be empty" }
    }

    /**
     * 1-based attempt. Beyond the schedule length, returns the last value (steady-state cadence).
     * Attempt < 1 clamps to the first entry.
     */
    fun backoffSeconds(attempt: Int): Double {
        if (attempt < 1) return backoffScheduleSeconds[0]
        val idx = attempt - 1
        return if (idx < backoffScheduleSeconds.size) {
            backoffScheduleSeconds[idx]
        } else {
            backoffScheduleSeconds.last()
        }
    }

    companion object {
        val DEFAULT_BACKOFF: List<Double> =
            listOf(0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0, 60.0, 60.0, 60.0)

        /**
         * Persistent failure classifier — mirrors Swift `shouldClassifyAsLost`.
         *
         * Heuristics:
         * 1. [consecutiveScanWindowsWithoutAdvert] ≥ config threshold,
         * 2. [lastErrorDescription] matches a hard BLE error pattern,
         * 3. [msSinceLastAdvertisement] exceeds config threshold.
         *
         * When `true`, supervisor transitions to LOST immediately instead of
         * continuing the backoff ramp.
         */
        fun shouldClassifyAsLost(
            consecutiveScanWindowsWithoutAdvert: Int,
            lastErrorDescription: String?,
            msSinceLastAdvertisement: Long?,
            config: Insta360CoordinatorConfig = Insta360CoordinatorConfig,
        ): Boolean {
            if (consecutiveScanWindowsWithoutAdvert >= config.persistentScanWindowCount) {
                return true
            }
            lastErrorDescription?.lowercase()?.let { err ->
                val persistentMarkers = listOf(
                    "powered off",
                    "out of range",
                    "user disconnect",
                    "peripheraldisconnected",
                    "the specified device has disconnected",
                    "the connection has timed out unexpectedly",
                    // Android-specific BluetoothGatt error strings
                    "133", // GATT_ERROR
                    "gatt_error",
                    "gatt error",
                    "remote device terminated",
                )
                if (persistentMarkers.any { err.contains(it) }) return true
            }
            if (msSinceLastAdvertisement != null) {
                val thresholdMs = (config.persistentLastSeenThresholdSeconds * 1_000).toLong()
                if (msSinceLastAdvertisement > thresholdMs) return true
            }
            return false
        }
    }
}
