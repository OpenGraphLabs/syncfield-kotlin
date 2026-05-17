package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class Insta360ReconnectPolicyTest {

    @Before
    fun setUp() {
        Insta360CoordinatorConfig.resetForTest()
    }

    @After
    fun tearDown() {
        Insta360CoordinatorConfig.resetForTest()
    }

    @Test
    fun defaultBackoffSchedule_mirrorsSwift() {
        val policy = Insta360ReconnectPolicy()
        assertThat(policy.backoffScheduleSeconds)
            .containsExactly(0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0, 60.0, 60.0, 60.0)
            .inOrder()
    }

    @Test
    fun backoffSeconds_attempt1IsFirstEntry() {
        val policy = Insta360ReconnectPolicy()
        assertThat(policy.backoffSeconds(attempt =1)).isEqualTo(0.5)
    }

    @Test
    fun backoffSeconds_attempt5IsFifthEntry() {
        val policy = Insta360ReconnectPolicy()
        assertThat(policy.backoffSeconds(attempt =5)).isEqualTo(8.0)
    }

    @Test
    fun backoffSeconds_beyondScheduleClampsToLast() {
        val policy = Insta360ReconnectPolicy()
        assertThat(policy.backoffSeconds(attempt =100)).isEqualTo(60.0)
        assertThat(policy.backoffSeconds(attempt =11)).isEqualTo(60.0)
    }

    @Test
    fun backoffSeconds_zeroOrNegativeClampsToFirst() {
        val policy = Insta360ReconnectPolicy()
        assertThat(policy.backoffSeconds(attempt =0)).isEqualTo(0.5)
        assertThat(policy.backoffSeconds(attempt =-10)).isEqualTo(0.5)
    }

    @Test
    fun emptySchedule_throwsAtConstruction() {
        try {
            Insta360ReconnectPolicy(backoffScheduleSeconds = emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("backoff schedule must not be empty")
        }
    }

    @Test
    fun customSchedule_usedAsIs() {
        val policy = Insta360ReconnectPolicy(backoffScheduleSeconds = listOf(0.1, 0.2, 0.3))
        assertThat(policy.backoffSeconds(attempt =1)).isEqualTo(0.1)
        assertThat(policy.backoffSeconds(attempt =3)).isEqualTo(0.3)
        assertThat(policy.backoffSeconds(attempt =10)).isEqualTo(0.3)
    }

    // --- shouldClassifyAsLost ------------------------------------------------

    @Test
    fun classifyAsLost_emptyAdvertWindowsBeyondThreshold() {
        // Default threshold is 3
        assertThat(
            Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = 3,
                lastErrorDescription = null,
                msSinceLastAdvertisement = null,
            )
        ).isTrue()
        assertThat(
            Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = 2,
                lastErrorDescription = null,
                msSinceLastAdvertisement = null,
            )
        ).isFalse()
    }

    @Test
    fun classifyAsLost_hardErrorString() {
        val cases = listOf(
            "Peripheral powered off",
            "Out of range",
            "the specified device has disconnected",
            "Remote device terminated connection",
            "GATT_ERROR 133",
        )
        cases.forEach { err ->
            assertThat(
                Insta360ReconnectPolicy.shouldClassifyAsLost(
                    consecutiveScanWindowsWithoutAdvert = 0,
                    lastErrorDescription = err,
                    msSinceLastAdvertisement = null,
                )
            ).isTrue()
        }
    }

    @Test
    fun classifyAsLost_softErrorStringNotPersistent() {
        assertThat(
            Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = 0,
                lastErrorDescription = "transient hiccup",
                msSinceLastAdvertisement = null,
            )
        ).isFalse()
    }

    @Test
    fun classifyAsLost_msSinceLastAdvertBeyondThreshold() {
        // Default threshold 90s = 90_000 ms
        assertThat(
            Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = 0,
                lastErrorDescription = null,
                msSinceLastAdvertisement = 91_000L,
            )
        ).isTrue()
        assertThat(
            Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = 0,
                lastErrorDescription = null,
                msSinceLastAdvertisement = 89_000L,
            )
        ).isFalse()
    }

    @Test
    fun classifyAsLost_allSignalsNegativeReturnsFalse() {
        assertThat(
            Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = 0,
                lastErrorDescription = null,
                msSinceLastAdvertisement = null,
            )
        ).isFalse()
    }
}
