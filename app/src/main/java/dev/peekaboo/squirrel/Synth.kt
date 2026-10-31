package dev.peekaboo.squirrel

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally generated, dog-interesting sounds. No asset files: everything is
 * synthesized at the device's native sample rate on startup (a few ms of work).
 *
 * Design notes:
 *  - Squeaky-toy squeaks and short high chirps sit in 1.5–6 kHz where dog hearing is
 *    very sensitive, and tablet speakers reproduce well.
 *  - Rustling is band-limited crackly noise (prey in leaves).
 *  - Deliberately no doorbells, knocks or barks.
 */
object Synth {
    private const val TWO_PI = (2.0 * PI)

    fun all(sr: Int): Map<Sound, FloatArray> = mapOf(
        Sound.SQUEAK to squeak(sr),
        Sound.CHIRP to chirp(sr),
        Sound.RUSTLE to rustle(sr),
        Sound.SCURRY to scurry(sr),
        Sound.CELEBRATE to celebrate(sr),
        Sound.ALL_DONE to allDone(sr),
    )

    /** Rubber-toy squeak: a fast rising "squee" with reedy harmonics, then a falling "ak". */
    fun squeak(sr: Int): FloatArray {
        val out = FloatArray((sr * 0.36).toInt())
        var phase = 0.0
        fun seg(start: Double, dur: Double, f0: Double, f1: Double, amp: Float) {
            val s0 = (start * sr).toInt()
            val n = (dur * sr).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = f0 * exp(ln(f1 / f0) * t) + 70.0 * sin(TWO_PI * 32.0 * i / sr)
                phase += TWO_PI * f / sr
                val reed = sin(phase) + 0.5 * sin(2 * phase) + 0.2 * sin(3 * phase)
                val env = min(1.0, t * 12.0) * min(1.0, (1 - t) * 8.0)
                out[s0 + i] += (amp * env * reed / 1.7).toFloat()
            }
        }
        seg(0.0, 0.19, 1700.0, 4200.0, 0.85f)
        seg(0.23, 0.12, 3800.0, 2600.0, 0.75f)
        return out
    }

    /** Three quick high chirps — "something's here!" */
    fun chirp(sr: Int): FloatArray {
        val out = FloatArray((sr * 0.32).toInt())
        for (k in 0 until 3) {
            val s0 = (k * 0.1 * sr).toInt()
            val n = (0.055 * sr).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = 5200.0 - 1400.0 * t
                phase += TWO_PI * f / sr
                val env = sin(PI * t)
                out[s0 + i] += (0.6 * env * (sin(phase) + 0.25 * sin(2 * phase)) / 1.25).toFloat()
            }
        }
        return out
    }

    /**
     * Leaves rustling: crackly noise band-passed to ~0.8–10 kHz with most energy at 3–6 kHz.
     * Matches measured leaf-litter recordings (1–11.5 kHz), the dog's most sensitive region
     * (~4–8 kHz, Heffner 1983) and what a tablet micro-speaker can actually reproduce.
     */
    fun rustle(sr: Int): FloatArray {
        val rng = Random(7)
        val n = (sr * 0.8).toInt()
        val out = FloatArray(n)
        val hp = Biquad.highpass(sr, 800.0, 0.7)
        val bp = Biquad.bandpass(sr, 4500.0, 0.6)
        val lp = Biquad.lowpass(sr, 10000.0, 0.7)
        var grain = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            if (rng.nextFloat() < 400f / sr) grain = 0.6 + rng.nextDouble() * 0.4
            grain *= 0.9993
            val x = rng.nextDouble() * 2 - 1
            val y = lp.run(0.6 * bp.run(x) + 0.4 * hp.run(x))
            val env = min(1.0, t * 10) * min(1.0, (1 - t) * 4)
            out[i] = (env * (0.25 + grain) * y).toFloat()
        }
        return normalize(out, 0.6f)
    }

    private fun normalize(x: FloatArray, peak: Float): FloatArray {
        val m = x.maxOf { kotlin.math.abs(it) }
        if (m > 0f) for (i in x.indices) x[i] *= peak / m
        return x
    }

    /**
     * Tiny claws pattering: a run of short clicks band-passed to ~2–8 kHz with a little
     * ~1 kHz body (measured chipmunk/leaf contact sounds sit at ~0.6–8 kHz).
     */
    fun scurry(sr: Int): FloatArray {
        val rng = Random(11)
        val out = FloatArray((sr * 0.3).toInt())
        val tick = (0.008 * sr).toInt()
        for (k in 0 until 8) {
            val s0 = (k * 0.034 * sr).toInt()
            val bp = Biquad.bandpass(sr, 4000.0 + rng.nextDouble() * 1500.0, 0.9)
            val body = Biquad.bandpass(sr, 1000.0, 1.2)
            for (i in 0 until tick + tick / 2) { // let the filters ring briefly past the click
                val env = if (i < tick) 1.0 - i.toDouble() / tick else 0.0
                val x = (rng.nextDouble() * 2 - 1) * env
                val y = bp.run(x) + 0.35 * body.run(x)
                if (s0 + i < out.size) out[s0 + i] += y.toFloat()
            }
        }
        return normalize(out, 0.5f)
    }

    /** Bright rising arpeggio — the "you did it!" sound. */
    fun celebrate(sr: Int): FloatArray {
        val notes = doubleArrayOf(1047.0, 1319.0, 1568.0, 2093.0)
        val noteDur = 0.09
        val out = FloatArray((sr * (0.38 + notes.size * noteDur + 0.25)).toInt())
        val start = 0.38 // let the squeak lead
        notes.forEachIndexed { k, f ->
            val s0 = ((start + k * noteDur) * sr).toInt()
            val n = ((noteDur + 0.2) * sr).toInt()
            for (i in 0 until n) {
                if (s0 + i >= out.size) break
                val t = i.toDouble() / sr
                val env = min(1.0, t * 200) * exp(-t * 14)
                val v = sin(TWO_PI * f * t) + 0.3 * sin(TWO_PI * 2 * f * t)
                out[s0 + i] += (0.42 * env * v).toFloat()
            }
        }
        return out
    }

    /** Calm, low, descending tones: session over, time to rest. */
    fun allDone(sr: Int): FloatArray {
        val notes = doubleArrayOf(1319.0, 1047.0, 784.0)
        val noteDur = 0.5
        val out = FloatArray((sr * notes.size * noteDur + sr * 0.4).toInt())
        notes.forEachIndexed { k, f ->
            val s0 = (k * noteDur * sr).toInt()
            val n = ((noteDur + 0.35) * sr).toInt()
            for (i in 0 until n) {
                if (s0 + i >= out.size) break
                val t = i.toDouble() / sr
                val env = min(1.0, t * 30) * exp(-t * 3.5)
                out[s0 + i] += (0.35 * env * sin(TWO_PI * f * t)).toFloat()
            }
        }
        return out
    }

    /** RBJ audio-EQ-cookbook biquad (direct form I). */
    class Biquad private constructor(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun run(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            private fun make(sr: Int, f: Double, q: Double, kind: Int): Biquad {
                val w = TWO_PI * f / sr
                val alpha = sin(w) / (2 * q)
                val cw = kotlin.math.cos(w)
                val a0 = 1 + alpha
                val (b0, b1, b2) = when (kind) {
                    0 -> Triple((1 - cw) / 2, 1 - cw, (1 - cw) / 2)          // low-pass
                    1 -> Triple((1 + cw) / 2, -(1 + cw), (1 + cw) / 2)       // high-pass
                    else -> Triple(alpha, 0.0, -alpha)                       // band-pass (0 dB peak)
                }
                return Biquad(b0 / a0, b1 / a0, b2 / a0, -2 * cw / a0, (1 - alpha) / a0)
            }
            fun lowpass(sr: Int, f: Double, q: Double) = make(sr, f, q, 0)
            fun highpass(sr: Int, f: Double, q: Double) = make(sr, f, q, 1)
            fun bandpass(sr: Int, f: Double, q: Double) = make(sr, f, q, 2)
        }
    }
}
