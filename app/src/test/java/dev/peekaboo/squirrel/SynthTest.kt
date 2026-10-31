package dev.peekaboo.squirrel

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SynthTest {
    private val sr = 48000

    /** Goertzel power at frequency f, normalised. */
    private fun power(x: FloatArray, from: Int, to: Int, f: Double): Double {
        val k = 2 * cos(2 * PI * f / sr)
        var s1 = 0.0; var s2 = 0.0
        for (i in from until to) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        return (s1 * s1 + s2 * s2 - k * s1 * s2) / (to - from).toDouble().pow(2)
    }

    private fun dominant(x: FloatArray, from: Int, to: Int, lo: Int, hi: Int, step: Int = 50) =
        (lo..hi step step).maxBy { power(x, from, to, it.toDouble()) }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test fun allSoundsAreNonSilentAndUnclipped() {
        for ((s, d) in Synth.all(sr)) {
            val peak = d.maxOf { abs(it) }
            assertTrue("$s peak $peak", peak in 0.15f..1.0f)
            assertTrue("$s rms", rms(d) > 0.01)
            assertTrue("$s has no NaN", d.none { it.isNaN() })
        }
    }

    @Test fun durationsAreShortAndPunchy() {
        val all = Synth.all(sr)
        for (s in listOf(Sound.SQUEAK, Sound.CHIRP, Sound.SCURRY)) assertTrue(all[s]!!.size < sr * 0.5)
        assertTrue(all[Sound.ALL_DONE]!!.size > sr)
    }

    @Test fun squeakRisesThenFalls() {
        val x = Synth.squeak(sr)
        val early = dominant(x, (0.01 * sr).toInt(), (0.05 * sr).toInt(), 1000, 4500)
        val late = dominant(x, (0.14 * sr).toInt(), (0.18 * sr).toInt(), 1000, 4500)
        val ak = dominant(x, (0.30 * sr).toInt(), (0.34 * sr).toInt(), 1000, 4500)
        assertTrue("rise $early -> $late", late > early + 800)
        assertTrue("fall $late -> $ak", ak < late)
        assertTrue(early in 1400..2400)
    }

    @Test fun chirpIsHighPitched() {
        val x = Synth.chirp(sr)
        val f = dominant(x, 0, (0.055 * sr).toInt(), 2000, 7000)
        assertTrue("chirp $f", f in 3800..5300)
        // three distinct bursts with silence between
        val gap = x.copyOfRange((0.06 * sr).toInt(), (0.095 * sr).toInt())
        assertTrue(gap.maxOf { abs(it) } < 1e-6f)
    }

    @Test fun rustleIsBroadbandHighFrequencyNoise() {
        val x = Synth.rustle(sr)
        val n = x.size
        val hi = power(x, 0, n, 6000.0); val lo = power(x, 0, n, 200.0)
        assertTrue("high-passed: hi=$hi lo=$lo", hi > lo * 3)
    }

    @Test fun allDoneDescends() {
        val x = Synth.allDone(sr)
        val f1 = dominant(x, (0.1 * sr).toInt(), (0.4 * sr).toInt(), 300, 800, 10)
        val f3 = dominant(x, (1.1 * sr).toInt(), (1.4 * sr).toInt(), 300, 800, 10)
        assertTrue(f1 in 650..670); assertTrue(f3 in 385..400)
    }

    @Test fun worksAtCommonSampleRates() {
        for (rate in intArrayOf(44100, 48000)) Synth.all(rate).values.forEach { assertTrue(it.isNotEmpty()) }
    }
}
