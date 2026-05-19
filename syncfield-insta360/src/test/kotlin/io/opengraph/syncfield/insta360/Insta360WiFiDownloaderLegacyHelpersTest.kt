package io.opengraph.syncfield.insta360

import android.net.wifi.WifiConfiguration
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for the pre-Q (Android 9 / API 28) fallback helpers
 * exposed by `Insta360WiFiDownloader.kt`. The actual join flow lives
 * inside [Insta360WiFiDownloader] and depends on `WifiManager` +
 * `ConnectivityManager` instances we don't realistically own from a
 * JVM unit test — but the two pure helpers below are what determine
 * whether the legacy path connects to the *right* SSID and whether
 * the supplicant accepts our [WifiConfiguration]. Both are
 * load-bearing: a wrong `KeyMgmt` bit silently fails to associate;
 * a wrong SSID-quote rule swaps which network we bind to.
 *
 * Runs under Robolectric so we can instantiate `WifiConfiguration`
 * (an Android framework class). Configured to SDK 28 to match the
 * platform we're trying to support.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Insta360WiFiDownloaderLegacyHelpersTest {

    // --- buildLegacyWifiConfiguration --------------------------------------

    @Test
    fun `wifi configuration wraps ssid and passphrase in literal quotes`() {
        val config = buildLegacyWifiConfiguration(
            ssid = "GO 3S 1234.OSC",
            passphrase = "supersecret",
            hiddenSsid = false,
        )
        assertThat(config.SSID).isEqualTo("\"GO 3S 1234.OSC\"")
        assertThat(config.preSharedKey).isEqualTo("\"supersecret\"")
    }

    @Test
    fun `wifi configuration enables WPA_PSK key management`() {
        val config = buildLegacyWifiConfiguration(
            ssid = "cam",
            passphrase = "pw",
            hiddenSsid = false,
        )
        assertThat(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)).isTrue()
    }

    @Test
    fun `wifi configuration sets hiddenSSID only when requested`() {
        val visible = buildLegacyWifiConfiguration("cam", "pw", hiddenSsid = false)
        val hidden = buildLegacyWifiConfiguration("cam", "pw", hiddenSsid = true)
        assertThat(visible.hiddenSSID).isFalse()
        assertThat(hidden.hiddenSSID).isTrue()
    }

    @Test
    fun `wifi configuration allows both RSN and WPA protocols`() {
        val config = buildLegacyWifiConfiguration("cam", "pw", hiddenSsid = false)
        // GO 3S firmware versions in the wild advertise both — leaving
        // either out has caused association timeouts on Pie OEMs.
        assertThat(config.allowedProtocols.get(WifiConfiguration.Protocol.RSN)).isTrue()
        assertThat(config.allowedProtocols.get(WifiConfiguration.Protocol.WPA)).isTrue()
    }

    @Test
    fun `wifi configuration allows CCMP and TKIP ciphers for cross-OEM compatibility`() {
        val config = buildLegacyWifiConfiguration("cam", "pw", hiddenSsid = false)
        assertThat(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.CCMP)).isTrue()
        assertThat(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.TKIP)).isTrue()
        assertThat(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.CCMP)).isTrue()
        assertThat(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.TKIP)).isTrue()
    }

    // --- legacyConnectionMatchesTarget ------------------------------------

    @Test
    fun `ssid match strips literal quotes`() {
        assertThat(legacyConnectionMatchesTarget("\"camera-1234\"", "camera-1234")).isTrue()
    }

    @Test
    fun `ssid match accepts unquoted strings`() {
        // Some OEM ROMs return SSIDs without surrounding quotes.
        assertThat(legacyConnectionMatchesTarget("camera-1234", "camera-1234")).isTrue()
    }

    @Test
    fun `ssid match rejects unknown ssid placeholder`() {
        // Returned before association completes or when ACCESS_FINE_LOCATION
        // is missing at runtime. Must never be treated as a match.
        assertThat(legacyConnectionMatchesTarget("<unknown ssid>", "camera-1234")).isFalse()
        assertThat(legacyConnectionMatchesTarget("\"<unknown ssid>\"", "camera-1234")).isFalse()
        assertThat(legacyConnectionMatchesTarget("<UNKNOWN SSID>", "camera-1234")).isFalse()
    }

    @Test
    fun `ssid match rejects null and blank`() {
        assertThat(legacyConnectionMatchesTarget(null, "camera-1234")).isFalse()
        assertThat(legacyConnectionMatchesTarget("", "camera-1234")).isFalse()
        assertThat(legacyConnectionMatchesTarget("   ", "camera-1234")).isFalse()
        assertThat(legacyConnectionMatchesTarget("\"\"", "camera-1234")).isFalse()
    }

    @Test
    fun `ssid match rejects different ssid`() {
        // Critical: when the user's home Wi-Fi briefly reappears during the
        // legacy join transition, the callback's onAvailable will fire with
        // a Network for the home network. We must NOT bind to it.
        assertThat(legacyConnectionMatchesTarget("\"home-wifi\"", "camera-1234")).isFalse()
        assertThat(legacyConnectionMatchesTarget("home-wifi", "camera-1234")).isFalse()
    }

    @Test
    fun `ssid match is case sensitive`() {
        // SSIDs are byte-string identifiers; Android's supplicant preserves
        // case. Matching must too — "CAMERA" and "camera" are different APs.
        assertThat(legacyConnectionMatchesTarget("\"Camera\"", "camera")).isFalse()
    }

    @Test
    fun `ssid match handles ssid containing special characters`() {
        // GO 3S broadcasts contain spaces and dots: "GO 3S 1234.OSC".
        val ssid = "GO 3S 1234.OSC"
        assertThat(legacyConnectionMatchesTarget("\"$ssid\"", ssid)).isTrue()
    }

    // --- Build.VERSION sanity for the dispatcher --------------------------

    /**
     * Sanity: the dispatcher in [Insta360WiFiDownloader.applyCameraNetwork]
     * uses `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`. Under this
     * test class's `@Config(sdk = [28])` annotation Robolectric must
     * report SDK_INT as 28 — otherwise we'd be silently exercising the
     * modern path on what the test claims is a Pie device. This guards
     * against accidental `@Config` removal in future refactors.
     */
    @Test
    fun `robolectric reports sdk 28 under Config 28 so dispatcher routes to legacy branch`() {
        assertThat(android.os.Build.VERSION.SDK_INT).isEqualTo(28)
        assertThat(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q).isFalse()
    }
}

/**
 * Sibling test class held to SDK 29 so we get matching coverage for
 * the modern dispatcher branch. Pure sanity — same helper used by the
 * production dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Insta360WiFiDownloaderModernDispatchSanityTest {
    @Test
    fun `robolectric reports sdk 29 under Config 29 so dispatcher routes to modern branch`() {
        assertThat(android.os.Build.VERSION.SDK_INT).isEqualTo(29)
        assertThat(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q).isTrue()
    }
}
