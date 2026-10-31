package dev.peekaboo.squirrel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Palette checks in a dog-cone model of an LCD (see DogVision / docs/DOG_VISION.md).
 * Every contrast target must hold in the WORST case over 32 display/eye variants,
 * because we don't know the exact spectra of the user's panel.
 */
class PaletteTest {
    private fun worst(f: (DogVision) -> Double) = DogVision.GRID.minOf(f)
    private fun lRatio(a: Int, b: Int) = worst { it.of(a).l / it.of(b).l }

    @Test fun modelSanity() {
        val m = DogVision.NOMINAL
        // Red and green both read as "yellow" to a dog, red much dimmer.
        assertTrue(m.of(0xFFFF0000.toInt()).by < -0.8 && m.of(0xFF00FF00.toInt()).by < -0.8)
        assertTrue(m.of(0xFFFF0000.toInt()).l < 0.5 * m.of(0xFF00FF00.toInt()).l)
        // Cyan sits near the dog's neutral point (~480 nm): close to grey.
        assertTrue(abs(m.of(0xFF00FFFF.toInt()).by) < 0.2)
        assertEquals(0.0, m.of(0xFFFFFFFF.toInt()).by, 1e-9)
    }

    @Test fun critterPopsAgainstSky() {
        assertTrue(lRatio(Palette.CRITTER, Palette.SKY_BOTTOM) >= 8.0)
        assertTrue(lRatio(Palette.CRITTER, Palette.SKY_TOP) >= 8.0)
        assertTrue(lRatio(Palette.CRITTER, Palette.SKY_FLASH) >= 3.0)
        assertTrue(worst { it.of(Palette.SKY_BOTTOM).by - it.of(Palette.CRITTER).by } >= 1.3)
    }

    @Test fun critterDistinctFromBush() {
        assertTrue(lRatio(Palette.CRITTER, Palette.BUSH) >= 2.5) // ~10x the dog brightness Weber fraction
        assertTrue(worst { it.of(Palette.BUSH).by - it.of(Palette.CRITTER).by } >= 0.6)
    }

    @Test fun bushesStandOutFromSky() {
        assertTrue(lRatio(Palette.BUSH, Palette.SKY_BOTTOM) >= 2.0)
    }

    @Test fun skyIsUnambiguouslyBlueToADog() {
        for (c in intArrayOf(Palette.SKY_TOP, Palette.SKY_BOTTOM, Palette.SKY_FLASH))
            assertTrue(worst { it.of(c).by } >= 0.6)
    }

    @Test fun everyColourIsBlueYellowOrNeutralNeverNearTheNeutralPoint() {
        // A chromatic colour drifting toward BY≈0 (e.g. teal/cyan mixes) looks washed-out grey
        // to a dog. Only true greys (R=G=B) may sit at BY≈0.
        for (c in Palette.ALL) {
            val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            val grey = r == g && g == b
            val by = DogVision.NOMINAL.of(c).by
            val ok = if (grey) abs(by) < 1e-6 else abs(by) >= 0.5
            assertTrue("#${Integer.toHexString(c)} BY=$by", ok)
        }
    }
}
