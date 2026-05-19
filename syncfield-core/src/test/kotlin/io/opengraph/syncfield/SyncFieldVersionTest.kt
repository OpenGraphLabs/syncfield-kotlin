package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncFieldVersionTest {

    @Test
    fun `current SDK version is the latest stable release`() {
        assertThat(SyncFieldVersion.current).isEqualTo("0.7.0")
        assertThat(SyncFieldVersion.current).doesNotContain("SNAPSHOT")
    }
}
