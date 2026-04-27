# syncfield-kotlin

Kotlin port of [syncfield-swift](https://github.com/OpenGraphLabs/syncfield-swift).
Multi-stream synchronized data capture for Android — egocentric video,
IMU, FSR gloves, and Insta360 cameras share a single host clock and
produce a common on-disk layout that the syncfield Python pipeline
ingests.

> **Status — v0.3.0 initial port.** The build is green and the JVM-side
> orchestration logic is fully unit-tested (37 tests, 0 failures), but
> the camera, motion, BLE, and Insta360 paths have only been built and
> not yet exercised on a real device. Insta360 SDK calls are reflective
> stubs awaiting the OneSDK Android AAR — see [Insta360 module](#insta360-module).
> Track real-device readiness in the GitHub issues.

## Modules

| Module               | Purpose                                                                                |
|----------------------|----------------------------------------------------------------------------------------|
| `syncfield-core`     | Session clock, orchestrator, writers, audio chirp synthesis, Android `AudioTrack` chirp player, stream contract |
| `syncfield-streams`  | Android camera (CameraX) and IMU (SensorManager) streams                               |
| `syncfield-tactile`  | Oglo glove BLE GATT client + parser + stream                                           |
| `syncfield-insta360` | Insta360 Go 3S BLE controller + WiFi downloader + camera stream + role registry        |
| `syncfield-ui`       | `SyncFieldPreviewView` (CameraX `PreviewView`-based)                                   |

## Compatibility

- Android 7.0 (API 24) and up
- Kotlin 2.1 / Coroutines 1.9 / AGP 8.12
- Wire-format compatible with `syncfield-swift` v0.3 — `manifest.json`,
  `sync_point.json`, `<streamId>.jsonl`, and `<streamId>.timestamps.jsonl`
  are byte-identical between iOS and Android episodes

## Insta360 module

`syncfield-insta360` integrates with Insta360's official `INSCameraSDK`
Android AAR. The AAR is not on Maven Central — host apps drop
`OneSDK.aar` into their own `app/libs/` and add the corresponding
`flatDir` repository. While the AAR is absent, every Insta360 call
throws `Insta360Error.FrameworkNotLinked` and the rest of the SDK
stays usable; this matches the iOS behaviour where
`canImport(INSCameraServiceSDK)` gates production paths.

The current `Insta360BLEController` is a **scaffold** — its `pair`,
`startRemoteRecording`, `stopRemoteRecording`, and `wifiCredentials`
methods are structured to mirror the iOS implementation but invoke the
SDK reflectively through `Insta360OneSDKBridge`. Filling in the actual
OneSDK calls is a follow-up tracked in the v0.3.1 milestone.

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

## Using as a composite build

Host apps that don't want to publish to a Maven repository can wire
the SDK in directly:

```kotlin
// settings.gradle (host app)
includeBuild('../path/to/syncfield-kotlin')
```

```kotlin
// app/build.gradle (host app)
dependencies {
    implementation 'io.opengraph.syncfield:syncfield-core:0.3.0'
    implementation 'io.opengraph.syncfield:syncfield-streams:0.3.0'
    implementation 'io.opengraph.syncfield:syncfield-tactile:0.3.0'
    implementation 'io.opengraph.syncfield:syncfield-insta360:0.3.0'
    implementation 'io.opengraph.syncfield:syncfield-ui:0.3.0'
}
```

The egonaut Android app uses exactly this pattern — see
`mobile/android/settings.gradle` and `mobile/android/app/build.gradle`.

## Quick start

```kotlin
import io.opengraph.syncfield.*
import io.opengraph.syncfield.audio.AudioTrackChirpPlayer
import io.opengraph.syncfield.streams.AndroidCameraStream
import io.opengraph.syncfield.streams.AndroidMotionStream
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
        val imu = AndroidMotionStream(this, streamId = "imu", rateHz = 100)
        val left = TactileStream(this, "tactile_left", TactileSide.Left)

        lifecycleScope.launch {
            orchestrator.add(cam)
            orchestrator.add(imu)
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
