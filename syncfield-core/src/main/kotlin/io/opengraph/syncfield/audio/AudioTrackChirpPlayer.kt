package io.opengraph.syncfield.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android default chirp player. Synthesises the waveform with
 * [ChirpSynthesis], streams it through an [AudioTrack], and
 * captures the head position in nanoseconds via [AudioTrack.getTimestamp]
 * once playback has actually started.
 *
 * Behaviour mirrors [ChirpPlayer]:
 *
 * - On success: [ChirpEmission.source] = [ChirpSource.Hardware] and
 *   `hardwareNs` is the system-clock-domain nanosecond of the first
 *   played sample.
 * - If the AudioTrack can't be initialised or the timestamp probe
 *   fails, falls back to [ChirpSource.SoftwareFallback] with
 *   `hardwareNs = null` so the recording still finishes cleanly.
 */
class AudioTrackChirpPlayer(
    private val sampleRate: Int = 44_100,
) : ChirpPlayer {

    override val isSilent: Boolean = false

    /**
     * Background scope used to release the AudioTrack after a chirp has
     * finished playing. Held by the player so callers don't block on
     * the release window.
     */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun play(spec: ChirpSpec): ChirpEmission = withContext(Dispatchers.IO) {
        val samples = ChirpSynthesis.render(spec, sampleRate.toDouble())
        if (samples.isEmpty()) {
            return@withContext softwareFallback()
        }

        val pcm16 = samples.toPcm16()
        val sizeBytes = pcm16.size * Short.SIZE_BYTES
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).takeIf { it > 0 } ?: sizeBytes
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(sizeBytes, minBufferBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return@withContext softwareFallback()
        }

        runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
        val softwareStart = System.nanoTime()
        track.play()
        if (!track.writeAll(pcm16)) {
            track.release()
            return@withContext softwareFallback()
        }

        // Brief wait so the first sample has a chance to land in the
        // mixer before we sample the hardware clock.
        delay(30)

        val ts = AudioTimestamp()
        val hardwareNs: Long? = if (track.getTimestamp(ts)) ts.nanoTime else null
        val source = if (hardwareNs != null) ChirpSource.Hardware else ChirpSource.SoftwareFallback

        // Fire-and-forget release once the buffer has drained. 50 ms
        // tail margin covers AudioFlinger latency.
        scheduleRelease(track, (spec.durationMs + 50).toLong())

        ChirpEmission(softwareNs = softwareStart, hardwareNs = hardwareNs, source = source)
    }

    private fun softwareFallback(): ChirpEmission =
        ChirpEmission(
            softwareNs = System.nanoTime(),
            hardwareNs = null,
            source = ChirpSource.SoftwareFallback,
        )

    private fun scheduleRelease(track: AudioTrack, delayMs: Long) {
        releaseScope.launch {
            try {
                delay(delayMs)
                runCatching { track.stop() }
            } finally {
                track.release()
            }
        }
    }
}

private fun AudioTrack.writeAll(samples: ShortArray): Boolean {
    var offset = 0
    while (offset < samples.size) {
        val written = write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
        if (written <= 0) return false
        offset += written
    }
    return true
}

internal fun FloatArray.toPcm16(): ShortArray =
    ShortArray(size) { index ->
        val sample = this[index].coerceIn(-1f, 1f)
        val scaled = when {
            sample <= -1f -> Short.MIN_VALUE.toInt()
            sample >= 1f -> Short.MAX_VALUE.toInt()
            sample < 0f -> (sample * -Short.MIN_VALUE).roundToInt()
            else -> (sample * Short.MAX_VALUE).roundToInt()
        }
        scaled.toShort()
    }
