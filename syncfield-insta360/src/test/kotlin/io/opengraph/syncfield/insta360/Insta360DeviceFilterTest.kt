package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Tests for [Insta360BluetoothHub.shouldEmitDevice] — the BLE-advertisement
 * name filter that decides whether a discovered device is an Insta360 GO-family
 * camera. Production bugs caused by an overly narrow filter manifest as
 * "주변 Insta360 카메라를 찾고 있어요…" hanging forever even when the camera
 * is visibly broadcasting.
 *
 * The Red→Green discipline here: any new device name pattern observed in
 * production logs is added as a failing case here FIRST, then the filter is
 * widened just enough to pass.
 */
class Insta360DeviceFilterTest {

    @Test
    fun acceptsGo3sStandardNames() {
        // Names observed from real GO 3S advertisements in scenario captures.
        val names = listOf(
            "GO 3S ABC123",
            "GO3S_ABC123",
            "GO 3S 12345",
            "GO ABC123",   // older firmware short form
            "GO2 XYZ001",  // GO 2 (older sibling), shares filter
        )
        for (name in names) {
            assertWithMessage("name='$name'")
                .that(Insta360BluetoothHub.shouldEmitDevice(name))
                .isTrue()
        }
    }

    @Test
    fun acceptsInstaPrefixedNames() {
        val names = listOf(
            "Insta360 GO 3S",
            "Insta360-GO3S-ABC",
            "INSTA360 ONE X3",  // not a wrist cam but still Insta brand
        )
        for (name in names) {
            assertWithMessage("name='$name'")
                .that(Insta360BluetoothHub.shouldEmitDevice(name))
                .isTrue()
        }
    }

    @Test
    fun rejectsUnrelatedNames() {
        val names = listOf(
            "AirPods Pro",
            "JBL Flip 5",
            "Samsung Galaxy Buds",
            "Tile Mate",
            "Random BLE Beacon",
        )
        for (name in names) {
            assertWithMessage("name='$name'")
                .that(Insta360BluetoothHub.shouldEmitDevice(name))
                .isFalse()
        }
    }

    @Test
    fun rejectsNullAndBlank() {
        assertWithMessage("null").that(Insta360BluetoothHub.shouldEmitDevice(null)).isFalse()
        assertWithMessage("empty").that(Insta360BluetoothHub.shouldEmitDevice("")).isFalse()
        assertWithMessage("whitespace").that(Insta360BluetoothHub.shouldEmitDevice("   ")).isFalse()
    }

    @Test
    fun caseInsensitive() {
        assertWithMessage("lowercase go 3s").that(Insta360BluetoothHub.shouldEmitDevice("go 3s")).isTrue()
        assertWithMessage("uppercase INSTA360").that(Insta360BluetoothHub.shouldEmitDevice("INSTA360")).isTrue()
        assertWithMessage("mixed Go3s").that(Insta360BluetoothHub.shouldEmitDevice("Go3s")).isTrue()
    }
}
