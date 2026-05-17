package io.opengraph.syncfield.insta360

import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialization gate for the phone's single WiFi radio.
 *
 * iOS and Android both allow only one WiFi network at a time, so when one
 * camera holds the phone's AP, sibling cameras can't reach their AP. The
 * gate also throttles sibling BLE heartbeats to a slow cadence
 * ([Insta360CoordinatorConfig.radioGateSlowHeartbeatIntervalMs]) for the
 * duration of the lease — this avoids radio collisions during the
 * AP-bound camera's mp4 download (the gate "slow-mode" pattern from
 * `Insta360RadioGate.swift`).
 *
 * Usage:
 * ```
 * coordinator.radioGate.withWiFi(bindingKey = "left") {
 *     // download from camera AP — only this binding key has the radio
 * }
 * ```
 *
 * Other registered binding keys keep their BLE link alive but heartbeat
 * at the slow cadence. The lease holder pauses its heartbeat entirely
 * (the AP-bound camera can't service BLE commands anyway).
 *
 * @property setHeartbeatIntervalMs called as `(bindingKey, intervalMs?)`. A
 *           `null` interval pauses heartbeat. Wired to
 *           `BLEController.setHeartbeatIntervalMs(...)` by the coordinator.
 */
class Insta360RadioGate(
    private val config: Insta360CoordinatorConfig = Insta360CoordinatorConfig,
    private val setHeartbeatIntervalMs: suspend (bindingKey: String, ms: Long?) -> Unit,
) {

    private val gateMutex = Mutex()
    private val registryMutex = Mutex()
    private val registered = mutableSetOf<String>()
    private var currentHolder: String? = null

    /** Lease handle yielded inside [withWiFi]. Currently identity-only. */
    class Lease internal constructor(val bindingKey: String)

    /** Register a binding key so it gets throttled to slow mode during other cameras' leases. */
    suspend fun register(bindingKey: String) = registryMutex.withLock {
        registered.add(bindingKey)
    }

    /** Remove a binding key from the slow-mode throttle list. */
    suspend fun unregister(bindingKey: String) = registryMutex.withLock {
        registered.remove(bindingKey)
    }

    /** Returns the binding key currently holding the lease, or null. */
    suspend fun currentLeaseHolder(): String? = registryMutex.withLock { currentHolder }

    /**
     * Acquire the WiFi lease, run [body], then restore heartbeats.
     *
     * Sequence (mirrors Swift `withWiFi`):
     * 1. Acquire [gateMutex] (serialize against other lease attempts)
     * 2. Log `acquireWiFi_requested`
     * 3. Mark [bindingKey] as the holder
     * 4. Pause holder's heartbeat (`setHeartbeatIntervalMs(holder, null)`)
     * 5. Demote all other registered cameras to slow mode
     * 6. Log `acquireWiFi_granted`
     * 7. Run [body]
     * 8. Restore all heartbeats to default; clear holder
     * 9. Release [gateMutex]
     *
     * On exception during [body], cleanup still runs (try/finally).
     */
    suspend fun <T> withWiFi(bindingKey: String, body: suspend (lease: Lease) -> T): T {
        InstaLog.log(
            InstaLogCategory.RADIO, event = "acquireWiFi_requested",
            fields = mapOf("bindingKey" to bindingKey),
        )
        return gateMutex.withLock {
            val others = registryMutex.withLock {
                currentHolder = bindingKey
                (registered - bindingKey).toList()
            }
            InstaLog.log(
                InstaLogCategory.RADIO, event = "acquireWiFi_granted",
                fields = mapOf("bindingKey" to bindingKey, "wait_ms" to 0),
            )
            // Pause the holder's heartbeat (AP-bound can't service BLE anyway)
            runCatching { setHeartbeatIntervalMs(bindingKey, null) }
            // Demote others to slow mode
            if (others.isNotEmpty()) {
                InstaLog.log(
                    InstaLogCategory.RADIO, event = "slow_mode_engaged",
                    fields = mapOf(
                        "holder" to bindingKey,
                        "other_cameras" to others,
                        "slow_interval_ms" to config.radioGateSlowHeartbeatIntervalMs,
                    ),
                )
                for (other in others) {
                    runCatching {
                        setHeartbeatIntervalMs(other, config.radioGateSlowHeartbeatIntervalMs)
                    }
                }
            }
            try {
                body(Lease(bindingKey))
            } finally {
                // Restore all heartbeats to default
                runCatching { setHeartbeatIntervalMs(bindingKey, config.heartbeatIntervalMs) }
                for (other in others) {
                    runCatching {
                        setHeartbeatIntervalMs(other, config.heartbeatIntervalMs)
                    }
                }
                registryMutex.withLock { currentHolder = null }
                if (others.isNotEmpty()) {
                    InstaLog.log(InstaLogCategory.RADIO, event = "slow_mode_released")
                }
                InstaLog.log(
                    InstaLogCategory.RADIO, event = "releaseWiFi",
                    fields = mapOf("bindingKey" to bindingKey),
                )
            }
        }
    }
}
