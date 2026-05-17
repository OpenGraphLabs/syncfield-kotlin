package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import org.junit.After
import org.junit.Before
import org.junit.Test

class Insta360CoordinatorConfigTest {

    @Before
    fun setUp() {
        Insta360CoordinatorConfig.resetForTest()
    }

    @After
    fun tearDown() {
        Insta360CoordinatorConfig.resetForTest()
    }

    @Test
    fun defaults_matchSwiftParity() {
        val c = Insta360CoordinatorConfig
        assertThat(c.heartbeatIntervalMs).isEqualTo(2_000L)
        assertThat(c.radioGateSlowHeartbeatIntervalMs).isEqualTo(8_000L)
        assertThat(c.wakeStallThresholdSeconds).isEqualTo(12.0)
        assertThat(c.persistentScanWindowCount).isEqualTo(3)
        assertThat(c.persistentScanWindowSeconds).isEqualTo(6.0)
        assertThat(c.persistentLastSeenThresholdSeconds).isEqualTo(90.0)
        assertThat(c.healthSnapshotIntervalMs).isEqualTo(5_000L)
        assertThat(c.autoReconnectEnabled).isTrue()
        assertThat(c.radioGateEnabled).isTrue()
        assertThat(c.backgroundBLEEnabled).isTrue()
        assertThat(c.wakeUserPromptEnabled).isTrue()
        assertThat(c.diagnosticsEnabled).isFalse()
        assertThat(c.scenarioMode).isFalse()
        assertThat(c.bleStrategyDuringWifi).isEqualTo(Insta360CoordinatorConfig.BleStrategyDuringWifi.KEEP_ALIVE)
        assertThat(c.minLogLevel).isEqualTo(InstaLogLevel.INFO)
    }

    @Test
    fun enableScenarioMode_setsDebugFloorEverywhere() {
        Insta360CoordinatorConfig.enableScenarioMode()
        assertThat(Insta360CoordinatorConfig.scenarioMode).isTrue()
        assertThat(Insta360CoordinatorConfig.minLogLevel).isEqualTo(InstaLogLevel.DEBUG)
        assertThat(InstaLog.minLevel).isEqualTo(InstaLogLevel.DEBUG)
    }

    @Test
    fun apply_bindsMinLogLevelToInstaLog() {
        Insta360CoordinatorConfig.minLogLevel = InstaLogLevel.WARN
        // Before apply(), InstaLog still reflects last value (default after reset)
        assertThat(InstaLog.minLevel).isEqualTo(InstaLogLevel.INFO)
        Insta360CoordinatorConfig.apply()
        assertThat(InstaLog.minLevel).isEqualTo(InstaLogLevel.WARN)
    }

    @Test
    fun mutability_allKnobsSettable() {
        Insta360CoordinatorConfig.heartbeatIntervalMs = 500L
        Insta360CoordinatorConfig.radioGateSlowHeartbeatIntervalMs = 16_000L
        Insta360CoordinatorConfig.autoReconnectEnabled = false
        Insta360CoordinatorConfig.bleStrategyDuringWifi = Insta360CoordinatorConfig.BleStrategyDuringWifi.DISCONNECT
        Insta360CoordinatorConfig.diagnosticsEnabled = true

        assertThat(Insta360CoordinatorConfig.heartbeatIntervalMs).isEqualTo(500L)
        assertThat(Insta360CoordinatorConfig.radioGateSlowHeartbeatIntervalMs).isEqualTo(16_000L)
        assertThat(Insta360CoordinatorConfig.autoReconnectEnabled).isFalse()
        assertThat(Insta360CoordinatorConfig.bleStrategyDuringWifi)
            .isEqualTo(Insta360CoordinatorConfig.BleStrategyDuringWifi.DISCONNECT)
        assertThat(Insta360CoordinatorConfig.diagnosticsEnabled).isTrue()
    }

    @Test
    fun bleStrategy_wireNameRoundTrip() {
        assertThat(Insta360CoordinatorConfig.BleStrategyDuringWifi.fromWireName("keepAlive"))
            .isEqualTo(Insta360CoordinatorConfig.BleStrategyDuringWifi.KEEP_ALIVE)
        assertThat(Insta360CoordinatorConfig.BleStrategyDuringWifi.fromWireName("disconnect"))
            .isEqualTo(Insta360CoordinatorConfig.BleStrategyDuringWifi.DISCONNECT)
        assertThat(Insta360CoordinatorConfig.BleStrategyDuringWifi.fromWireName("bogus")).isNull()
    }
}
