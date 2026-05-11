package io.opengraph.syncfield

/**
 * Single source of truth for the SDK version. Embedded into every
 * `sync_point.json` and `manifest.json` written by the orchestrator,
 * and re-exported by optional modules so they stay in lock-step with
 * the core release.
 */
object SyncFieldVersion {
    const val current: String = "0.4.0"
}
