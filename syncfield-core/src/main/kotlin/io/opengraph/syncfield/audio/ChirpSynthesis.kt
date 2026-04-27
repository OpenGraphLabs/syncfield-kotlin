package io.opengraph.syncfield.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-function renderer for a linear FM sweep with a cosine envelope.
 *
 * Output matches the Swift SDK's `ChirpSynthesis.render` byte-for-byte
 * given the same `sampleRate`, which in turn matches the Python
 * reference at `syncfield/tone.py:71`.
 */
object ChirpSynthesis {

    fun render(spec: ChirpSpec, sampleRate: Double): FloatArray {
        val durationS = spec.durationMs / 1000.0
        val n = (durationS * sampleRate).toInt()
        if (n <= 0) return FloatArray(0)

        val f0 = spec.fromHz
        val f1 = spec.toHz
        val k = (f1 - f0) / durationS                  // sweep rate Hz/s
        val envS = spec.envelopeMs / 1000.0
        val envN = maxOf(1, (envS * sampleRate).toInt())
        val amp = spec.amplitude.toFloat()

        val out = FloatArray(n)
        val twoPi = 2.0 * PI
        for (i in 0 until n) {
            val t = i / sampleRate
            // Linear FM: phase(t) = 2π(f0·t + 0.5·k·t²)
            val phase = twoPi * (f0 * t + 0.5 * k * t * t)
            var sample = (sin(phase) * amp).toFloat()

            // Cosine attack + release envelope
            if (i < envN) {
                val a = 0.5 * (1.0 - cos(PI * i / envN))
                sample *= a.toFloat()
            } else if (i >= n - envN) {
                val tail = (n - 1 - i).toDouble() / envN
                val a = 0.5 * (1.0 - cos(PI * tail))
                sample *= a.toFloat()
            }
            out[i] = sample
        }
        return out
    }
}
