# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.6.0] — 2026-05-19

Stabilizes egocentric Android capture frame rate when the host installs a heavy frame processor. Mirrors `syncfield-swift` 0.10.0 for cross-platform parity. The frame-processor closure signature changes from `(ImageProxy, Int) -> Unit` to `(FrameSnapshot) -> Unit` — host apps must update their callsite (one-line change for `og-skill`).

### Fixed
- **`AndroidCameraStream` frame processor no longer blocks the camera analyzer thread.** Before 0.6.0 the closure installed via `setFrameProcessor(throttleHz, body)` ran inline on the single-threaded `cameraExecutor` that CameraX's `ImageAnalysis` uses for its analyzer callback. Because the analyzer is built with `STRATEGY_KEEP_ONLY_LATEST` (the right default for real-time capture), any processor call that exceeded the inter-frame budget (~33 ms at 30 fps) caused CameraX to silently drop the next incoming sample. Hosts with on-device inference workloads — og-skill's MediaPipe hand-landmarker pipeline being the production case — were exposed to the same FPS-collapse failure mode that hit the iPhone pipeline through 2026-05-18 (`cam_ego.timestamps.jsonl` showed bimodal "33 ms vs 67 ms" inter-frame gap signatures and within-session drop rates climbing 20 % → 75 % with thermal throttling on iOS; the Android pipeline shape is structurally identical).
- The analyzer thread now does one small piece of work per frame: `ImageProxy.toBitmap()` (a memcpy under the `OUTPUT_IMAGE_FORMAT_RGBA_8888` path the SDK already pins, well under the inter-frame budget). The resulting `FrameSnapshot` is dispatched through a new `FrameProcessorGate` to a dedicated single-thread executor (`syncfield-camera-processor`). When a prior dispatch is still running, new snapshots are dropped on the producer side rather than letting CameraX silently drop frames downstream. The CameraX buffer-pool slot is released as the analyzer callback returns, so capture continues at the native 30 fps regardless of detector latency.
- `stopRecording` and `disconnect` now `drain()`/`shutdown()` the gate before returning, so hosts can rely on "stopRecording resolved ⇒ no more processor callbacks" for their own teardown (og-skill's bridge releases `AndroidHandDetectionEngine` immediately after stop).

### Changed (breaking)
- **`AndroidCameraStream.setFrameProcessor` signature:** the closure now receives a single `FrameSnapshot` parameter instead of `(ImageProxy, Int)`. The snapshot bundles the captured `Bitmap`, frame index, capture timestamp, and rotation hint — all the fields the previous host code read off the `ImageProxy`. The SDK owns the bitmap; hosts must not recycle it or hold references past the closure return. See KDoc on `FrameSnapshot`.
- Rationale for the breaking change: an `ImageProxy` is a handle into CameraX's buffer pool; deferring its `close()` until the processor finishes would defeat the off-thread fix (the next analyzer callback can't fire until the proxy is closed). Snapshotting to a `Bitmap` on the analyzer thread, then closing the proxy as usual, is the canonical CameraX ML-pipeline pattern.
- `syncFieldReleaseVersion` bumped to `0.6.0`.

### Compatibility
- Host migration is a one-line change: `setFrameProcessor(throttleHz = X) { proxy, _ -> ... proxy.something ... }` → `setFrameProcessor(throttleHz = X) { snapshot -> ... snapshot.bitmap / snapshot.frameIndex / snapshot.timestampNs / snapshot.rotationDegrees ... }`.
- Drop-on-busy applies even with `throttleHz = 0`. A closure that occasionally exceeds the inter-frame budget will skip samples instead of holding up the analyzer — this matches what CameraX was already doing silently, and is the contract that makes stateful detectors (MediaPipe `.video` mode, ML Kit's persistent trackers) safe.

### Tests
- New `FrameProcessorGateTest` (`syncfield-streams/src/test`): pure-JVM coverage of dispatch / drop-on-busy / drain / shutdown / repeated-enqueue / under-load serialization. 8 cases, runs in `./gradlew :syncfield-streams:testDebugUnitTest` without an emulator.
- Existing `syncfield-streams` tests (`VideoSettingsTest`, `CameraFovScoringTest`, `CameraSelectionResultTest`, `CameraUseCaseRotationPolicyTest`) continue to pass — 20 → 28 cases total in the module.

## [0.5.0] — 2026-05-17

### Added

`syncfield-insta360` reaches feature parity with `syncfield-swift` v0.9.x production stability
work. All additions are additive over v0.4.0; no public API removed.

- **BLE heartbeat** in `Insta360BLEController` — periodic 2 s `fetchCameraBatteryState()`
  round-trip keeps the camera awake during idle. `setHeartbeatIntervalMs(ms)` mutator lets
  `Insta360RadioGate` pause the AP-bound holder and demote siblings to slow mode (8 s default).
- **`lastKnownDeviceUUID/Name` cache** on `Insta360BLEController` — survives BLE drops so
  pending sidecars and reconnect logic can identify the camera after a transient disconnect.
- **`reconnectIfNeeded()`, `assertActionCamHost()`** on `Insta360BLEController`.
- **`unsolicitedDisconnectHandler`** on `Insta360BLEController` — supervisors wire this to feed
  `UnsolicitedDisconnect` events to the state machine.
- **`requestPhoneAuthorization` / `cancelPendingPhoneAuthorization`** on `Insta360BLEController`
  (Android variant: bridges OneSDK's implicit `connectBle` auth flow for API parity with iOS).
- **`Insta360IdentityStore`** — file-backed JSON cache (`identities.json` under `filesDir/insta360/`)
  storing `serialLast6`, `lastKnownUUID`, `lastKnownBLEName`, `phoneAuthorizedAtMs`, etc.
  Wire-format identical to iOS via `kotlinx.serialization` `@SerialName` snake_case.
- **`Insta360ConnectionState`** — 10-state enum with `acceptsCommands` / `isTerminal` predicates.
- **`Insta360ReconnectPolicy`** — `[0.5, 1, 2, 4, 8, 15, 30, 60, 60, 60]` backoff schedule +
  `shouldClassifyAsLost(...)` heuristic that fires on persistent BLE error strings (including
  Android-specific GATT_ERROR 133 markers).
- **`Insta360CoordinatorConfig`** — process-wide policy knobs (`heartbeatIntervalMs`,
  `wakeStallThresholdSeconds`, `persistentScanWindowCount`, etc.) + `enableScenarioMode()`.
- **`Insta360CameraSupervisor`** — per-camera state machine ported verbatim from
  `Insta360CameraSupervisor.swift`. Preserves all load-bearing invariants: 500 ms
  unsolicited-disconnect debounce, 12 s wake-stall threshold gated to `searching`/`connecting`,
  3 empty-scan-window persistent classifier, `ForceReconnectRequested` full reset.
  Emits `StateFlow<Insta360ConnectionHealth>` + `SharedFlow<Insta360TransitionEvent>` +
  `SharedFlow<Insta360WakeStallEvent>`. 26 unit tests.
- **`Insta360RadioGate`** — process-wide WiFi serialization gate. `withWiFi(bindingKey) { ... }`
  pauses holder heartbeat, demotes siblings to slow mode, runs body, restores on completion.
  Cleanup guaranteed via try/finally.
- **`Insta360ConnectionCoordinator`** (singleton `object`) — owns all supervisors + radio gate.
  `attach(bindingKey, role, reconnectDriver)`, `detach`, `feed(event)`, `forceReconnect`,
  `health(bindingKey)`, `allHealth()`, `withWiFi`. Fans per-supervisor transitions/wake-stalls
  into process-wide `stateEvents` + `wakeStallEvents` SharedFlows via UNDISPATCHED collectors
  (zero-race subscription guarantee).
- **`Insta360BackgroundSupervisor`** — `ProcessLifecycleOwner` integration. Fans ON_STOP /
  ON_START to every attached supervisor as `BackgroundEntered(recordingActive)` /
  `ForegroundEntered`.
- **`Insta360Collector`** — multi-camera batch downloader. Three load-bearing invariants:
  (1) deterministic UUID-alphabetical ordering, (2) prefetch pair every target before per-camera
  sequential download so all camera heartbeats run during the wait, (3) `withWiFi(uuid) { ... }`
  wraps each camera's `downloadBatch` so siblings drop to 8 s slow mode during the lease.
  `collectEpisode / collectEpisodes / collectAll` + cooperative `cancel()`.
- **`HealthEvent` extensions** in `syncfield-core` (additive — non-exhaustive `when` warns,
  doesn't error): `Insta360StateTransition`, `Insta360WakeStallRequiresUser`,
  `Insta360CameraWarning`, `Insta360HeartbeatStatus`.
- **`logging/InstaLog`** — `INSTA360_<DOMAIN>` Logcat marker emitter mirroring iOS' `InstaLog`.
  Greppable `key=value` field format with deterministic sort order so scenario captures
  diff cleanly line-by-line.

### Test coverage

- **135 unit tests, 0 failures** — full coverage of foundation types, IdentityStore, supervisor
  state-transition table (~26 cases), RadioGate serialization, coordinator fan-out, background
  lifecycle, collector grouping + prefetch retries.
- `kotlinx.coroutines.test` (virtual time), `Turbine` (Flow assertions), `MockK`, Robolectric.

### OneSDK integration

- Removed reflection scaffolds. Direct imports of `InstaCameraManager` (com.arashivision.sdk:sdkcamera 1.10.1).
- `Insta360OneSDKBridge.available` kept as `Class.forName` capability flag (Proguard strip safety).

### Fixed

- Android GO 3S collect now completes media ingestion end-to-end: BLE opens camera Wi-Fi and file-access mode, Android joins the camera AP, socket probing still targets `192.168.42.1:6666`, and MP4 transfer uses the camera HTTP server on port 80.
- Multi-camera collect reports OneDriver download progress (`DOWNLOADING` then `SUCCESS`/`FAIL`) and restores file-access state after each transfer.

## [0.4.0] — 2026-05-11

### Added

- JitPack/Maven publication metadata for all Android SDK modules.
- `Insta360Support` capability probe so host apps can fail fast when the
  OneSDK is absent.
- Unit coverage for command queue serialization, Wi-Fi probe policy, and
  OneSDK availability probing.

### Changed

- Promoted the SDK version from `0.4.0-SNAPSHOT` to the stable `0.4.0`
  release.
- Replaced the single BLE command mutex with a per-device command queue
  plus a shared SDK-critical section for `InstaCameraManager`.
- Matched Android Insta360 Wi-Fi reachability probing to the iOS
  eight-step backoff policy and wait briefly for the default network to
  restore after release.

## [0.3.0] — 2026-04-27

Initial Kotlin port of [syncfield-swift](https://github.com/OpenGraphLabs/syncfield-swift)
v0.3. Wire-format compatible with the iOS SDK so episodes recorded on
either platform feed the same Python pipeline.

### Added

- `syncfield-core` — `SessionOrchestrator`, `SessionClock`, `HealthBus`,
  `SyncPoint`, `StreamCapabilities`, `StreamWriter`, `SensorWriter`,
  `SessionLogWriter`, `ManifestWriter`, audio chirp synthesis, and
  `AudioTrackChirpPlayer`
- `syncfield-streams` — `AndroidCameraStream` (CameraX) and
  `AndroidMotionStream` (`SensorManager`)
- `syncfield-tactile` — `TactileBLEClient`, `TactilePacketParser`,
  `TactileStream` for Oglo FSR gloves
- `syncfield-insta360` — BLE controller, WiFi downloader, camera stream,
  pending-sidecar persistence, and role registry. SDK-call sites are
  reflective stubs gated on `Insta360OneSDKBridge.available` so the
  module compiles without the OneSDK AAR present
- `syncfield-ui` — `SyncFieldPreviewView` wrapping CameraX `PreviewView`
- 37 unit tests covering chirp synthesis, session orchestration,
  writers (sorted-keys JSONL parity with Swift), tactile packet
  parsing, video settings, and Insta360 sidecar round-trip
