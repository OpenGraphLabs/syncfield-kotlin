# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.4.0-SNAPSHOT] — 2026-05-10

### Added

- `Insta360Support` capability probe so host apps can fail fast when the
  OneSDK is absent.
- Unit coverage for command queue serialization, Wi-Fi probe policy, and
  OneSDK availability probing.

### Changed

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
