package dev.peekaboo.squirrel

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Checks the palette against a dichromatic (dog-like, deuteranope) vision model. */
class PaletteTest {
    private fun lin(c: Int) = ((c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) })
    private fun rgb(c: Int) = doubleArrayOf(lin(c shr 16 and 255), lin(c shr 8 and 255), lin(c and 255))

    /** Viénot/Brettel/Mollon 1999 deuteranope simulation in linear RGB. */
    private fun dog(c: Int): DoubleArray {
        val (r, g, b) = rgb(c).let { Triple(it[0], it[1], it[2]) }
        return doubleArrayOf(
            0.29275 * r + 0.70725 * g,
            0.29275 * r + 0.70725 * g,
            -0.02234 * r + 0.02234 * g + b,
        )
    }

    private fun lum(v: DoubleArray) = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
    private fun contrast(a: Int, b: Int): Double {
        val la = lum(dog(a)) + 0.05; val lb = lum(dog(b)) + 0.05
        return max(la, lb) / min(la, lb)
    }

    private fun hsv(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    @Test fun onlyBluesYellowsAndGreys() {
        for (c in Palette.ALL) {
            val (h, s, _) = hsv(c).let { Triple(it[0], it[1], it[2]) }
            val ok = s < 0.08f || h in 200f..250f || h in 40f..60f
            assertTrue("colour #${Integer.toHexString(c)} hue=$h sat=$s is not dog-friendly", ok)
        }
    }

    @Test fun critterPopsAgainstSkyInDogVision() {
        assertTrue(contrast(Palette.CRITTER, Palette.SKY_TOP) > 7.0)
        assertTrue(contrast(Palette.CRITTER, Palette.SKY_BOTTOM) > 4.5)
        assertTrue(contrast(Palette.CRITTER, Palette.SKY_FLASH) > 3.0)
    }

    @Test fun critterDistinctFromBushInDogVision() {
        assertTrue(contrast(Palette.CRITTER, Palette.BUSH) > 2.0)
        // Yellow vs grey also differ in the dog's blue–yellow opponent channel.
        val c = dog(Palette.CRITTER); val b = dog(Palette.BUSH)
        val byC = c[2] - c[0]; val byB = b[2] - b[0]
        assertTrue(abs(byC - byB) > 0.3)
    }

    @Test fun bushesVisibleAgainstSky() {
        assertTrue(contrast(Palette.BUSH, Palette.SKY_BOTTOM) > 1.5)
    }
}
