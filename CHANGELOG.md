# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.7.2] — 2026-05-19

Lowers `syncfield-insta360`'s `minSdk` floor from 29 to 28 so apps on Android 9 / API 28 can link the module without the `tools:overrideLibrary` hack. The Q+ `WifiNetworkSpecifier` path that ships in 0.7.x stays bit-for-bit identical when `Build.VERSION.SDK_INT >= Q`; on P the downloader internally falls back to the pre-deprecation `WifiManager.addNetwork` + `enableNetwork` + `bindProcessToNetwork` flow. The SDK_INT branching lives entirely inside `Insta360WiFiDownloader` — `download` / `downloadBatch` / `listFiles` keep the same public signature host apps already call.

### Added
- **API 28 fallback inside `Insta360WiFiDownloader`** — new `applyNetworkSuggestionLegacy` + `requestCameraNetworkLegacyOnce` private path mirroring the existing Q+ retry-with-deadline shape. Internally tracked via a private `CameraNetworkJoin` sealed class (`Modern` carries only the `NetworkCallback`; `Legacy` additionally carries the `addNetwork` netId + the previously-enabled config snapshot) so `releaseCameraNetwork` can tear down the correct platform state per branch.
- **`buildLegacyWifiConfiguration(ssid, passphrase, hiddenSsid)`** (internal, top-level) — produces a `WifiConfiguration` wired for the GO 3S firmware variants in the wild: WPA_PSK key management, both RSN + WPA protocols, CCMP + TKIP pairwise/group ciphers, quoted SSID/PSK.
- **`legacyConnectionMatchesTarget(rawConnectedSsid, target)`** (internal, top-level) — pure-string matcher used inside the legacy `NetworkCallback.onAvailable` to debounce false positives: strips literal double quotes, rejects `<unknown ssid>` placeholders, rejects blanks. Without this debounce the callback would bind us to whichever Wi-Fi transport Android happens to surface first (often the user's home network mid-transition).
- **`Insta360WiFiDownloaderLegacyHelpersTest`** — Robolectric (`@Config(sdk = [28])`) coverage of every helper: quote-stripping, key-management bits, hidden-SSID flag, protocol/cipher bits, `<unknown ssid>` rejection, case-sensitive match, special-char SSID match (`GO 3S 1234.OSC`). Sibling `@Config(sdk = [29])` sanity test guards against accidental dispatcher-branch flip.

### Changed
- **`syncfield-insta360/build.gradle.kts`**: `minSdk = 29` → `minSdk = 28`. Comment updated to document the dispatcher split. Other modules (`syncfield-core`, `-streams`, `-tactile`, `-ui`) are unchanged — they already sit at `minSdk = 26`.
- **`Insta360WiFiDownloader.download` / `.downloadBatch` / `.listFiles`** drop their `@RequiresApi(Build.VERSION_CODES.Q)` annotation (the SDK_INT dispatch happens internally). The Q+ helpers `applyNetworkSuggestion` and `requestCameraNetworkOnce` keep their `@RequiresApi(Q)` annotation as a defense-in-depth contract.

### Not changed
- The Q+ join + reachability + fetch pipeline (`applyNetworkSuggestion`, `requestCameraNetworkOnce`, `waitForReachability`, `fetchResource`, `logWifiScanSnapshot`, `logNetworkSnapshot`, `releaseCameraNetwork`'s common path). Behavior on Android 10+ is byte-for-byte identical to 0.7.1 — the only change to that branch is that its callback is wrapped in `CameraNetworkJoin.Modern` before being handed to `releaseCameraNetwork`.
- Every non-Wi-Fi module (`Insta360BLEController`, `Insta360GattHandshake`, `Insta360DirectGattConnector`, `Insta360OneSDKBridge`, `Insta360CameraSupervisor`, `Insta360RadioGate`, `Insta360KnownCameraIdentity`). No Q+ APIs were used outside the downloader.
- `AndroidManifest.xml` permissions — `ACCESS_FINE_LOCATION` / `CHANGE_WIFI_STATE` / `CHANGE_NETWORK_STATE` / `ACCESS_NETWORK_STATE` already cover both code paths. Host apps that already request the runtime fine-location prompt need no further work.

### Compatibility caveats for API 28
- **System-scoped, not app-scoped**: the legacy `enableNetwork(_, /*disableOthers=*/true)` flow briefly takes the device's Wi-Fi onto the camera AP, disconnecting every app for the duration of the download. The Q+ specifier path is app-scoped and does not have this issue. This is a platform constraint, not something we can polyfill.
- **Best-effort restore**: on teardown we re-enable each previously-saved `WifiConfiguration` and call `reconnect()`. The supplicant then picks the highest-priority enabled network; how fast it picks the user's home Wi-Fi is OEM-dependent. Apps targeting API 28 should not assume connectivity is restored within any specific deadline.
- **No on-device test coverage**: the legacy path has been validated by unit test and code review only — no Android 9 hardware is currently in the test lab. Production telemetry (`camera_ap_join_*_legacy` / `wifi_legacy_release_*` / `wifi_legacy_restore_*` events emitted via `InstaLog`) is the canary; surface any OEM-specific anomalies through the host app's logging pipeline.

### Fixed
- **Failed-join cleanup leaves saved Wi-Fi disabled** — extracted `restoreLegacyWifiState` helper now runs on all four legacy-path failure exits (`enableNetwork` returning `false`, `registerNetworkCallback` throwing, join await cancellation, join await timing out/throwing). Previously these exits only removed the camera `WifiConfiguration` and skipped the `previouslyEnabledNetIds` restore — a single failed join could leave the user's home Wi-Fi disabled because `enableNetwork(_, disableOthers=true)` already ran. The successful-teardown path keeps emitting `wifi_legacy_release_*` events; the new failure-cleanup paths emit `wifi_legacy_restore_*` with a `phase` field (`enable_network_failed` / `register_callback_failed` / `join_cancelled` / `join_failed`) so the two streams stay distinguishable in production logs. Modern (Q+) path is unaffected.

### Tests
- New `Insta360WiFiDownloaderLegacyHelpersTest` (above). `SyncFieldVersionTest` bumped to track the new `current`. Maintainers should run `./gradlew :syncfield-insta360:testDebugUnitTest :syncfield-core:testDebugUnitTest` before tagging.

## [0.7.1] — 2026-05-19

Adds passive audio-interruption observability to `AndroidCameraStream`, mirroring the audio-recovery health events `syncfield-swift` 0.10.0 ships on iOS. iOS shipped an active recovery path (re-attaching `AVCaptureAudioDataOutput` on `AVAudioSession.interruptionNotification.ended`); Android cannot mirror that because CameraX abstracts the audio source and active recovery would risk the single-file invariant downstream pipelines rely on. Instead the SDK surfaces the two health events JS already consumes, and the host app decides how to react.

### Added
- **`HealthEvent.AudioStalled(streamId, silentForSeconds)`** and **`HealthEvent.AudioRecovered(streamId)`** — additive sealed-interface variants. Payload matches the iOS `HealthEvent` cases of the same name so the React Native bridge can forward both platforms through one channel.
- **`AudioStateTracker`** (`syncfield-streams`) — pure-Kotlin state machine that translates CameraX `AudioStats.audioState` transitions (`ACTIVE` ↔ `SOURCE_SILENCED` / `SOURCE_ERROR` / `ENCODER_ERROR`) into the new health events. `DISABLED` is a baseline no-op so revoked-mic-permission sessions don't emit spurious events.
- **`AndroidCameraStream` Status branch** — the previously dropped `VideoRecordEvent.Status` case now feeds `audioState` into `AudioStateTracker` and publishes resulting events through the existing `HealthBus`. The tracker is reset at `startRecording`, `stopRecording`, and `disconnect`. Fire-and-forget `ioScope.launch` is safe because `HealthBus` is `DROP_OLDEST`.

### Not changed
- Recording lifecycle (`startRecording` / `stopRecording` start/stop semantics, the single-file invariant, frame-processor gate, ingest path) — none of these touched.
- iOS `AVAudioSession`-style active recovery — explicitly out of scope on Android (OEM-dependent `Recording.pause()/resume()` and stop/restart both break the single-file invariant).
- `AudioManager.registerAudioRecordingCallback` backup signal — deferred until field data shows `audioState` misses transitions.

### Tests
- New `AudioStateTrackerTest` (`syncfield-streams/src/test`): pure-JVM coverage of every transition, including idempotence across same-state ticks, error-state aliasing, recover-restall sequences, and reset semantics.
- `SyncFieldVersionTest` bumped to track the new `current`.
- Whole-SDK `./gradlew test` green.

## [0.7.0] — 2026-05-19

Brings Android IMU + camera intrinsics surface area to iOS `syncfield-swift` 0.10.0 parity. The motivating downstream change is og-skill's 3D head/camera/hand pose pipeline (`og-skill/pipeline`): every IMU-based VIO backend (ORB-SLAM3 mono-inertial, OpenVINS/GTSAM, VGGT-IMU) needs raw specific-force (gravity included), raw gyro, and magnetometer values to recover gravity direction, metric scale, and absolute yaw. The previous `AndroidMotionStream` fused stream stripped gravity (TYPE_LINEAR_ACCELERATION) and never wired magnetometer, so Android recordings were unusable for those backends. This release fixes that by mirroring iOS's per-sensor file layout and JSONL schema.

### Added
- **`AndroidRawAccelStream`** (`imu_accel_raw.jsonl`) — raw accelerometer with gravity included. Uses `Sensor.TYPE_ACCELEROMETER` (NOT `TYPE_LINEAR_ACCELERATION`). Equivalent of iOS `iPhoneRawAccelStream`.
- **`AndroidRawGyroStream`** (`imu_gyro_raw.jsonl`) — raw gyroscope (`Sensor.TYPE_GYROSCOPE`). Equivalent of iOS `iPhoneRawGyroStream`.
- **`AndroidRawMagStream`** (`imu_mag_raw.jsonl`) — magnetometer (`Sensor.TYPE_MAGNETIC_FIELD`, μT). Equivalent of iOS `iPhoneRawMagStream`. Enables absolute-yaw bind so VIO doesn't drift over long recordings.
- **`DeliveredCameraIntrinsics`** — data class mirroring the iOS struct of the same name. Carries `fx/fy/cx/cy/sampleWidth/sampleHeight/frameIndex` plus a `source` tag (`LENS_INTRINSIC_CALIBRATION` or `FOCAL_LENGTH_FALLBACK`).
- **`AndroidCameraStream.setIntrinsicMatrixHandler(handler)`** — fires once from `connect()` after camera selection with the resolved intrinsics. The handler prefers `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` (scaled from active-array pixels to the output frame); if that's `null` (common on Samsung/Xiaomi devices) it falls back to `LENS_INFO_AVAILABLE_FOCAL_LENGTHS.min()` + `SENSOR_INFO_PHYSICAL_SIZE` and the pinhole formula `fx = focal_mm × outputW / sensorW`. Mirrors iOS `iPhoneCameraStream.setIntrinsicMatrixHandler`.

### Changed (breaking)
- **`SensorWriter` row schema rename to match iOS:** `frame` → `frame_number`, `timestamp_ns` → `capture_ns`. The optional `device_timestamp_ns` field is unchanged. JSONL row keys are still sorted alphabetically, so the new top-level key order is `capture_ns < channels < device_timestamp_ns < frame_number`.
- **`StreamWriter` row schema rename to match iOS:** `frame` → `frame_number`, `timestamp_ns` → `capture_ns`. Affects `<streamId>.timestamps.jsonl` written by `AndroidCameraStream`. Downstream consumers (notably `og-skill/pipeline/src/slam_vio/io.py`) already expected this iOS shape, so the rename eliminates the iOS-vs-Android schema split.
- **`AndroidMotionStream` renamed to `AndroidDeviceMotionStream`** and the default `streamId` switched from `"imu"` to `"imu_devmotion"`. The class still subscribes to `TYPE_LINEAR_ACCELERATION + TYPE_GYROSCOPE + TYPE_GRAVITY`; this is the Android analog of iOS `iPhoneMotionStream` (`CMDeviceMotion.userAcceleration + rotationRate + gravity`). Apps that want VIO-compatible raw specific-force should add `AndroidRawAccelStream` alongside this; apps that want fused gravity-free user acceleration keep using `AndroidDeviceMotionStream`.

### Compatibility
- Host migration steps for og-skill (separate PR, syncFieldSdkVersion 0.6.0 → 0.7.0):
  1. Rename `AndroidMotionStream` references to `AndroidDeviceMotionStream`, set `streamId = "imu_devmotion"`.
  2. Add three new stream registrations: `AndroidRawAccelStream` / `AndroidRawGyroStream` / `AndroidRawMagStream`, all `rateHz = 100`. Mirrors the iOS bridge module's IMU block.
  3. Wire `setIntrinsicMatrixHandler` on `AndroidCameraStream` to a `CameraIntrinsicsWriter` mirror of the iOS Swift class so `camera_intrinsics.json` lands on disk in the same schema.
- Existing Android recording sessions (0.6.0 and earlier) still upload, but their `imu.jsonl` plus the older `frame` / `timestamp_ns` key names will need a one-off remapping if you want to re-run them through `og-skill/pipeline` post-0.7.0.

### Tests
- New `AndroidRawSensorStreamTest` (`syncfield-streams/src/test`): Robolectric-driven coverage of the three raw stream defaults + channel-map shape, plus a cross-stream test that confirms no fusion leakage across sensor types.
- New `AndroidCameraIntrinsicsTest` (`syncfield-streams/src/test`): pure-JVM coverage of both compute paths (lens-calibration scaling, focal-length pinhole) and their degenerate-input rejection.
- `WritersTest` updated for the iOS schema keys (`capture_ns` / `frame_number`).
- Whole-SDK `./gradlew test` green after migration.

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
