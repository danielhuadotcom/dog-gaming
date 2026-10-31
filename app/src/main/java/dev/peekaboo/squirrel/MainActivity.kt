package dev.peekaboo.squirrel

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File

/**
 * The dog plays by touching the screen (or any big button). The trainer controls everything
 * from the trainer panel: Back button or the corner gear. Nothing is locked.
 *
 * Optional intent extras (automated tests; override saved trainer settings for this launch):
 *   seed (long), difficulty (float 0..1), session (float s; <=0 = unlimited), wild (float 0..1),
 *   cooldown (float s), ignoreCooldown (bool), audioDump (bool: tee mixer to files/audio_out.wav),
 *   timeScale (float: test-only slow motion)
 */
class MainActivity : Activity() {
    private lateinit var view: GameView
    private lateinit var audio: AudioEngine
    private lateinit var wildPlayer: WildPlayer
    private lateinit var panel: TrainerPanel
    private lateinit var settings: TrainerSettings
    private val prefs by lazy { getSharedPreferences("peekaboo", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private var savedDoneFor: GameLogic? = null
    private var appliedLevel = 0f
    private var pendingTapRestart = false
    private var testOverrides: (GameConfig) -> GameConfig = { it }
    private var cooldownOverrideSec: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferHighestRefreshRate()

        settings = TrainerSettings.load(prefs)
        val ex = intent.extras
        val seed = ex?.getLong("seed", System.nanoTime()) ?: System.nanoTime()
        if (ex?.containsKey("difficulty") == true) settings.level = ex.getFloat("difficulty")
        if (ex?.containsKey("cooldown") == true) cooldownOverrideSec = ex.getFloat("cooldown")
        val ignoreCooldown = ex?.getBoolean("ignoreCooldown", false) ?: false
        testOverrides = { c ->
            var o = c
            if (ex?.containsKey("session") == true) ex.getFloat("session").let {
                o = o.copy(sessionSeconds = if (it <= 0f) Float.POSITIVE_INFINITY else it)
            }
            if (ex?.containsKey("wild") == true) o = o.copy(wildChance = ex.getFloat("wild"), wildFirst = ex.getFloat("wild") > 0f)
            o
        }
        val timeScale = (ex?.getFloat("timeScale", 1f) ?: 1f).coerceIn(0.05f, 1f)
        val dump = if (ex?.getBoolean("audioDump", false) == true) File(filesDir, "audio_out.wav") else null

        audio = AudioEngine(dump)
        val listener = object : GameListener {
            override fun onSound(s: Sound) = audio.play(s)
            override fun onEvent(msg: String) { Log.i(GameView.TAG, msg) }
        }
        var first = true
        appliedLevel = settings.level
        view = GameView(this, logicFactory = {
            GameLogic(seed, config(settings.level), listener,
                wildReady = { this::wildPlayer.isInitialized && wildPlayer.ready }).also {
                if (first && !ignoreCooldown && cooldownLeft() > 0) it.rest()
                first = false
            }
        }, onGear = { ui.post { openPanel() } },
            onWildVisible = { show -> if (show) wildPlayer.show() else wildPlayer.hide() },
            timeScale = timeScale)

        val video = android.view.TextureView(this)
        val caption = android.widget.TextView(this).apply {
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        wildPlayer = WildPlayer(this, video, seed, caption, speed = timeScale)
        wildPlayer.clipSounds = settings.clipSounds

        panel = TrainerPanel(this, settings, status = ::statusLine, onSettingsChanged = ::applySettings,
            actions = listOf(
                "▶ Resume" to { panel.close() },
                "Show critter now (cartoon)" to { panel.close(); view.command { it.showNow(wild = false) } },
                "Show real footage now" to { panel.close(); view.command { it.showNow(wild = true) } },
                "★ Mark a win now (treat!)" to { panel.close(); view.command { it.trainerWin() } },
                "Skip treat screen" to { panel.close(); view.command { it.skipTreat() } },
                "Restart session" to { prefs.edit().putLong("restUntil", 0).apply(); panel.close(); view.restart() },
                "Reset level to Training" to {
                    settings.level = 0f; applySettings(); prefs.edit().putLong("restUntil", 0).apply()
                    panel.close(); view.restart()
                },
                "Exit app" to { finish() },
            ),
            onClose = { view.paused = false; hideSystemUi() })

        val root = FrameLayout(this)
        root.addView(view)
        root.addView(video) // footage above the game; touches fall through to the game view
        root.addView(caption, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.START,
        ))
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        tick()
    }

    private fun config(level: Float) = testOverrides(settings.toConfig(level))

    private fun cooldownSeconds() = cooldownOverrideSec ?: (settings.cooldownMin * 60f)

    private fun cooldownLeft(): Long = prefs.getLong("restUntil", 0) - System.currentTimeMillis()

    private fun statusLine(): String {
        val g = view.logic
        return "Phase: ${g.phase.name.lowercase()}   ·   wins ${g.hits}   misses ${g.misses}   trainer marks ${g.marks}" +
            "   ·   level now ${TrainerPanel.levelName(g.difficulty)}   ·   ${wildPlayer.clips.size} footage clips"
    }

    /** Trainer changed a setting: save, and apply live to the running game. */
    private fun applySettings() {
        settings.save(prefs)
        wildPlayer.clipSounds = settings.clipSounds
        val levelChanged = settings.level != appliedLevel
        appliedLevel = settings.level
        val newLevel = settings.level
        view.command { g ->
            g.config = config(g.difficulty)
            if (levelChanged) g.setDifficulty(newLevel)
        }
    }

    private fun openPanel() {
        if (panel.isOpen) return
        view.paused = true
        wildPlayer.hide()
        panel.open()
    }

    /** Persist progress + optional cooldown when a session finishes; update rest-screen text. */
    private fun tick() {
        val g = view.logic
        if (g.phase == Phase.DONE && savedDoneFor !== g) {
            savedDoneFor = g
            if (settings.autoLevel) { settings.level = g.difficulty; appliedLevel = g.difficulty; settings.save(prefs) }
            val cd = cooldownSeconds()
            if (cd > 0f && g.hits + g.misses > 0) {
                prefs.edit().putLong("restUntil", System.currentTimeMillis() + (cd * 1000).toLong()).apply()
            }
        }
        val left = cooldownLeft()
        if (left > 0) pendingTapRestart = false
        view.renderer.restMessage = if (left > 0) {
            val s = left / 1000
            "Resting — next play in ${s / 60}:${"%02d".format(s % 60)}   ·   trainer: Back → Restart session to play now"
        } else "Tap anywhere to play again"
        if (g.phase == Phase.DONE && left <= 0 && pendingTapRestart) { pendingTapRestart = false; view.restart() }
        ui.postDelayed(::tick, 500)
    }

    override fun onBackPressed() {
        if (panel.isOpen) panel.close() else openPanel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back = trainer panel (dogs can't press it); volume-down still adjusts volume.
        // Any other key = the big button (USB/Bluetooth buttons, BT camera shutters send VOLUME_UP).
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || panel.isOpen) {
            return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) onPress()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || panel.isOpen) {
            return super.onKeyUp(keyCode, event)
        }
        return true
    }

    private fun onPress() {
        if (view.logic.phase == Phase.DONE && cooldownLeft() <= 0) pendingTapRestart = true
        view.press()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!panel.isOpen && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            view.logic.phase == Phase.DONE && cooldownLeft() <= 0 && !view.renderer.inGear(ev.x, ev.y)) {
            pendingTapRestart = true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        audio.start()
    }

    override fun onPause() {
        super.onPause()
        audio.stop()
        // Footage has its own audio; don't keep playing it in the background.
        wildPlayer.hide()
        val g = view.logic
        if (settings.autoLevel && g.phase != Phase.DONE && g.hits + g.misses > 0) {
            settings.level = g.difficulty; appliedLevel = g.difficulty; settings.save(prefs)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        wildPlayer.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    /** Dogs perceive flicker at higher rates than people; ask for the fastest mode the panel has. */
    @Suppress("DEPRECATION")
    private fun preferHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        display ?: return
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        Log.i(GameView.TAG, "DISPLAY mode=${best.modeId} hz=${best.refreshRate}")
    }
}
