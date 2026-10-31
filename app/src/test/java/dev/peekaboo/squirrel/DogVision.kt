package dev.peekaboo.squirrel

import kotlin.math.exp
import kotlin.math.pow

/**
 * Kotlin port of tools/dogvision.py (test-only).
 * Display primaries = Gaussians scaled so RGB white is D65 for a human (Wyman–Sloan–Shirley
 * 2013 CIE 1931 fit); dog cones = Govardovskii 2000 A1 template; von Kries-normalised to
 * display white. Dog brightness ≈ L-cone catch; chroma BY = (S−L)/(S+L).
 */
class DogVision(sMax: Double, lMax: Double, r: Pair<Double, Double>, g: Pair<Double, Double>, b: Pair<Double, Double>) {
    data class Dog(val s: Double, val l: Double) { val by get() = if (s + l > 0) (s - l) / (s + l) else 0.0 }

    private val ms = DoubleArray(3)
    private val ml = DoubleArray(3)

    init {
        val wl = DoubleArray(401) { 380.0 + it }
        val prims = listOf(r, g, b).map { (mu, fwhm) -> val sd = fwhm / 2.3548; wl.map { exp(-0.5 * ((it - mu) / sd).pow(2)) } }
        val xyz = prims.map { sp -> DoubleArray(3) { c -> wl.indices.sumOf { sp[it] * cmf(wl[it])[c] } } }
        val k = solve(Array(3) { c -> DoubleArray(3) { p -> xyz[p][c] } }, doubleArrayOf(0.95047, 1.0, 1.08883))
        val sC = wl.map { gov(sMax, it) }; val lC = wl.map { gov(lMax, it) }
        for (p in 0..2) {
            ms[p] = k[p] * wl.indices.sumOf { prims[p][it] * sC[it] }
            ml[p] = k[p] * wl.indices.sumOf { prims[p][it] * lC[it] }
        }
        val sw = ms.sum(); val lw = ml.sum()
        for (p in 0..2) { ms[p] /= sw; ml[p] /= lw }
    }

    fun of(argb: Int): Dog {
        val c = doubleArrayOf(lin(argb shr 16 and 255), lin(argb shr 8 and 255), lin(argb and 255))
        return Dog((0..2).sumOf { ms[it] * c[it] }, (0..2).sumOf { ml[it] * c[it] })
    }

    companion object {
        /** Worst-case grid: S 429/435, red 605/625, green 530w70/545w100, blue 445/455, ±dim-room rod shift. */
        val GRID: List<DogVision> by lazy {
            val out = ArrayList<DogVision>()
            for (s in listOf(429.0, 435.0)) for (rm in listOf(605.0, 625.0))
                for (gm in listOf(530.0 to 70.0, 545.0 to 100.0)) for (bm in listOf(445.0, 455.0))
                    for (rod in listOf(false, true))
                        out += DogVision(s, if (rod) 530.0 else 555.0, rm to 60.0, gm, bm to 20.0)
            out
        }
        val NOMINAL by lazy { DogVision(430.0, 555.0, 615.0 to 60.0, 540.0 to 80.0, 450.0 to 20.0) }

        private fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

        private fun gov(lmax: Double, l: Double): Double {
            val x = lmax / l
            val a = 0.8795 + 0.0459 * exp(-(lmax - 300).pow(2) / 11940)
            val alpha = 1 / (exp(69.7 * (a - x)) + exp(28 * (0.922 - x)) + exp(-14.9 * (1.104 - x)) + 0.674)
            val lb = 189 + 0.315 * lmax; val bb = -40.5 + 0.195 * lmax
            return alpha + 0.26 * exp(-((l - lb) / bb).pow(2))
        }

        private fun g(x: Double, mu: Double, s1: Double, s2: Double): Double {
            val t = (x - mu) / (if (x < mu) s1 else s2); return exp(-0.5 * t * t)
        }

        private fun cmf(l: Double) = doubleArrayOf(
            1.056 * g(l, 599.8, 37.9, 31.0) + 0.362 * g(l, 442.0, 16.0, 26.7) - 0.065 * g(l, 501.1, 20.4, 26.2),
            0.821 * g(l, 568.8, 46.9, 40.5) + 0.286 * g(l, 530.9, 16.3, 31.1),
            1.217 * g(l, 437.0, 11.8, 36.0) + 0.681 * g(l, 459.0, 26.0, 13.8),
        )

        private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
            val a = Array(3) { i -> a0[i].copyOf() + b0[i] }
            for (i in 0..2) {
                val piv = (i..2).maxBy { kotlin.math.abs(a[it][i]) }; val t = a[i]; a[i] = a[piv]; a[piv] = t
                for (r in 0..2) if (r != i) { val f = a[r][i] / a[i][i]; for (j in 0..3) a[r][j] -= f * a[i][j] }
            }
            return DoubleArray(3) { a[it][3] / a[it][it] }
        }
    }
}
