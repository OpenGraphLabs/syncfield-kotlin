package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360WiFiReachabilityPolicyTest {

    @Test
    fun `probe backoff matches ios eight-attempt policy`() {
        assertThat(Insta360WiFiReachabilityPolicy.probeDelaysMs)
            .containsExactly(1_000L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L, 4_000L, 5_000L)
            .inOrder()
    }
}
