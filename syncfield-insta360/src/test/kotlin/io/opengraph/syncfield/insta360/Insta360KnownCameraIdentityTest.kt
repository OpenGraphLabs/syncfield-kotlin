package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360KnownCameraIdentityTest {

    @Test
    fun create_withFullData() {
        val id = Insta360KnownCameraIdentity.create(uuid = "ABCD-1234", bleName = "GO ABC123")
        assertThat(id.uuid).isEqualTo("ABCD-1234")
        assertThat(id.preferredBLEName).isEqualTo("GO ABC123")
        assertThat(id.serialLast6).isEqualTo("ABC123")
        assertThat(id.isUsable).isTrue()
        assertThat(id.bindingKey).isEqualTo("ABCD-1234")
    }

    @Test
    fun create_uuidOnly() {
        val id = Insta360KnownCameraIdentity.create(uuid = "uuid-only", bleName = null)
        assertThat(id.uuid).isEqualTo("uuid-only")
        assertThat(id.preferredBLEName).isNull()
        assertThat(id.serialLast6).isNull()
        assertThat(id.bindingKey).isEqualTo("uuid-only")
    }

    @Test
    fun create_nameOnly_withValidSerial() {
        val id = Insta360KnownCameraIdentity.create(uuid = null, bleName = "GO XYZ789")
        assertThat(id.uuid).isNull()
        assertThat(id.preferredBLEName).isEqualTo("GO XYZ789")
        assertThat(id.serialLast6).isEqualTo("XYZ789")
        assertThat(id.bindingKey).isEqualTo("serial:XYZ789")
    }

    @Test
    fun create_nameOnly_withoutSerialFallbackToNameKey() {
        // Single token (no space) — name preserved but no serial extracted
        val id = Insta360KnownCameraIdentity.create(uuid = null, bleName = "GoCamera")
        assertThat(id.preferredBLEName).isEqualTo("GoCamera")
        assertThat(id.serialLast6).isNull()
        assertThat(id.bindingKey).isEqualTo("name:GoCamera")
    }

    @Test
    fun create_emptyStringsAreNulled() {
        val id = Insta360KnownCameraIdentity.create(uuid = "  ", bleName = "  ")
        assertThat(id.uuid).isNull()
        assertThat(id.preferredBLEName).isNull()
        assertThat(id.serialLast6).isNull()
        assertThat(id.isUsable).isFalse()
        assertThat(id.bindingKey).isNull()
    }

    @Test
    fun create_internalWhitespaceCollapsed() {
        val id = Insta360KnownCameraIdentity.create(uuid = null, bleName = "  GO   ABC123  ")
        assertThat(id.preferredBLEName).isEqualTo("GO ABC123")
        assertThat(id.serialLast6).isEqualTo("ABC123")
    }

    @Test
    fun extractSerialLast6_validInputs() {
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("GO ABC123")).isEqualTo("ABC123")
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("GO 3S DEF456")).isEqualTo("DEF456")
    }

    @Test
    fun extractSerialLast6_invalidInputs() {
        // Last token not exactly 6 chars
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("GO ABC12")).isNull()
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("GO ABCDEFG")).isNull()
        // Single token
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("GoCamera")).isNull()
        // Empty
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("")).isNull()
        assertThat(Insta360KnownCameraIdentity.extractSerialLast6("   ")).isNull()
    }

    @Test
    fun encodeWakeId_hexAsciiPerChar() {
        // 'A' = 0x41, 'B' = 0x42, 'C' = 0x43, '1' = 0x31, '2' = 0x32, '3' = 0x33
        assertThat(Insta360KnownCameraIdentity.encodeWakeId("ABC123")).isEqualTo("414243313233")
    }

    @Test
    fun encodeWakeId_truncatesBeyondSix() {
        assertThat(Insta360KnownCameraIdentity.encodeWakeId("ABCDEFGH")).hasLength(12)
    }
}
