package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360WifiEnablePlanTest {

    @Test
    fun confirmedSetOptionsStillOpensCameraWifiOnAndroid() {
        val plan = wifiEnablePlanAfterSetOptions(WifiEnableState.Confirmed)

        assertThat(plan.openCameraWifi).isTrue()
        assertThat(plan.requireOpenCameraWifiSuccess).isFalse()
    }

    @Test
    fun sentSetOptionsStillOpensCameraWifiOnAndroid() {
        val plan = wifiEnablePlanAfterSetOptions(WifiEnableState.Sent)

        assertThat(plan.openCameraWifi).isTrue()
        assertThat(plan.requireOpenCameraWifiSuccess).isFalse()
    }

    @Test
    fun rejectedSetOptionsRequiresOpenCameraWifiSuccess() {
        val plan = wifiEnablePlanAfterSetOptions(WifiEnableState.NotSent)

        assertThat(plan.openCameraWifi).isTrue()
        assertThat(plan.requireOpenCameraWifiSuccess).isTrue()
    }
}
