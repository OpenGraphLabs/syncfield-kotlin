# syncfield-kotlin

Kotlin port of [syncfield-swift](https://github.com/OpenGraphLabs/syncfield-swift).
Multi-stream synchronized data capture for Android — egocentric video,
IMU, FSR gloves, and Insta360 cameras share a single host clock and
produce a common on-disk layout that the syncfield Python pipeline
ingests.

> **Status — v0.5.0.** The Insta360 module reaches feature parity with
> `syncfield-swift` v0.9.x (per-camera supervisor, radio gate, connection
> coordinator, multi-camera collector, identity store, background lifecycle)
> and has been validated against Android GO 3S dual-wrist recording,
> Wi-Fi collect, and file ingestion.

## Modules

| Module               | Purpose                                                                                |
|----------------------|----------------------------------------------------------------------------------------|
| `syncfield-core`     | Session clock, orchestrator, writers, audio chirp synthesis, Android `AudioTrack` chirp player, stream contract |
| `syncfield-streams`  | Android camera (CameraX) and IMU (SensorManager) streams                               |
| `syncfield-tactile`  | Oglo glove BLE GATT client + parser + stream                                           |
| `syncfield-insta360` | Insta360 Go 3S BLE controller + WiFi downloader + camera stream + role registry        |
| `syncfield-ui`       | `SyncFieldPreviewView` (CameraX `PreviewView`-based)                                   |

## Compatibility

- Android 8.0 (API 26) and up — `java.time` is the session-clock and
  log-writer backbone, and on API 24/25 it requires core-library
  desugaring at every consumer site, which isn't worth the friction
  for the tiny Android 7 share remaining in 2026
- Kotlin 2.1 / Coroutines 1.9 / AGP 8.12
- Wire-format compatible with `syncfield-swift` v0.3 — `manifest.json`,
  `sync_point.json`, `<streamId>.jsonl`, and `<streamId>.timestamps.jsonl`
  are byte-identical between iOS and Android episodes

## Insta360 module

`syncfield-insta360` integrates directly with Insta360's official
`com.arashivision.sdk:sdkcamera` (OneSDK 1.10.1) — no reflection
scaffolds. OneSDK is pulled transitively from Insta360's own Maven
repository, so the host app needs that repository registered in its
`settings.gradle.kts` alongside the standard ones:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://androidsdk.insta360.com/repository/maven-public/") {
            credentials {
                username = "insta360guest"
                password = "EXMSjSo8OeOrjU7d"
            }
        }
    }
}
```

The public Insta360 API exposed by this module covers:

- **`Insta360BLEController`** — per-camera BLE control (pair, start /
  stop remote recording, Wi-Fi credentials, heartbeat, phone auth)
- **`Insta360ConnectionCoordinator`** + **`Insta360CameraSupervisor`** —
  process-wide state machine, reconnect backoff, wake-stall surfacing
- **`Insta360RadioGate`** — Wi-Fi serialization across cameras
- **`Insta360Collector`** — multi-camera batch downloader with
  prefetch-pair-then-sequential semantics
- **`Insta360BackgroundSupervisor`** — `ProcessLifecycleOwner`
  integration for background / foreground transitions
- **`Insta360IdentityStore`** — JSON-backed phone-authorization +
  last-known UUID cache, wire-compatible with iOS

`Insta360Support.available` is kept as a `Class.forName` capability
flag so R8 / ProGuard configurations that strip the OneSDK still
fail-fast cleanly.

## Building

You need:

- JDK 17
- Android SDK with platform 34 + build-tools 34
- (Optional, for `syncfield-insta360` runtime) Insta360 OneSDK Android
  AAR dropped into `app/libs/` and referenced via `flatDir`

```sh
# Print all gradle tasks
./gradlew tasks

# Compile every module + run unit tests
./gradlew assembleDebug test

# Just the core unit tests (fastest signal during development)
./gradlew :syncfield-core:testDebugUnitTest

# Tactile parser tests
./gradlew :syncfield-tactile:testDebugUnitTest
```

If you don't have an Android SDK installed yet:

```sh
# macOS via Homebrew
brew install --cask android-commandlinetools
sdkmanager --install \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "platform-tools"
sdkmanager --licenses        # accept y/y/...

# Tell Gradle where the SDK lives
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

## Using the released SDK

The public release is available through JitPack. Add the repository to
the host app's dependency repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then depend on the modules the host app needs:

```kotlin
dependencies {
    implementation("com.github.OpenGraphLabs.syncfield-kotlin:syncfield-core:v0.5.0")
    implementation("com.github.OpenGraphLabs.syncfield-kotlin:syncfield-streams:v0.5.0")
    implementation("com.github.OpenGraphLabs.syncfield-kotlin:syncfield-tactile:v0.5.0")
    implementation("com.github.OpenGraphLabs.syncfield-kotlin:syncfield-insta360:v0.5.0")
    implementation("com.github.OpenGraphLabs.syncfield-kotlin:syncfield-ui:v0.5.0")
}
```

The previous stable line is available at `v0.4.0` (Insta360 pairing
only, no supervisor stack).

## Using as a composite build

For local SDK development, host apps can still wire the SDK in directly:

```kotlin
// settings.gradle (host app)
includeBuild('../path/to/syncfield-kotlin')
```

```kotlin
// app/build.gradle (host app)
dependencies {
    implementation("io.opengraph.syncfield:syncfield-core:0.5.0")
    implementation("io.opengraph.syncfield:syncfield-streams:0.5.0")
    implementation("io.opengraph.syncfield:syncfield-tactile:0.5.0")
    implementation("io.opengraph.syncfield:syncfield-insta360:0.5.0")
    implementation("io.opengraph.syncfield:syncfield-ui:0.5.0")
}
```

The egonaut Android app should use the released JitPack artifacts for
normal builds and reserve composite builds for active SDK development.

## Quick start

```kotlin
import io.opengraph.syncfield.*
import io.opengraph.syncfield.audio.AudioTrackChirpPlayer
import io.opengraph.syncfield.streams.AndroidCameraStream
import io.opengraph.syncfield.streams.AndroidDeviceMotionStream
import io.opengraph.syncfield.streams.AndroidRawAccelStream
import io.opengraph.syncfield.streams.AndroidRawGyroStream
import io.opengraph.syncfield.streams.AndroidRawMagStream
import io.opengraph.syncfield.tactile.TactileSide
import io.opengraph.syncfield.tactile.TactileStream
import java.io.File

class RecordingActivity : AppCompatActivity() {

    private lateinit var orchestrator: SessionOrchestrator

    override fun onResume() {
        super.onResume()
        orchestrator = SessionOrchestrator(
            hostId = "android-${Build.MODEL}",
            outputDirectory = File(filesDir, "episodes"),
            chirpPlayer = AudioTrackChirpPlayer(),
        )

        val cam = AndroidCameraStream(
            context = this,
            lifecycleOwner = this,
            streamId = "cam_ego",
        )
        val devmotion = AndroidDeviceMotionStream(this, streamId = "imu_devmotion", rateHz = 100)
        val accel = AndroidRawAccelStream(this, streamId = "imu_accel_raw", rateHz = 100)
        val gyro = AndroidRawGyroStream(this, streamId = "imu_gyro_raw", rateHz = 100)
        val mag = AndroidRawMagStream(this, streamId = "imu_mag_raw", rateHz = 100)
        val left = TactileStream(this, "tactile_left", TactileSide.Left)

        lifecycleScope.launch {
            orchestrator.add(cam)
            orchestrator.add(devmotion)
            orchestrator.add(accel)
            orchestrator.add(gyro)
            orchestrator.add(mag)
            orchestrator.add(left)
            orchestrator.connect()

            val sp = orchestrator.startRecording()
            // … record …
            val stopReport = orchestrator.stopRecording()
            val ingestReport = orchestrator.ingest { /* progress */ }
            orchestrator.disconnect()
        }
    }
}
```
