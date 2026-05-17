package io.opengraph.syncfield.insta360

import com.arashivision.insta360.basecamera.camera.BaseCamera
import com.arashivision.insta360.basecamera.camera.CameraManager
import com.arashivision.insta360.basecamera.camera.CameraType
import com.arashivision.insta360.basecamera.camera.check.ICameraCheck
import com.clj.fastble.data.BleDevice

/**
 * Decorator over [CameraManager.IConfiguration] that injects
 * [CameraType.GO3S] into the SDK's support list.
 *
 * Insta360 Android SDK 1.10.1 ships a default `IConfiguration` whose
 * `getSupportCamera()` returns 10 entries — none of which are GO3S —
 * even though `CameraType.GO3S` is a real enum value and
 * `BaseCameraController` already contains GO3S-specific branches. The
 * missing entry silently disables GO3S in two places:
 *
 *   1. BLE scan — `InstaCameraManager.startBleScan()`'s internal
 *      `onScanning` filter drops every advertisement whose
 *      `CameraType.getForType(...)` resolves outside the support list.
 *   2. BLE connect — after a successful GATT connect (fastble layer),
 *      the SDK builds a `BaseCamera` only when its inferred type is in
 *      the support list. Without that, `onCameraStatusChanged(BLE, true)`
 *      never fires and any caller awaiting it hangs forever.
 *
 * Installing this wrapper via `CameraManager.getInstance().setConfiguration(...)`
 * after `InstaCameraSDK.init(application)` re-enables GO3S through the
 * SDK's normal high-level command path — no fastble re-implementation
 * required.
 */
internal class Insta360SupportConfigWrapper(
    private val inner: CameraManager.IConfiguration,
) : CameraManager.IConfiguration {

    override fun getSupportCamera(): List<CameraType> {
        val base = inner.supportCamera
        if (base.contains(CameraType.GO3S)) return base
        // Preserve original ordering, append GO3S so callers iterating
        // the existing 10 entries observe no behavioral change.
        return base + CameraType.GO3S
    }

    override fun needCheckAuthorization(serial: String): Boolean =
        inner.needCheckAuthorization(serial)

    override fun getForbidActiveCamera(): List<String> = inner.forbidActiveCamera

    override fun getCameraCheckActivationList(
        serial: String,
        connectType: BaseCamera.ConnectType,
    ): List<ICameraCheck> = inner.getCameraCheckActivationList(serial, connectType)

    override fun onOpenCamera(
        connectType: BaseCamera.ConnectType,
        cameras: List<BaseCamera>,
    ): Boolean = inner.onOpenCamera(connectType, cameras)

    override fun savePreviewStreamAndGyroData(): Boolean =
        inner.savePreviewStreamAndGyroData()

    override fun isVpnEstablishedByOtherApp(): Boolean = inner.isVpnEstablishedByOtherApp

    override fun hasBindWifiNetwork(): Boolean = inner.hasBindWifiNetwork()

    override fun isAuthorized(key: String): Boolean = inner.isAuthorized(key)

    override fun getCustomFwVersion(): String = inner.customFwVersion

    override fun onlySupportBleConnect(): Boolean = inner.onlySupportBleConnect()

    override fun isSupportBleErrorSkip(
        device: BleDevice?,
        connectType: BaseCamera.ConnectType,
    ): Boolean = inner.isSupportBleErrorSkip(device, connectType)

    override fun getBleScanProtoDeviceType(type: Int): String =
        inner.getBleScanProtoDeviceType(type)

    override fun transformBleDeviceName(name: String): String =
        inner.transformBleDeviceName(name)
}
