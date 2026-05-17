package io.opengraph.syncfield.insta360

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Observes [ProcessLifecycleOwner] and fans `BackgroundEntered` /
 * `ForegroundEntered` events to every attached supervisor on the
 * [Insta360ConnectionCoordinator].
 *
 * Mirrors `Insta360BackgroundSupervisor.swift`. Use:
 *
 * ```
 * val bgSupervisor = Insta360BackgroundSupervisor(
 *     coordinator = Insta360ConnectionCoordinator,
 *     recordingActiveProvider = { session.state.isRecording },
 * )
 * bgSupervisor.start()  // call once, e.g. from MainApplication.onCreate
 * // ...
 * bgSupervisor.stop()   // optional, on app teardown
 * ```
 *
 * Threading: Lifecycle callbacks fire on the main thread; fanning out events
 * is launched onto [supervisorScope] (Default dispatcher) so coordinator
 * mutex contention doesn't block UI.
 */
class Insta360BackgroundSupervisor(
    private val coordinator: Insta360ConnectionCoordinator = Insta360ConnectionCoordinator,
    private val recordingActiveProvider: () -> Boolean = { false },
    private val config: Insta360CoordinatorConfig = Insta360CoordinatorConfig,
) {

    private val supervisorScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleForeground()
        }

        override fun onStop(owner: LifecycleOwner) {
            handleBackground()
        }
    }

    /** Register with [ProcessLifecycleOwner]. Safe to call multiple times. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        InstaLog.log(InstaLogCategory.BG, event = "background_supervisor_started")
    }

    /** Unregister. After [stop], events are no longer fanned to the coordinator. */
    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        InstaLog.log(InstaLogCategory.BG, event = "background_supervisor_stopped")
    }

    /** Test-only hook (or call from custom non-Process lifecycle sources). */
    fun handleBackground() {
        if (!config.backgroundBLEEnabled) return
        val recording = runCatching { recordingActiveProvider() }.getOrDefault(false)
        InstaLog.log(
            InstaLogCategory.BG, event = "did_enter_background",
            fields = mapOf("recording_active" to recording),
        )
        supervisorScope.launch {
            val keys = coordinator.allHealth().keys.toList()
            for (key in keys) {
                coordinator.feed(key, Insta360SupervisorEvent.BackgroundEntered(recording))
            }
        }
    }

    /** Test-only hook. */
    fun handleForeground() {
        InstaLog.log(InstaLogCategory.BG, event = "will_enter_foreground")
        supervisorScope.launch {
            val keys = coordinator.allHealth().keys.toList()
            for (key in keys) {
                coordinator.feed(key, Insta360SupervisorEvent.ForegroundEntered)
            }
        }
    }
}
