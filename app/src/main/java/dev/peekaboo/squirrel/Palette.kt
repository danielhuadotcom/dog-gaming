package dev.peekaboo.squirrel

/**
 * Dogs are dichromats (S cone ~430 nm, L cone ~555 nm): their only chromatic axis is
 * blue–yellow, and red/orange/green differ to them only in brightness. Colours here were
 * chosen with a dog-cone model of an LCD (tools/dogvision.py, docs/DOG_VISION.md) to
 * maximise worst-case contrast across plausible panel spectra:
 *  - sky = blue subpixel only (any red/green adds dog-brightness and dilutes the blue)
 *  - critter = yellow with a touch less green than #FFFF00 (broad LCD green leaks into S)
 *  - bushes = neutral grey at the geometric mean brightness of sky and critter
 * PaletteTest re-runs the model over the same parameter grid and enforces the targets.
 */
object Palette {
    const val SKY_TOP = 0xFF000040.toInt()
    const val SKY_BOTTOM = 0xFF0000A5.toInt()
    const val SKY_FLASH = 0xFF1A2CFF.toInt()
    const val GROUND = 0xFF05082A.toInt()
    const val BUSH = 0xFF7C7C7C.toInt()
    const val BUSH_SHADE = 0xFF5A5A5A.toInt()
    const val CRITTER = 0xFFFFE800.toInt()
    const val CRITTER_TAIL = 0xFFFFC800.toInt()
    const val CRITTER_DETAIL = 0xFF05082A.toInt()
    const val SPARK = 0xFFFFF060.toInt()
    const val HUD = 0xFF8A8A8A.toInt()
    const val REST_BG = 0xFF060B22.toInt()
    const val MOON = 0xFF9A9A9A.toInt()

    val ALL = intArrayOf(
        SKY_TOP, SKY_BOTTOM, SKY_FLASH, GROUND, BUSH, BUSH_SHADE, CRITTER, CRITTER_TAIL,
        CRITTER_DETAIL, SPARK, HUD, REST_BG, MOON,
    )
}
