package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360ConnectionHealthTest {

    @Test
    fun defaults_sensible() {
        val h = Insta360ConnectionHealth(bindingKey = "B1")
        assertThat(h.bindingKey).isEqualTo("B1")
        assertThat(h.role).isNull()
        assertThat(h.state).isEqualTo(Insta360ConnectionState.IDLE)
        assertThat(h.rssi).isNull()
        assertThat(h.consecutiveHeartbeatMisses).isEqualTo(0)
        assertThat(h.reconnectAttempt).isEqualTo(0)
    }

    @Test
    fun toMap_keysAreCamelCase() {
        val h = Insta360ConnectionHealth(
            bindingKey = "B1",
            role = "left",
            state = Insta360ConnectionState.BLE_READY,
            rssi = -45,
            lastSeenAtMs = 1234L,
            lastStateChangeAtMs = 1100L,
            consecutiveHeartbeatMisses = 0,
            reconnectAttempt = 0,
            lastError = null,
        )
        val m = h.toMap()
        assertThat(m["bindingKey"]).isEqualTo("B1")
        assertThat(m["role"]).isEqualTo("left")
        // state must serialize as wire name, not enum constant
        assertThat(m["state"]).isEqualTo("bleReady")
        assertThat(m["rssi"]).isEqualTo(-45)
        assertThat(m["lastSeenAtMs"]).isEqualTo(1234L)
        assertThat(m["lastStateChangeAtMs"]).isEqualTo(1100L)
        assertThat(m["consecutiveHeartbeatMisses"]).isEqualTo(0)
        assertThat(m["reconnectAttempt"]).isEqualTo(0)
        assertThat(m["lastError"]).isNull()
    }

    @Test
    fun toMap_allWireNamesAccountedFor() {
        Insta360ConnectionState.values().forEach { state ->
            val h = Insta360ConnectionHealth(bindingKey = "k", state = state)
            assertThat(h.toMap()["state"]).isEqualTo(state.wireName)
        }
    }

    @Test
    fun equals_distinctValuesAreDistinct() {
        val a = Insta360ConnectionHealth(bindingKey = "B1", reconnectAttempt = 1)
        val b = Insta360ConnectionHealth(bindingKey = "B1", reconnectAttempt = 2)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun equals_sameValuesAreEqual() {
        val a = Insta360ConnectionHealth(
            bindingKey = "B1",
            role = "ego",
            state = Insta360ConnectionState.BLE_READY,
            rssi = -60,
        )
        val b = Insta360ConnectionHealth(
            bindingKey = "B1",
            role = "ego",
            state = Insta360ConnectionState.BLE_READY,
            rssi = -60,
        )
        assertThat(a).isEqualTo(b)
    }
}
