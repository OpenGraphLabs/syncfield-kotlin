package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360WifiCredentialsTest {

    @Test
    fun resolveWifiCredentials_prefersCurrentDeviceDerivedSsidOverStaleSdkSsid() {
        val resolved = resolveInsta360WifiCredentials(
            deviceName = "GO 3S 1TEBJJ",
            stableId = "GO 3S 1TEBJJDE:6C:62:7B:3C:0E",
            sdkSsid = "GO 3S 2BNMWH.OSC",
            sdkPassword = "stale-password",
        )

        assertThat(resolved.ssid).isEqualTo("GO 3S 1TEBJJ.OSC")
        assertThat(resolved.password).isEqualTo(INSTA360_DEFAULT_WIFI_PASSWORD)
        assertThat(resolved.ssidSource).isEqualTo("derived")
        assertThat(resolved.passwordSource).isEqualTo("default")
    }

    @Test
    fun resolveWifiCredentials_usesSdkPasswordWhenSdkSsidMatchesCurrentDevice() {
        val resolved = resolveInsta360WifiCredentials(
            deviceName = "GO 3S 1TEBJJ",
            stableId = "GO 3S 1TEBJJDE:6C:62:7B:3C:0E",
            sdkSsid = "GO 3S 1TEBJJ.OSC",
            sdkPassword = "camera-password",
        )

        assertThat(resolved.ssid).isEqualTo("GO 3S 1TEBJJ.OSC")
        assertThat(resolved.password).isEqualTo("camera-password")
        assertThat(resolved.ssidSource).isEqualTo("derived")
        assertThat(resolved.passwordSource).isEqualTo("sdk")
    }

    @Test
    fun resolveWifiCredentials_prefersFreshSdkSsidWhenRequested() {
        val resolved = resolveInsta360WifiCredentials(
            deviceName = "GO 3S 1TEBJJ",
            stableId = "GO 3S 1TEBJJDE:6C:62:7B:3C:0E",
            sdkSsid = "Insta360-GO3S-actual.OSC",
            sdkPassword = "camera-password",
            preferSdkSsid = true,
        )

        assertThat(resolved.ssid).isEqualTo("Insta360-GO3S-actual.OSC")
        assertThat(resolved.password).isEqualTo("camera-password")
        assertThat(resolved.ssidSource).isEqualTo("sdk")
        assertThat(resolved.passwordSource).isEqualTo("sdk")
    }

    @Test
    fun resolveWifiCredentials_stripsAndroidQuotedSsid() {
        val resolved = resolveInsta360WifiCredentials(
            deviceName = null,
            stableId = null,
            sdkSsid = "\"GO 3S 2BNMWH.OSC\"",
            sdkPassword = null,
        )

        assertThat(resolved.ssid).isEqualTo("GO 3S 2BNMWH.OSC")
        assertThat(resolved.password).isEqualTo(INSTA360_DEFAULT_WIFI_PASSWORD)
        assertThat(resolved.ssidSource).isEqualTo("sdk")
        assertThat(resolved.passwordSource).isEqualTo("default")
    }
}
