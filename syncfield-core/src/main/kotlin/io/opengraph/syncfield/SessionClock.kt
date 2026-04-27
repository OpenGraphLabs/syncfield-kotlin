package io.opengraph.syncfield

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Provides the monotonic clock used to stamp every frame in a session,
 * plus a wall-clock anchor captured once at session start.
 *
 * Android's `System.nanoTime()` is the equivalent of mach absolute time
 * on Apple platforms — it's monotonic, never goes backwards, and is the
 * same domain that `SensorEvent.timestamp` lives in.
 */
class SessionClock {

    /** Current monotonic clock in nanoseconds. */
    fun nowMonotonicNs(): Long = System.nanoTime()

    /**
     * Capture a [SyncPoint] anchoring monotonic to wall clock.
     *
     * @param hostId stable per-device identifier (e.g. ANDROID_ID)
     * @param sdkVersion defaults to [SyncFieldVersion.current]
     */
    fun anchor(
        hostId: String,
        sdkVersion: String = SyncFieldVersion.current,
    ): SyncPoint {
        val mono = nowMonotonicNs()
        val now = Instant.now()
        val wall = now.epochSecond * 1_000_000_000L + now.nano
        val iso = DateTimeFormatter.ISO_INSTANT.format(now)
        return SyncPoint(
            sdkVersion = sdkVersion,
            monotonicNs = mono,
            wallClockNs = wall,
            hostId = hostId,
            isoDatetime = iso,
        )
    }
}
