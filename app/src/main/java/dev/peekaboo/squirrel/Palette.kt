package dev.peekaboo.squirrel

/**
 * Dogs are dichromats: they see blue–violet and yellow well, while red and green
 * collapse into similar brownish greys. Everything on screen is therefore built from
 * blues, yellows and neutral greys only. PaletteTest enforces this.
 */
object Palette {
    const val SKY_TOP = 0xFF0B1A4A.toInt()
    const val SKY_BOTTOM = 0xFF1F3C96.toInt()
    const val SKY_FLASH = 0xFF3A62D8.toInt()
    const val GROUND = 0xFF0A1433.toInt()
    const val BUSH = 0xFF7C7C7C.toInt()
    const val BUSH_SHADE = 0xFF5A5A5A.toInt()
    const val CRITTER = 0xFFFFD60A.toInt()
    const val CRITTER_TAIL = 0xFFFFC300.toInt()
    const val CRITTER_DETAIL = 0xFF0A1433.toInt()
    const val SPARK = 0xFFFFE866.toInt()
    const val HUD = 0xFF8C9BC7.toInt()
    const val REST_BG = 0xFF060B22.toInt()
    const val MOON = 0xFF9A9A9A.toInt()

    val ALL = intArrayOf(
        SKY_TOP, SKY_BOTTOM, SKY_FLASH, GROUND, BUSH, BUSH_SHADE, CRITTER, CRITTER_TAIL,
        CRITTER_DETAIL, SPARK, HUD, REST_BG, MOON,
    )
}
