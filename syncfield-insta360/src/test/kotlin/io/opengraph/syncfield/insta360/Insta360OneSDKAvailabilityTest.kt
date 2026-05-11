package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360OneSDKAvailabilityTest {

    @Test
    fun `availability probe returns false when any required class is absent`() {
        assertThat(
            Insta360OneSDKAvailability.areClassesAvailable(
                listOf("java.lang.String", "io.opengraph.syncfield.insta360.DoesNotExist")
            )
        ).isFalse()
    }

    @Test
    fun `availability probe returns true when all required classes load`() {
        assertThat(
            Insta360OneSDKAvailability.areClassesAvailable(
                listOf("java.lang.String", "java.lang.Integer")
            )
        ).isTrue()
    }
}
