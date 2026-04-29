package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360BluetoothHubTest {

    @Test
    fun shouldEmitDeviceAcceptsGoFamilyNamesCaseInsensitively() {
        assertThat(Insta360BluetoothHub.shouldEmitDevice("Insta360 GO 3S")).isTrue()
        assertThat(Insta360BluetoothHub.shouldEmitDevice("go3s-left")).isTrue()
    }

    @Test
    fun shouldEmitDeviceRejectsNullAndNonGoNames() {
        assertThat(Insta360BluetoothHub.shouldEmitDevice(null)).isFalse()
        assertThat(Insta360BluetoothHub.shouldEmitDevice("X4 123456")).isFalse()
    }

    @Test
    fun streamIdMatchesIosRoleMapping() {
        assertThat(Insta360BluetoothHub.streamId("ego")).isEqualTo("cam_wrist_ego")
        assertThat(Insta360BluetoothHub.streamId("left")).isEqualTo("cam_wrist_left")
        assertThat(Insta360BluetoothHub.streamId("right")).isEqualTo("cam_wrist_right")
    }
}
