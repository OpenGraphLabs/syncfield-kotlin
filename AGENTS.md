# SyncField Kotlin — agent notes

This file collects load-bearing gotchas that aren't obvious from
reading code. Update it when you trip over something the source itself
won't tell a future agent.

## Insta360 GO 3S — why we bypass the SDK

`com.arashivision.sdk:sdkcamera:1.10.1` is the canonical SDK, but its
`InstaCameraManager.connectBle(BleDevice)` **silently drops** the GO 3S:

- `getSupportCameraType()` is hardcoded to 10 entries and excludes GO3S
  (the enum value `CameraType.GO3S` exists, and `BaseCameraController`
  already has GO3S branches — only the support filter is incomplete).
- Replacing the SDK's `IConfiguration` to include GO3S enables the
  scan filter but `connectBle` is still gated deeper in an obfuscated
  `IBleConnectDelegate` impl — the support wrapper is kept for
  reference (`Insta360SupportConfigWrapper.kt`) but not wired.

The bypass: drive fastble's `BleManager.getInstance().connect(BleDevice,
BleGattCallback)` directly, then reproduce the post-GATT protocol
handshake the SDK normally performs inside `BleConnectCmd`. Without
that handshake the camera tears the GATT link down within ~5 ms
(HCI status=19, REMOTE_USER_TERMINATED_CONNECTION).

## Layered architecture (mirrors iOS)

```
fastble BleManager.connect           ← Insta360DirectGattConnector.connect
  ↓ GATT only
Insta360BleProtocolSession           ← MTU 256, notify on service `be80`,
  ↓ sync packet exchange               sync packet (ab ba × 5) → ff0641
                                       response. Sync is what stops the
                                       camera from disconnecting us.
OneDriver (JNI)                      ← Insta360OneDriverBridge mounts
  ↑ outbound writes → onWrite          OneBleIOCallbacks on the session.
  ↓ putData(bytes, true)               Native side builds packets for
                                       shutter / record / authorization;
                                       inbound notify frames go back into
                                       the parser via putData.
High-level commands                  ← captureStillImage, startRecord,
                                       stopRecord, sendHeartBeat,
                                       checkAuthorization, etc.
```

iOS-equivalent path: `INSBluetoothManager.connect(device)` resolves only
after the same protocol handshake completes. `INSCameraBasicCommands`
returned by `getCommandBy(device)` is `Insta360OneDriverBridge`.

## Critical native-lib detail

Insta360's `libOne.so` references the libc++ 9.0+ ABI symbol
`__cxa_init_primary_exception`. **The matching `libc++_shared.so` is
inside `sdkcamera-1.10.1.aar`**, not the one fbjni/RN bundle. Consumer
apps must override the merged `libc++_shared.so` at the app level
(local `jniLibs/` wins over AAR merge). Without this fix,
`OneDriver`'s static init throws `UnsatisfiedLinkError` and every
`new OneDriver(...)` then fails with `NoClassDefFoundError`.

## Reuse the session — don't re-pair

`Insta360BLEController.pair()` performs GATT + protocol handshake +
OneDriver attach exactly once. Commands (`triggerIdentifyPhoto`,
`startRemoteRecording`, etc.) MUST reuse the live `protocolSession` +
`oneDriverBridge`. Re-pair on every command tears down the active GATT
client and starves subsequent writes (manifests as `gatt requestMtu fail
code=102` and `writeCharacteristic but deviceBusy` cascades).

## Phone authorization is implicit during protocol handshake

`OneDriver.checkAuthorization(uniqueId)` returns `AUTHORIZED` on second
and subsequent connects from the same host (the camera firmware
remembers the host identifier). LCD prompt only fires on first pair
after a camera-side reset. `Insta360PhoneAuthDeviceId.stableId(context)`
provides the iOS `INSConnectionUtils.authorizationId()` equivalent
(seeded from `Settings.Secure.ANDROID_ID`, persisted in SharedPreferences).

## Critical files

| File | Role |
|---|---|
| `Insta360DirectGattConnector.kt` | fastble GATT connect (no SDK) |
| `Insta360BleProtocolSession.kt` | sync handshake + notify + write |
| `Insta360GattHandshake.kt` | composes GATT + protocol — iOS `INSBluetoothManager.connect` equivalent |
| `Insta360ProtocolPackets.kt` | wire-format constants (service `be80`, MTU 256, sync bytes, heartbeat hex) |
| `Insta360OneDriverBridge.kt` | OneBleIOCallbacks ↔ ProtocolSession plumbing; iOS `INSCameraBasicCommands` equivalent |
| `Insta360BLEController.kt` | per-camera controller; orchestrates pair → handshake → bridge attach → command dispatch |

## Reference

`/tmp/minicam-src-extract/com/arashivision/camera/command/ble/BleConnectCmd.java`
is the protocol-handshake reference. Extract it again with:
```sh
jar -xf ~/.gradle/caches/modules-2/files-2.1/com.arashivision.minicamera/minicamera/*/*/minicamera-*-sources.jar
```
