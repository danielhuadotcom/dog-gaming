package dev.peekaboo.squirrel

import org.junit.Assert.*
import org.junit.Test

class GameLogicTest {
    private class Rec : GameListener {
        val sounds = mutableListOf<Sound>()
        val events = mutableListOf<String>()
        override fun onSound(s: Sound) { sounds += s }
        override fun onEvent(msg: String) { events += msg }
    }

    private fun game(d: Float, session: Float = 240f, seed: Long = 1L, rec: Rec = Rec()) =
        GameLogic(seed, GameConfig(session, d), rec) to rec

    private fun GameLogic.run(seconds: Float, until: (GameLogic) -> Boolean = { false }): Boolean {
        var t = 0f
        while (t < seconds) { update(1f / 60f); t += 1f / 60f; if (until(this)) return true }
        return false
    }

    @Test fun trainingModeAnyPressWins() {
        val (g, rec) = game(0f)
        assertTrue(g.tuning.training)
        assertTrue(g.isCritterVisible)
        g.run(1f)
        assertTrue(g.press())
        assertEquals(Phase.CELEBRATE, g.phase)
        assertTrue(rec.sounds.containsAll(listOf(Sound.SQUEAK, Sound.CELEBRATE)))
        g.run(4f)
        assertTrue("training critter comes back after celebration", g.isCritterVisible)
    }

    @Test fun trainingCritterStaysVisibleIndefinitely() {
        val (g, _) = game(0f)
        g.run(60f)
        assertTrue(g.isCritterVisible)
        assertEquals(0, g.misses)
    }

    @Test fun pressWhileHiddenDoesNothingAndHasNoBuzzer() {
        val (g, rec) = game(0.5f)
        assertEquals(Phase.HIDDEN, g.phase)
        rec.sounds.clear()
        assertFalse(g.press())
        assertEquals(Phase.HIDDEN, g.phase)
        assertTrue("no penalty sound", rec.sounds.isEmpty())
    }

    @Test fun fullCycleRustleEmergeVisibleRetreat() {
        val (g, rec) = game(0.3f, seed = 3)
        val seen = mutableSetOf<Phase>()
        g.run(30f) { seen += it.phase; it.misses > 0 }
        assertTrue(seen.containsAll(listOf(Phase.HIDDEN, Phase.RUSTLE, Phase.EMERGE, Phase.VISIBLE, Phase.RETREAT)))
        assertTrue(Sound.RUSTLE in rec.sounds)
        assertTrue(Sound.CHIRP in rec.sounds)
        assertTrue(Sound.SCURRY in rec.sounds)
        // Rustle always precedes the chirp of an emergence.
        assertTrue(rec.sounds.indexOf(Sound.RUSTLE) < rec.sounds.indexOf(Sound.CHIRP))
    }

    @Test fun pressDuringVisibleScores() {
        val (g, _) = game(0.5f, seed = 5)
        assertTrue(g.run(30f) { it.phase == Phase.VISIBLE })
        assertTrue(g.press())
        assertEquals(1, g.hits)
    }

    @Test fun windowShrinksWithDifficulty() {
        assertTrue(Tuning.of(0.2f).window > Tuning.of(0.9f).window)
        assertTrue(Tuning.of(0.2f).spots < Tuning.of(1f).spots)
        assertEquals(0f, Tuning.of(0.3f).fakeChance, 0f)
        assertTrue(Tuning.of(1f).fakeChance > 0.2f)
    }

    @Test fun difficultyRisesWhenDogKeepsWinning() {
        val (g, _) = game(0f, session = 10_000f)
        repeat(12) { g.run(4f) { it.isCritterVisible && it.phase != Phase.CELEBRATE }; g.press() }
        assertTrue("got harder: ${g.difficulty}", g.difficulty > Tuning.TRAINING_BELOW)
    }

    @Test fun difficultyDropsAfterThreeMissesInARow() {
        val (g, _) = game(0.8f, session = 10_000f)
        g.run(120f) { it.misses >= 3 }
        assertTrue(g.difficulty < 0.8f)
    }

    @Test fun adaptiveLoopSettlesNearTargetWinRate() {
        // Simulated dog with reaction time that sometimes misses short windows.
        val (g, _) = game(0.5f, session = 100_000f, seed = 9)
        val rng = kotlin.random.Random(2)
        var reactAt = -1f
        repeat(60 * 60 * 30) {
            g.update(1f / 60f)
            if (g.isCritterVisible && g.phase != Phase.CELEBRATE) {
                if (reactAt < 0) reactAt = 0.6f + rng.nextFloat() * 2.4f
                reactAt -= 1f / 60f
                if (reactAt <= 0f) { g.press(); reactAt = -1f }
            } else reactAt = -1f
        }
        val rate = g.hits.toFloat() / (g.hits + g.misses)
        assertTrue("win rate $rate", rate in 0.6f..0.92f)
    }

    @Test fun mashingDelaysNextPeekAtHigherLevels() {
        val (a, _) = game(0.9f, seed = 4)
        val (b, _) = game(0.9f, seed = 4)
        repeat(10) { b.press() }
        var ta = 0f; while (a.phase == Phase.HIDDEN) { a.update(0.01f); ta += 0.01f }
        var tb = 0f; while (b.phase == Phase.HIDDEN) { b.update(0.01f); tb += 0.01f }
        assertTrue("mash delayed: $ta vs $tb", tb > ta + 1.5f)
    }

    @Test fun sessionEndsWithAllDoneSound() {
        val (g, rec) = game(0.4f, session = 20f)
        g.run(40f)
        assertEquals(Phase.DONE, g.phase)
        assertEquals(1, rec.sounds.count { it == Sound.ALL_DONE })
        g.run(10f)
        assertFalse(g.press())
    }

    @Test fun sessionWaitsForCelebrationToFinish() {
        val (g, _) = game(0f, session = 1f)
        g.run(0.9f); assertTrue(g.press())
        g.update(0.1f); g.update(0.1f)
        assertEquals(Phase.CELEBRATE, g.phase)
        g.run(4f)
        assertEquals(Phase.DONE, g.phase)
    }

    @Test fun deterministicWithSeed() {
        val (a, ra) = game(0.6f, seed = 42)
        val (b, rb) = game(0.6f, seed = 42)
        a.run(60f); b.run(60f)
        assertEquals(ra.events, rb.events)
    }

    @Test fun critterStaysOnScreen() {
        val (g, _) = game(1f, session = 1000f, seed = 8)
        repeat(60 * 300) { g.update(1f / 60f); assertTrue(g.critterX in 0.05f..0.95f) }
    }

    @Test fun wildAppearancesOnlyWhenClipReady() {
        val rec = Rec()
        var ready = false
        val g = GameLogic(6, GameConfig(10_000f, 0.4f, wildChance = 1f), rec, wildReady = { ready })
        g.run(30f) { it.phase == Phase.VISIBLE }
        assertFalse("no clip buffered -> cartoon", g.wild)
        ready = true
        g.run(30f) { it.phase == Phase.HIDDEN }
        g.run(30f) { it.phase == Phase.VISIBLE }
        assertTrue(g.wild)
        assertTrue(g.press())
        assertFalse("footage hides on hit", g.isCritterVisible)
    }

    @Test fun wildWindowIsLonger() {
        val g = GameLogic(6, GameConfig(10_000f, 0.6f, wildChance = 1f), Rec(), wildReady = { true })
        var t = 0f
        g.run(30f) { it.phase == Phase.VISIBLE }
        while (g.phase == Phase.VISIBLE) { g.update(0.01f); t += 0.01f }
        assertTrue("$t vs ${g.tuning.window}", t >= g.tuning.window * 1.4f)
    }
}
