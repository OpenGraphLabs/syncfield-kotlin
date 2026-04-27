package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionClockTest {

    @Test
    fun `nowMonotonicNs is non-decreasing across rapid calls`() {
        val clock = SessionClock()
        val first = clock.nowMonotonicNs()
        Thread.sleep(1)
        val second = clock.nowMonotonicNs()
        assertThat(second).isAtLeast(first)
    }

    @Test
    fun `anchor populates all required fields`() {
        val clock = SessionClock()
        val sp = clock.anchor(hostId = "host-abc")
        assertThat(sp.hostId).isEqualTo("host-abc")
        assertThat(sp.sdkVersion).isEqualTo(SyncFieldVersion.current)
        assertThat(sp.monotonicNs).isGreaterThan(0L)
        assertThat(sp.wallClockNs).isGreaterThan(0L)
        assertThat(sp.isoDatetime).matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*Z").pattern)
        // Chirp fields are absent on a bare anchor.
        assertThat(sp.chirpStartNs).isNull()
        assertThat(sp.chirpStopNs).isNull()
    }

    @Test
    fun `wall clock is roughly current`() {
        val clock = SessionClock()
        val before = System.currentTimeMillis() * 1_000_000L
        val sp = clock.anchor("h")
        val after = System.currentTimeMillis() * 1_000_000L + 1_000_000_000L
        assertThat(sp.wallClockNs).isAtLeast(before - 1_000_000_000L)
        assertThat(sp.wallClockNs).isAtMost(after)
    }
}
