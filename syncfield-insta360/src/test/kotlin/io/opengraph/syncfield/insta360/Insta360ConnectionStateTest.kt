package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360ConnectionStateTest {

    @Test
    fun acceptsCommands_truthTable() {
        // States that accept BLE commands
        assertThat(Insta360ConnectionState.BLE_READY.acceptsCommands).isTrue()
        assertThat(Insta360ConnectionState.BLE_DEGRADED.acceptsCommands).isTrue()
        assertThat(Insta360ConnectionState.WIFI_BOUND.acceptsCommands).isTrue()

        // States that do not accept commands
        assertThat(Insta360ConnectionState.IDLE.acceptsCommands).isFalse()
        assertThat(Insta360ConnectionState.SEARCHING.acceptsCommands).isFalse()
        assertThat(Insta360ConnectionState.CONNECTING.acceptsCommands).isFalse()
        assertThat(Insta360ConnectionState.BLE_SUSPENDED.acceptsCommands).isFalse()
        assertThat(Insta360ConnectionState.RECONNECTING.acceptsCommands).isFalse()
        assertThat(Insta360ConnectionState.LOST.acceptsCommands).isFalse()
        assertThat(Insta360ConnectionState.GIVE_UP.acceptsCommands).isFalse()
    }

    @Test
    fun isTerminal_onlyLostAndGiveUp() {
        assertThat(Insta360ConnectionState.LOST.isTerminal).isTrue()
        assertThat(Insta360ConnectionState.GIVE_UP.isTerminal).isTrue()

        Insta360ConnectionState.values()
            .filter { it != Insta360ConnectionState.LOST && it != Insta360ConnectionState.GIVE_UP }
            .forEach { state ->
                assertThat(state.isTerminal).isFalse()
            }
    }

    @Test
    fun wireName_isStableAndUnique() {
        val names = Insta360ConnectionState.values().map { it.wireName }
        assertThat(names).containsNoDuplicates()
        // Spot-check critical mappings (RN consumers parse these)
        assertThat(Insta360ConnectionState.BLE_READY.wireName).isEqualTo("bleReady")
        assertThat(Insta360ConnectionState.BLE_SUSPENDED.wireName).isEqualTo("bleSuspended")
        assertThat(Insta360ConnectionState.WIFI_BOUND.wireName).isEqualTo("wifiBound")
        assertThat(Insta360ConnectionState.GIVE_UP.wireName).isEqualTo("giveUp")
    }

    @Test
    fun fromWireName_roundTrip() {
        Insta360ConnectionState.values().forEach { state ->
            assertThat(Insta360ConnectionState.fromWireName(state.wireName)).isEqualTo(state)
        }
    }

    @Test
    fun fromWireName_unknownReturnsNull() {
        assertThat(Insta360ConnectionState.fromWireName("nope")).isNull()
        assertThat(Insta360ConnectionState.fromWireName("")).isNull()
    }
}
