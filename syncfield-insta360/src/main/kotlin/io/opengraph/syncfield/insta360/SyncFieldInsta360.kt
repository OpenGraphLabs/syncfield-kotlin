package io.opengraph.syncfield.insta360

/**
 * Top-level facade for syncfield-insta360 module v0.5.0.
 *
 * External SDK consumers can import a single namespace to access the full
 * production surface — discovery, pairing, state machine, batch collect,
 * background lifecycle.
 *
 * Public API:
 *  - [Insta360BluetoothHub] — BLE scan + pair (legacy facade, v0.4.0 compatible)
 *  - [Insta360ConnectionCoordinator] — process-wide supervisor + radio gate
 *  - [Insta360Collector] — multi-camera batch download
 *  - [Insta360BackgroundSupervisor] — `ProcessLifecycleOwner` integration
 *  - [Insta360CoordinatorConfig] — runtime policy knobs
 *  - [Insta360IdentityStore] — phone authorization cache + lastKnown UUID
 *  - [logging.InstaLog] — `INSTA360_<DOMAIN>` Logcat marker emitter
 */
object SyncFieldInsta360 {
    const val VERSION = "0.5.0"
}
