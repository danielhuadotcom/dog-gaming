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
import java.io.File

/**
 * Optional intent extras (used by automated tests, harmless otherwise):
 *   seed (long)            deterministic RNG
 *   difficulty (float)     0..1 starting difficulty (overrides saved progress)
 *   session (float)        session length in seconds (default 240)
 *   cooldown (float)       rest period in seconds after a session (default 600)
 *   ignoreCooldown (bool)  start playing even if resting
 *   audioDump (bool)       tee all speaker output to files/audio_out.wav
 *   timeScale (float)      test-only slow motion for screen recordings (default 1)
 *   wild (float)           0..1 chance an appearance uses real wildlife footage (default 0.5)
 */
class MainActivity : Activity() {
    private lateinit var view: GameView
    private lateinit var audio: AudioEngine
    private lateinit var wildPlayer: WildPlayer
    private val prefs by lazy { getSharedPreferences("peekaboo", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private var cooldownSeconds = 600f
    private var savedDoneFor: GameLogic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferHighestRefreshRate()

        val ex = intent.extras
        val seed = ex?.getLong("seed", System.nanoTime()) ?: System.nanoTime()
        val session = ex?.getFloat("session", 240f) ?: 240f
        cooldownSeconds = ex?.getFloat("cooldown", 600f) ?: 600f
        val startD = if (ex?.containsKey("difficulty") == true) ex.getFloat("difficulty")
        else prefs.getFloat("difficulty", 0f)
        var ignoreCooldown = ex?.getBoolean("ignoreCooldown", false) ?: false
        val wildChance = ex?.getFloat("wild", 0.5f) ?: 0.5f
        val timeScale = (ex?.getFloat("timeScale", 1f) ?: 1f).coerceIn(0.05f, 1f)
        val dump = if (ex?.getBoolean("audioDump", false) == true) File(filesDir, "audio_out.wav") else null

        audio = AudioEngine(dump)
        val listener = object : GameListener {
            override fun onSound(s: Sound) = audio.play(s)
            override fun onEvent(msg: String) { Log.i(GameView.TAG, msg) }
        }
        var first = true
        view = GameView(this, logicFactory = {
            val d = if (first) startD else prefs.getFloat("difficulty", startD)
            GameLogic(
                seed, GameConfig(sessionSeconds = session, startDifficulty = d, wildChance = wildChance),
                listener, wildReady = { this::wildPlayer.isInitialized && wildPlayer.ready },
            ).also {
                if (first && !ignoreCooldown && cooldownLeft() > 0) it.rest()
                first = false
            }
        }, onOwnerOverride = {
            Log.i(GameView.TAG, "OWNER_OVERRIDE")
            prefs.edit().putLong("restUntil", 0).apply()
            view.restart()
        }, onWildVisible = { show -> if (show) wildPlayer.show() else wildPlayer.hide() }, timeScale = timeScale)
        val video = android.view.TextureView(this)
        val caption = android.widget.TextView(this).apply {
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        wildPlayer = WildPlayer(this, video, seed, caption, speed = timeScale)
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        // Footage sits above the game; touches fall through to the game view.
        root.addView(video)
        root.addView(caption, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.START,
        ))
        setContentView(root)
        tickRestMessage()
    }

    private fun cooldownLeft(): Long = prefs.getLong("restUntil", 0) - System.currentTimeMillis()

    /** Persist progress + start cooldown once a session finishes; update owner text. */
    private fun tickRestMessage() {
        val g = view.logic
        if (g.phase == Phase.DONE && savedDoneFor !== g) {
            savedDoneFor = g
            if (g.hits + g.misses > 0) {
                prefs.edit()
                    .putFloat("difficulty", g.difficulty)
                    .putLong("restUntil", System.currentTimeMillis() + (cooldownSeconds * 1000).toLong())
                    .apply()
            }
        }
        val left = cooldownLeft()
        // A tap in the <=500 ms before restUntil was saved must not auto-restart the game
        // the moment the cooldown later expires.
        if (left > 0) pendingTapRestart = false
        view.renderer.restMessage = if (left > 0) {
            val s = left / 1000
            "Next play in ${s / 60}:${"%02d".format(s % 60)}   ·   owner: hold top-right corner 3s to play now"
        } else "Rested! Tap anywhere to play again"
        if (g.phase == Phase.DONE && left <= 0 && pendingTapRestart) { pendingTapRestart = false; view.restart() }
        ui.postDelayed(::tickRestMessage, 500)
    }

    private var pendingTapRestart = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Any key = the big button (USB/Bluetooth buttons, keyboards, BT camera shutters
        // which send VOLUME_UP). Back still exits; volume-down still adjusts volume.
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) onPress()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyUp(keyCode, event)
        }
        return true
    }

    private fun onPress() {
        if (view.logic.phase == Phase.DONE && cooldownLeft() <= 0) pendingTapRestart = true
        view.press()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && view.logic.phase == Phase.DONE && cooldownLeft() <= 0) {
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
        // Footage has its own audio; don't keep playing it in the background. If the surface
        // was destroyed the new render thread re-shows it; otherwise the cartoon (always drawn
        // underneath) carries the rest of this appearance.
        wildPlayer.hide()
        val g = view.logic
        if (g.phase != Phase.DONE && g.hits + g.misses > 0) {
            prefs.edit().putFloat("difficulty", g.difficulty).apply()
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
