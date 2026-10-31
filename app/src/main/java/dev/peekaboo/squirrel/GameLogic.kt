package dev.peekaboo.squirrel

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class Sound { SQUEAK, CHIRP, RUSTLE, SCURRY, CELEBRATE, ALL_DONE }

/**
 * HIDDEN    critter is behind a bush, nothing happening
 * RUSTLE    a bush shakes (anticipation). May be a fake-out at higher levels.
 * EMERGE    critter peeks up over the bush (counts as visible)
 * VISIBLE   critter is out in the open, darting start-stop
 * RETREAT   critter runs back and ducks behind a bush (still counts as visible)
 * CELEBRATE dog scored: squeak, spin, treat cue
 * DONE      session over, resting screen
 */
enum class Phase { HIDDEN, RUSTLE, EMERGE, VISIBLE, RETREAT, CELEBRATE, DONE }

interface GameListener {
    fun onSound(s: Sound) {}
    fun onEvent(msg: String) {}
}

data class GameConfig(
    val sessionSeconds: Float = 240f,
    val startDifficulty: Float = 0f,
    /** Chance that an appearance uses real wildlife footage instead of the cartoon critter. */
    val wildChance: Float = 0f,
)

/** All timing knobs derived from a single 0..1 difficulty value. */
data class Tuning(
    val training: Boolean,
    val window: Float,
    val hideMin: Float,
    val hideMax: Float,
    val rustle: Float,
    val spots: Int,
    val fakeChance: Float,
    val mashPenalty: Float,
) {
    companion object {
        const val TRAINING_BELOW = 0.15f

        fun of(d: Float): Tuning {
            if (d < TRAINING_BELOW) {
                return Tuning(true, Float.POSITIVE_INFINITY, 1f, 1f, 0.8f, 1, 0f, 0f)
            }
            val t = ((d - TRAINING_BELOW) / (1f - TRAINING_BELOW)).coerceIn(0f, 1f)
            var spots = 1 + floor(t * 3.99f).toInt()
            if (t > 0.15f) spots = max(spots, 2)
            return Tuning(
                training = false,
                window = lerp(4.5f, 1.3f, t),
                hideMin = 1.5f,
                hideMax = lerp(2.5f, 5.0f, t),
                rustle = lerp(1.4f, 0.5f, t),
                spots = spots.coerceIn(1, 4),
                fakeChance = max(0f, (t - 0.4f) / 0.6f * 0.35f),
                mashPenalty = if (t > 0.4f) 0.4f else 0f,
            )
        }
    }
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

class GameLogic(
    seed: Long,
    val config: GameConfig,
    private val listener: GameListener,
    /** Whether a wildlife clip is buffered and ready to show instantly. */
    private val wildReady: () -> Boolean = { false },
) {
    private val rng = Random(seed)

    var difficulty = config.startDifficulty.coerceIn(0f, 1f); private set
    var tuning = Tuning.of(difficulty); private set
    var phase = Phase.HIDDEN; private set
    var phaseTime = 0f; private set
    private var phaseDuration = 1f
    var elapsed = 0f; private set
    var hits = 0; private set
    var misses = 0; private set
    private val history = ArrayDeque<Boolean>()
    private var consecutiveMisses = 0
    private var mashExtra = 0f

    // Hiding spots (normalized x across the screen).
    var spotCount = 1; private set
    val spotX = FloatArray(4)
    var currentSpot = 0; private set
    var rustleSpot = -1; private set
    var rustleFake = false; private set

    // Critter pose, read by the renderer.
    var critterX = 0.5f; private set
    /** 0 = fully hidden behind bush, 1 = peeking over it. Only meaningful when [critterBehindBush]. */
    var peek = 0f; private set
    /** 0 = at peek position, 1 = on the ground in front. Arc between the two. */
    var leap = 0f; private set
    var critterBehindBush = true; private set
    var facing = 1; private set
    var twitch = false; private set
    var moving = false; private set
    /** This appearance is shown as real wildlife footage. */
    var wild = false; private set

    // Dart state machine (VISIBLE).
    private var moveFrom = 0.5f
    private var moveTo = 0.5f
    private var moveT = 0f
    private var moveDur = 0f
    private var freezeLeft = 0f
    private var leapIn = 0f
    private var retreatTarget = 0

    val sessionOver get() = elapsed >= config.sessionSeconds
    val isCritterVisible get() = phase == Phase.EMERGE || phase == Phase.VISIBLE || phase == Phase.RETREAT
    val remainingSeconds get() = max(0f, config.sessionSeconds - elapsed)

    init {
        layoutSpots()
        listener.onEvent("START seed=$seed d=${fmt(difficulty)} session=${config.sessionSeconds}")
        if (tuning.training) startTraining() else enter(Phase.HIDDEN, hideTime())
    }

    /** Put the game straight into the resting state (cooldown still active from a previous session). */
    fun rest() {
        enter(Phase.DONE, Float.POSITIVE_INFINITY)
    }

    // ---------------------------------------------------------------- input

    /** A nose boop / paw / button press. Returns true if it scored. */
    fun press(): Boolean {
        if (isCritterVisible) {
            hits++
            recordTrial(true)
            listener.onSound(Sound.SQUEAK)
            listener.onSound(Sound.CELEBRATE)
            listener.onEvent("PRESS hit phase=${phase.name} hits=$hits")
            enter(Phase.CELEBRATE, 3.5f)
            return true
        }
        if ((phase == Phase.HIDDEN || phase == Phase.RUSTLE) && tuning.mashPenalty > 0f && mashExtra < 2f) {
            // No buzzer, no punishment: mashing just quietly delays the next peek.
            val add = min(tuning.mashPenalty, 2f - mashExtra)
            mashExtra += add
            if (phase == Phase.HIDDEN) phaseDuration += add
        }
        listener.onEvent("PRESS ignored phase=${phase.name}")
        return false
    }

    // ---------------------------------------------------------------- tick

    fun update(dtIn: Float) {
        val dt = dtIn.coerceIn(0f, 0.1f)
        if (phase != Phase.DONE) elapsed += dt
        phaseTime += dt
        when (phase) {
            Phase.HIDDEN -> if (phaseTime >= phaseDuration) {
                if (sessionOver) finish() else startRustle()
            }
            Phase.RUSTLE -> if (phaseTime >= phaseDuration) {
                when {
                    sessionOver -> finish()
                    rustleFake -> {
                        listener.onEvent("FAKE_RUSTLE_END")
                        enter(Phase.HIDDEN, rng.nextFloat() * 1.2f + 0.6f)
                    }
                    else -> startEmerge()
                }
            }
            Phase.EMERGE -> {
                peek = (phaseTime / phaseDuration).coerceIn(0f, 1f)
                if (phaseTime >= phaseDuration) startVisible()
            }
            Phase.VISIBLE -> tickVisible(dt)
            Phase.RETREAT -> tickRetreat()
            Phase.CELEBRATE -> if (phaseTime >= phaseDuration) {
                when {
                    sessionOver -> finish()
                    tuning.training -> startTraining()
                    else -> enter(Phase.HIDDEN, 1.0f + rng.nextFloat())
                }
            }
            Phase.DONE -> Unit
        }
    }

    // ---------------------------------------------------------------- phases

    private fun enter(p: Phase, duration: Float) {
        phase = p
        phaseTime = 0f
        phaseDuration = duration
        if (p == Phase.HIDDEN) {
            mashExtra = 0f
            wild = false
            critterBehindBush = true
            peek = 0f
            leap = 0f
            moving = false
            // Re-layout is only safe while hidden.
            tuning = Tuning.of(difficulty)
            if (tuning.spots != spotCount) layoutSpots()
        }
        listener.onEvent("PHASE ${p.name} d=${fmt(difficulty)} spots=$spotCount dur=${fmt(duration)}")
    }

    private fun hideTime() = tuning.hideMin + rng.nextFloat() * (tuning.hideMax - tuning.hideMin)

    private fun layoutSpots() {
        spotCount = tuning.spots
        for (i in 0 until spotCount) spotX[i] = (i + 1f) / (spotCount + 1f)
        currentSpot = currentSpot.coerceAtMost(spotCount - 1)
    }

    private fun startTraining() {
        // Level 1: critter just sits out in the open. Any press wins.
        tuning = Tuning.of(difficulty)
        if (tuning.spots != spotCount) layoutSpots()
        phase = Phase.HIDDEN // so enter() below logs a transition cleanly
        critterX = 0.5f
        critterBehindBush = false
        leap = 1f
        peek = 1f
        freezeLeft = 0.5f
        moving = false
        pickWild()
        if (wild) listener.onEvent("WILD start training")
        listener.onSound(Sound.CHIRP)
        phase = Phase.VISIBLE
        phaseTime = 0f
        phaseDuration = Float.POSITIVE_INFINITY
        listener.onEvent("PHASE VISIBLE training d=${fmt(difficulty)}")
    }

    private fun startRustle() {
        rustleSpot = rng.nextInt(spotCount)
        rustleFake = rng.nextFloat() < tuning.fakeChance
        listener.onSound(Sound.RUSTLE)
        enter(Phase.RUSTLE, tuning.rustle)
        listener.onEvent("RUSTLE spot=$rustleSpot fake=$rustleFake")
    }

    private fun pickWild() {
        wild = config.wildChance > 0f && rng.nextFloat() < config.wildChance && wildReady()
    }

    private fun startEmerge() {
        pickWild()
        if (wild) listener.onEvent("WILD start")
        currentSpot = rustleSpot
        critterX = spotX[currentSpot]
        critterBehindBush = true
        peek = 0f
        leap = 0f
        facing = if (critterX < 0.5f) 1 else -1
        listener.onSound(Sound.CHIRP)
        enter(Phase.EMERGE, 0.3f)
    }

    private fun startVisible() {
        enter(Phase.VISIBLE, if (wild) tuning.window * 1.5f else tuning.window)
        critterBehindBush = false
        peek = 1f
        leap = 0f
        leapIn = 0.25f
        moveFrom = critterX
        moveTo = (critterX + facing * 0.08f).coerceIn(0.1f, 0.9f)
        moving = false
        freezeLeft = 0f
    }

    private fun tickVisible(dt: Float) {
        if (leapIn > 0f) {
            leapIn -= dt
            val t = 1f - max(0f, leapIn) / 0.25f
            leap = t
            critterX = lerp(moveFrom, moveTo, t)
            if (leapIn <= 0f) freezeLeft = 0.3f + rng.nextFloat() * 0.3f
            return
        }
        leap = 1f
        if (moving) {
            moveT += dt
            val t = (moveT / moveDur).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t) // ease-out: burst then brake
            critterX = lerp(moveFrom, moveTo, e)
            if (t >= 1f) {
                moving = false
                freezeLeft = 0.25f + rng.nextFloat() * 0.45f
                twitch = rng.nextBoolean()
            }
        } else {
            freezeLeft -= dt
            if (freezeLeft <= 0f) {
                if (!tuning.training && phaseTime >= phaseDuration) {
                    startRetreat()
                    return
                }
                startDart(if (tuning.training) 0.12f else 0.35f)
            }
        }
    }

    private fun startDart(range: Float) {
        moveFrom = critterX
        var target: Float
        var tries = 0
        do {
            target = (critterX + (rng.nextFloat() * 2f - 1f) * range).coerceIn(0.12f, 0.88f)
            tries++
        } while (abs(target - critterX) < 0.06f && tries < 8)
        moveTo = target
        moveT = 0f
        moveDur = 0.15f + rng.nextFloat() * 0.15f
        moving = true
        facing = if (moveTo >= moveFrom) 1 else -1
        listener.onSound(Sound.SCURRY)
    }

    private fun startRetreat() {
        retreatTarget = rng.nextInt(spotCount)
        moveFrom = critterX
        moveTo = spotX[retreatTarget]
        facing = if (moveTo >= moveFrom) 1 else -1
        listener.onSound(Sound.SCURRY)
        enter(Phase.RETREAT, 0.55f)
    }

    private fun tickRetreat() {
        val run = 0.3f
        if (phaseTime < run) {
            critterBehindBush = false
            leap = 1f
            critterX = lerp(moveFrom, moveTo, phaseTime / run)
        } else {
            critterX = moveTo
            critterBehindBush = true
            currentSpot = retreatTarget
            peek = (1f - (phaseTime - run) / (phaseDuration - run)).coerceIn(0f, 1f)
        }
        if (phaseTime >= phaseDuration) {
            misses++
            recordTrial(false)
            listener.onEvent("TRIAL miss misses=$misses")
            if (sessionOver) finish() else enter(Phase.HIDDEN, hideTime())
        }
    }

    private fun finish() {
        listener.onSound(Sound.ALL_DONE)
        listener.onEvent("SESSION_DONE hits=$hits misses=$misses d=${fmt(difficulty)}")
        critterBehindBush = true
        peek = 0f
        enter(Phase.DONE, Float.POSITIVE_INFINITY)
    }

    // ---------------------------------------------------------------- adaptation

    /** Keep the dog winning ~70–80% of the time. */
    private fun recordTrial(hit: Boolean) {
        history.addLast(hit)
        while (history.size > 8) history.removeFirst()
        consecutiveMisses = if (hit) 0 else consecutiveMisses + 1
        val before = difficulty
        if (consecutiveMisses >= 3) {
            difficulty -= 0.1f
            history.clear()
            consecutiveMisses = 0
        } else if (history.size >= 4) {
            val rate = history.count { it }.toFloat() / history.size
            if (rate >= 0.85f) {
                difficulty += 0.07f; history.clear()
            } else if (rate < 0.6f) {
                difficulty -= 0.1f; history.clear()
            }
        }
        difficulty = difficulty.coerceIn(0f, 1f)
        if (difficulty != before) listener.onEvent("DIFFICULTY ${fmt(before)} -> ${fmt(difficulty)}")
    }

    private fun fmt(f: Float) = if (f.isInfinite()) "inf" else String.format(java.util.Locale.US, "%.2f", f)
}
