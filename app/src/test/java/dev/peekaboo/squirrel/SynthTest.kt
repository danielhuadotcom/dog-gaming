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
        val f1 = dominant(x, (0.1 * sr).toInt(), (0.4 * sr).toInt(), 600, 1500, 10)
        val f3 = dominant(x, (1.1 * sr).toInt(), (1.4 * sr).toInt(), 600, 1500, 10)
        assertTrue("$f1", f1 in 1310..1330); assertTrue("$f3", f3 in 775..795)
    }

    @Test fun worksAtCommonSampleRates() {
        for (rate in intArrayOf(44100, 48000)) Synth.all(rate).values.forEach { assertTrue(it.isNotEmpty()) }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }

    /** Fraction of energy between lo and hi Hz (Hann-windowed 2048-pt frames, 50% overlap). */
    private fun bandFraction(x: FloatArray, lo: Double, hi: Double): Double {
        val n = 2048
        var inBand = 0.0; var total = 0.0
        var start = 0
        while (start + n <= x.size) {
            val re = DoubleArray(n) { x[start + it] * (0.5 - 0.5 * cos(2 * PI * it / n)) }
            val im = DoubleArray(n)
            fft(re, im)
            for (k in 1 until n / 2) {
                val p = re[k] * re[k] + im[k] * im[k]
                total += p
                if (k * sr.toDouble() / n in lo..hi) inBand += p
            }
            start += n / 2
        }
        return inBand / total
    }

    @Test fun everythingFitsATabletSpeaker() {
        // Budget micro-speakers reproduce roughly 0.4–16 kHz; keep >=85% of each sound's energy in 0.5–12 kHz.
        for ((s, d) in Synth.all(sr)) {
            val frac = bandFraction(d, 500.0, 12000.0)
            assertTrue("$s only ${"%.2f".format(frac)} of energy in speaker band", frac >= 0.85)
        }
    }

    @Test fun preyCuesSitInTheDogsSensitiveRange() {
        // Dog hearing is most sensitive ~2–8 kHz (Heffner 1983); real squirrel/mouse calls and
        // leaf-litter rustles put most energy ~1.5–10 kHz. Prey cues must too.
        for (s in listOf(Sound.SQUEAK, Sound.CHIRP, Sound.RUSTLE, Sound.SCURRY)) {
            val frac = bandFraction(Synth.all(sr)[s]!!, 1500.0, 10000.0)
            assertTrue("$s only ${"%.2f".format(frac)} in 1.5–10 kHz", frac >= 0.6)
        }
    }
}
