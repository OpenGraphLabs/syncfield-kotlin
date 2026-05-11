package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class Insta360SupportInstrumentedTest {

    @Test
    fun supportProbeIsSafeOnDevice() {
        assertThat(Insta360Support.available).isAnyOf(true, false)
    }
}
