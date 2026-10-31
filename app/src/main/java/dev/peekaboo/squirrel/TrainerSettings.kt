package dev.peekaboo.squirrel

import android.content.SharedPreferences

/** Everything the trainer can change. Persisted; nothing is locked. */
data class TrainerSettings(
    /** 0 = cartoon only, 1 = mixed, 2 = footage only. */
    var footage: Int = 1,
    var autoLevel: Boolean = true,
    /** Starting level 0..1 (0 = training). Auto-level moves it from here. */
    var level: Float = 0f,
    /** 0 = unlimited. */
    var sessionMin: Int = 5,
    /** 0 = no cooldown. */
    var cooldownMin: Int = 0,
    var treatSec: Float = 3.5f,
    var repopSec: Int = 8,
    var tapSkipsTreat: Boolean = true,
    var clipSounds: Boolean = true,
) {
    val wildChance get() = when (footage) { 0 -> 0f; 2 -> 1f; else -> 0.5f }

    fun toConfig(difficulty: Float = level) = GameConfig(
        sessionSeconds = if (sessionMin <= 0) Float.POSITIVE_INFINITY else sessionMin * 60f,
        startDifficulty = difficulty,
        wildChance = wildChance,
        adaptive = autoLevel,
        repopSeconds = repopSec.toFloat(),
        treatSeconds = treatSec,
        tapSkipsTreat = tapSkipsTreat,
        wildFirst = footage != 0,
    )

    fun save(p: SharedPreferences) {
        p.edit()
            .putInt("t_footage", footage).putBoolean("t_auto", autoLevel).putFloat("difficulty", level)
            .putInt("t_session", sessionMin).putInt("t_cooldown", cooldownMin).putFloat("t_treat", treatSec)
            .putInt("t_repop", repopSec).putBoolean("t_tapskip", tapSkipsTreat).putBoolean("t_clipsnd", clipSounds)
            .apply()
    }

    companion object {
        fun load(p: SharedPreferences) = TrainerSettings(
            footage = p.getInt("t_footage", 1),
            autoLevel = p.getBoolean("t_auto", true),
            level = p.getFloat("difficulty", 0f),
            sessionMin = p.getInt("t_session", 5),
            cooldownMin = p.getInt("t_cooldown", 0),
            treatSec = p.getFloat("t_treat", 3.5f),
            repopSec = p.getInt("t_repop", 8),
            tapSkipsTreat = p.getBoolean("t_tapskip", true),
            clipSounds = p.getBoolean("t_clipsnd", true),
        )
    }
}
