package io.opengraph.syncfield.insta360

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes Insta360 commands at the narrowest level the Android OneSDK allows.
 *
 * Per-device locks prevent overlapping commands for the same camera. SDK-critical
 * commands still pass through [sdkMutex] because `InstaCameraManager` is a process
 * singleton with one active command route.
 */
internal class Insta360CommandQueue(
    private val sdkMutex: Mutex = sharedSdkMutex,
    private val defaultTimeoutMs: Long = 30_000L,
    private val retryDelayMs: Long = 250L,
) {
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> runDeviceCommand(
        deviceId: String,
        timeoutMs: Long = defaultTimeoutMs,
        retries: Int = 0,
        sdkCritical: Boolean = true,
        block: suspend () -> T,
    ): T {
        val deviceLock = deviceLocks.getOrPut(deviceId) { Mutex() }
        return deviceLock.withLock {
            runWithRetry(timeoutMs, retries, sdkCritical, block)
        }
    }

    suspend fun <T> runGlobalCommand(
        timeoutMs: Long = defaultTimeoutMs,
        retries: Int = 0,
        block: suspend () -> T,
    ): T = runWithRetry(timeoutMs, retries, sdkCritical = true, block)

    private suspend fun <T> runWithRetry(
        timeoutMs: Long,
        retries: Int,
        sdkCritical: Boolean,
        block: suspend () -> T,
    ): T {
        var last: Throwable? = null
        repeat(retries + 1) { attempt ->
            try {
                return withTimeout(timeoutMs) {
                    if (sdkCritical) sdkMutex.withLock { block() } else block()
                }
            } catch (t: Throwable) {
                last = t
                if (attempt < retries && retryDelayMs > 0) delay(retryDelayMs)
            }
        }
        throw last ?: Insta360Error.CommandFailed("Insta360 command failed")
    }

    companion object {
        private val sharedSdkMutex = Mutex()
        val shared = Insta360CommandQueue(sharedSdkMutex)
    }
}
