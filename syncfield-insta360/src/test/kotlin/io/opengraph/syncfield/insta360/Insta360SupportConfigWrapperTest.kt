package io.opengraph.syncfield.insta360

import com.arashivision.insta360.basecamera.camera.BaseCamera
import com.arashivision.insta360.basecamera.camera.CameraManager
import com.arashivision.insta360.basecamera.camera.CameraType
import com.arashivision.insta360.basecamera.camera.check.ICameraCheck
import com.clj.fastble.data.BleDevice
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Tests for [Insta360SupportConfigWrapper] — a decorator over the SDK's
 * own [CameraManager.IConfiguration] that adds [CameraType.GO3S] to the
 * support list while transparently delegating every other interface
 * method to the wrapped instance.
 *
 * Why this exists:
 *   Insta360 Android SDK 1.10.1 hardcodes a 10-entry support list inside
 *   its default `IConfiguration` impl, and GO3S is missing from that list
 *   (the enum itself ships with `CameraType.GO3S`, and
 *   `BaseCameraController` has explicit GO3S branches — only the support
 *   filter is incomplete). Without GO3S in the list, the SDK silently
 *   drops every GO3S advertisement during scan AND refuses to surface
 *   `onCameraStatusChanged(BLE, true)` after a `connectBle()` succeeds at
 *   the GATT layer. iOS works because the iOS SDK exposes a lower-level
 *   `INSBluetoothManager` that skips this filter; Android does the same
 *   thing through the official `CameraManager.setConfiguration()`
 *   extension point.
 *
 * Tested invariants (Red→Green):
 *   1. GO3S is present in the wrapper's `getSupportCamera()` regardless of
 *      whether the inner impl already includes it.
 *   2. Original entries are preserved in order; GO3S is appended when
 *      missing (so consumers iterating the list keep their existing
 *      ordering for the 10 vendor-blessed types).
 *   3. No duplicate GO3S entries when the inner already contains GO3S
 *      (defensive — guards against compounding when wrapper is applied
 *      more than once).
 *   4. All other 14 [CameraManager.IConfiguration] methods delegate to the
 *      inner instance with identical arguments.
 */
class Insta360SupportConfigWrapperTest {

    @Test
    fun `getSupportCamera adds GO3S when inner omits it`() {
        val innerList = listOf(CameraType.X3, CameraType.X4, CameraType.X5)
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { supportCamera } returns innerList
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.supportCamera).containsExactly(
            CameraType.X3, CameraType.X4, CameraType.X5, CameraType.GO3S,
        ).inOrder()
    }

    @Test
    fun `getSupportCamera preserves existing GO3S without duplicating`() {
        val innerList = listOf(CameraType.X4, CameraType.GO3S, CameraType.X5)
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { supportCamera } returns innerList
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.supportCamera).containsExactly(
            CameraType.X4, CameraType.GO3S, CameraType.X5,
        ).inOrder()
    }

    @Test
    fun `getSupportCamera tolerates inner returning empty list`() {
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { supportCamera } returns emptyList()
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.supportCamera).containsExactly(CameraType.GO3S)
    }

    @Test
    fun `needCheckAuthorization delegates`() {
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { needCheckAuthorization("cam-A") } returns true
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.needCheckAuthorization("cam-A")).isTrue()
        verify { inner.needCheckAuthorization("cam-A") }
    }

    @Test
    fun `getForbidActiveCamera delegates`() {
        val forbid = listOf("EVO", "ONE")
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { forbidActiveCamera } returns forbid
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.forbidActiveCamera).isSameInstanceAs(forbid)
    }

    @Test
    fun `getCameraCheckActivationList delegates with both args`() {
        val checks = listOf<ICameraCheck>()
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every {
                getCameraCheckActivationList("serial-1", BaseCamera.ConnectType.BLE)
            } returns checks
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(
            wrapper.getCameraCheckActivationList("serial-1", BaseCamera.ConnectType.BLE),
        ).isSameInstanceAs(checks)
        verify {
            inner.getCameraCheckActivationList("serial-1", BaseCamera.ConnectType.BLE)
        }
    }

    @Test
    fun `onOpenCamera delegates`() {
        val cams = emptyList<BaseCamera>()
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { onOpenCamera(BaseCamera.ConnectType.WIFI, cams) } returns true
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.onOpenCamera(BaseCamera.ConnectType.WIFI, cams)).isTrue()
    }

    @Test
    fun `boolean and string getters all delegate`() {
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every { savePreviewStreamAndGyroData() } returns true
            every { isVpnEstablishedByOtherApp } returns false
            every { hasBindWifiNetwork() } returns true
            every { isAuthorized("k") } returns true
            every { customFwVersion } returns "1.2.3"
            every { onlySupportBleConnect() } returns false
            every { getBleScanProtoDeviceType(42) } returns "proto-42"
            every { transformBleDeviceName("GO 3S 2BNMWH") } returns "GO 3S 2BNMWH"
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.savePreviewStreamAndGyroData()).isTrue()
        assertThat(wrapper.isVpnEstablishedByOtherApp).isFalse()
        assertThat(wrapper.hasBindWifiNetwork()).isTrue()
        assertThat(wrapper.isAuthorized("k")).isTrue()
        assertThat(wrapper.customFwVersion).isEqualTo("1.2.3")
        assertThat(wrapper.onlySupportBleConnect()).isFalse()
        assertThat(wrapper.getBleScanProtoDeviceType(42)).isEqualTo("proto-42")
        assertThat(wrapper.transformBleDeviceName("GO 3S 2BNMWH")).isEqualTo("GO 3S 2BNMWH")
    }

    @Test
    fun `isSupportBleErrorSkip delegates with both args`() {
        val device = mockk<BleDevice>()
        val inner = mockk<CameraManager.IConfiguration>(relaxed = true) {
            every {
                isSupportBleErrorSkip(device, BaseCamera.ConnectType.BLE)
            } returns true
        }

        val wrapper = Insta360SupportConfigWrapper(inner)

        assertThat(wrapper.isSupportBleErrorSkip(device, BaseCamera.ConnectType.BLE)).isTrue()
        verify { inner.isSupportBleErrorSkip(device, BaseCamera.ConnectType.BLE) }
    }
}
