package io.opengraph.syncfield.insta360

sealed class Insta360Error(message: String) : Exception(message) {

    object FrameworkNotLinked :
        Insta360Error("Insta360Error: Insta360 Android SDK is not on the classpath")

    object NotPaired :
        Insta360Error("Insta360Error: camera is not paired - call connect() first")

    object NotConnected :
        Insta360Error("Insta360Error: SyncField session is not connected")

    object WifiCredentialsUnavailable :
        Insta360Error("Insta360Error: camera did not provide WiFi SSID/passphrase over BLE")

    class HotspotApplyFailed(detail: String) :
        Insta360Error("Insta360Error: WiFi network suggestion apply failed ($detail)")

    class DownloadFailed(detail: String) :
        Insta360Error("Insta360Error: $detail")

    class CommandFailed(detail: String) :
        Insta360Error("Insta360Error: BLE command failed ($detail)")

    object ScanAlreadyActive :
        Insta360Error("Insta360Error: BLE scan is already active")

    class DeviceNotDiscovered(uuid: String) :
        Insta360Error("Insta360Error: camera '$uuid' is not in the current scan set")

    class DeviceNotPaired(uuid: String) :
        Insta360Error("Insta360Error: camera '$uuid' is not paired")

    class IdentifyPhotoFailed(detail: String) :
        Insta360Error("Insta360Error: identify photo failed ($detail)")

    object CameraNotReachable :
        Insta360Error("Insta360Error: camera AP reachable timeout at 192.168.42.1")

    class InvalidWristRole(role: String) :
        Insta360Error("Insta360Error: invalid wrist role '$role' (expected ego|left|right)")

    object RoleConflict :
        Insta360Error("Insta360Error: another camera already claimed this wrist role")

    class RoleAlreadyPaired(role: String) :
        Insta360Error("Insta360Error: wrist role '$role' already has a paired camera")
}
