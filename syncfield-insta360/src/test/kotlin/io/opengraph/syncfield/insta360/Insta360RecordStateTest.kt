package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360RecordStateTest {

    @Test
    fun `started and stopped notifications are distinct`() {
        assertThat(Insta360RecordState.isStarted(0)).isTrue()
        assertThat(Insta360RecordState.isStarted(1)).isFalse()

        assertThat(Insta360RecordState.isStopped(1)).isTrue()
        assertThat(Insta360RecordState.isStopped(0)).isFalse()
        assertThat(Insta360RecordState.isStopped(2)).isFalse()
    }
}
