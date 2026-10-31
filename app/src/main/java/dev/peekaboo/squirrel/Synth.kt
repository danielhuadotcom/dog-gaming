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
                val reed = sin(phase) + 0.35 * sin(2 * phase) + 0.15 * sin(3 * phase)
                val env = min(1.0, t * 12.0) * min(1.0, (1 - t) * 8.0)
                out[s0 + i] += (amp * env * reed / 1.5).toFloat()
            }
        }
        seg(0.0, 0.19, 1600.0, 3400.0, 0.85f)
        seg(0.23, 0.12, 3100.0, 2200.0, 0.75f)
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
                out[s0 + i] += (0.6 * env * sin(phase)).toFloat()
            }
        }
        return out
    }

    /** Leaves rustling: high-passed noise with random crackle grains. */
    fun rustle(sr: Int): FloatArray {
        val rng = Random(7)
        val n = (sr * 0.8).toInt()
        val out = FloatArray(n)
        // One-pole high-pass around ~1.5 kHz.
        val rc = 1.0 / (TWO_PI * 1500.0)
        val a = rc / (rc + 1.0 / sr)
        var prevX = 0.0
        var prevY = 0.0
        var grain = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            if (rng.nextFloat() < 40f / sr * 10f) grain = 0.6 + rng.nextDouble() * 0.4
            grain *= 0.9993
            val x = rng.nextDouble() * 2 - 1
            val y = a * (prevY + x - prevX)
            prevX = x; prevY = y
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

    /** Tiny claws pattering: a run of short filtered ticks. */
    fun scurry(sr: Int): FloatArray {
        val rng = Random(11)
        val out = FloatArray((sr * 0.3).toInt())
        val tick = (0.008 * sr).toInt()
        for (k in 0 until 8) {
            val s0 = (k * 0.034 * sr).toInt()
            var prevX = 0.0
            for (i in 0 until tick) {
                val x = rng.nextDouble() * 2 - 1
                val y = x - prevX // crude high-pass → "clicky"
                prevX = x
                val env = 1.0 - i.toDouble() / tick
                if (s0 + i < out.size) out[s0 + i] += (0.22 * env * y).toFloat()
            }
        }
        return out
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
        val notes = doubleArrayOf(659.0, 523.0, 392.0)
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
}
