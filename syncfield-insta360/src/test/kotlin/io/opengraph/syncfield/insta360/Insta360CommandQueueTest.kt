package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Insta360CommandQueueTest {

    @Test
    fun `same-device commands serialize`() = runTest {
        val queue = Insta360CommandQueue(sdkMutex = Mutex(), retryDelayMs = 0L)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            queue.runDeviceCommand("camera-a", sdkCritical = false) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstStarted.await()

        val second = async {
            queue.runDeviceCommand("camera-a", sdkCritical = false) {
                secondStarted.complete(Unit)
                "second"
            }
        }
        runCurrent()

        assertThat(secondStarted.isCompleted).isFalse()
        releaseFirst.complete(Unit)
        assertThat(first.await()).isEqualTo("first")
        assertThat(second.await()).isEqualTo("second")
    }

    @Test
    fun `non-sdk-critical commands on different devices can overlap`() = runTest {
        val queue = Insta360CommandQueue(sdkMutex = Mutex(), retryDelayMs = 0L)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            queue.runDeviceCommand("camera-a", sdkCritical = false) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val second = async {
            queue.runDeviceCommand("camera-b", sdkCritical = false) {
                secondStarted.complete(Unit)
            }
        }
        secondStarted.await()

        releaseFirst.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `sdk-critical commands serialize across devices`() = runTest {
        val queue = Insta360CommandQueue(sdkMutex = Mutex(), retryDelayMs = 0L)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            queue.runDeviceCommand("camera-a", sdkCritical = true) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val second = async {
            queue.runDeviceCommand("camera-b", sdkCritical = true) {
                secondStarted.complete(Unit)
            }
        }
        runCurrent()

        assertThat(secondStarted.isCompleted).isFalse()
        releaseFirst.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `transient command failures retry inside device lock`() = runTest {
        val queue = Insta360CommandQueue(sdkMutex = Mutex(), retryDelayMs = 0L)
        var attempts = 0

        val result = queue.runDeviceCommand("camera-a", retries = 2, sdkCritical = false) {
            attempts += 1
            if (attempts < 3) throw Insta360Error.CommandFailed("temporary")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(3)
    }
}
