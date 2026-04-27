package io.opengraph.syncfield

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HealthBusTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `subscribers receive published events in order`() = runTest {
        val bus = HealthBus()
        bus.events.test {
            bus.publish(HealthEvent.StreamConnected("imu"))
            bus.publish(HealthEvent.SamplesDropped("imu", 3))
            bus.publish(HealthEvent.StreamDisconnected("imu", "normal"))

            val first = awaitItem()
            assertThat(first).isInstanceOf(HealthEvent.StreamConnected::class.java)
            assertThat(first.streamId).isEqualTo("imu")

            val second = awaitItem() as HealthEvent.SamplesDropped
            assertThat(second.count).isEqualTo(3)

            val third = awaitItem() as HealthEvent.StreamDisconnected
            assertThat(third.reason).isEqualTo("normal")

            cancelAndIgnoreRemainingEvents()
        }
    }
}
