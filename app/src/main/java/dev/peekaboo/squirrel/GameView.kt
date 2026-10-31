package dev.peekaboo.squirrel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicInteger

/**
 * SurfaceView with a dedicated render thread. unlockCanvasAndPost blocks on vsync,
 * so the loop naturally runs at the display's refresh rate (60/90/120 Hz).
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val logicFactory: () -> GameLogic,
    private val onOwnerOverride: () -> Unit,
    private val onWildVisible: (Boolean) -> Unit = {},
    /** Test-only slow motion for recordings (1 = real time). */
    private val timeScale: Float = 1f,
) : SurfaceView(context), SurfaceHolder.Callback {
    /** Set if hardware canvas posting stops blocking on vsync (surface wedged): use software canvas. */
    @Volatile private var softwareCanvas = false

    @Volatile var logic: GameLogic = logicFactory()
        private set
    val renderer = Renderer()
    private val presses = AtomicInteger(0)
    @Volatile private var resetRequested = false
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    // Owner override: hold the top-right corner for 3 s.
    private var cornerDownAt = -1L

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun press() { presses.incrementAndGet() }

    fun restart() { resetRequested = true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val inCorner = e.getX(i) > width * 0.92f && e.getY(i) < height * 0.12f
                if (inCorner && logic.phase == Phase.DONE) cornerDownAt = SystemClock.uptimeMillis()
                else press()
            }
            MotionEvent.ACTION_MOVE -> if (cornerDownAt > 0 && SystemClock.uptimeMillis() - cornerDownAt > 3000) {
                cornerDownAt = -1
                onOwnerOverride()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (cornerDownAt > 0 && SystemClock.uptimeMillis() - cornerDownAt > 3000) onOwnerOverride()
                cornerDownAt = -1
            }
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        val t = Thread({ loop() }, "peekaboo-render")
        thread = t // assign before start(): loop() exits as soon as thread !== itself
        t.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.resize(width, height)
        Log.i(TAG, "SURFACE ${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        var last = System.nanoTime()
        var frames = 0
        var fpsT = 0f
        var wildShown = false
        val me = Thread.currentThread()
        // Also exit if surfaceDestroyed's join timed out and a newer thread replaced us,
        // so two threads never update the (non-thread-safe) logic or draw at once.
        while (running && thread === me) {
            val now = System.nanoTime()
            val dt = (now - last) / 1e9f
            last = now
            if (resetRequested) { resetRequested = false; logic = logicFactory() }
            val g = logic
            repeat(presses.getAndSet(0).coerceAtMost(1)) { g.press() }
            g.update(dt * timeScale)
            val wildNow = g.wild && g.isCritterVisible
            if (wildNow != wildShown) {
                wildShown = wildNow
                post { onWildVisible(wildNow) }
            }

            val c = try {
                if (Build.VERSION.SDK_INT >= 26 && !softwareCanvas) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (e: Exception) { null }
            if (c == null) { try { Thread.sleep(16) } catch (_: InterruptedException) {}; continue }
            try {
                renderer.draw(c, g, dt)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
            // Posting normally blocks on vsync. If it stops blocking, cap the loop at ~125 fps so we
            // never spin, and treat sustained >200 fps as a wedged hardware surface.
            val frameNs = System.nanoTime() - now
            if (frameNs < 8_000_000L) try { Thread.sleep((8_000_000L - frameNs) / 1_000_000L) } catch (_: InterruptedException) {}
            frames++; fpsT += dt
            if (fpsT >= 5f) {
                val fps = frames / fpsT
                Log.i(TAG, "FPS ${"%.1f".format(fps)}")
                if (fps > 110f && !softwareCanvas && Build.VERSION.SDK_INT >= 26 && maxRefresh() < 100f) {
                    softwareCanvas = true
                    Log.w(TAG, "SURFACE_WEDGED fps=$fps -> software canvas")
                }
                frames = 0; fpsT = 0f
            }
        }
        // Surface gone (screen off / backgrounded): never leave footage up or playing.
        // The next render thread starts with wildShown=false and re-shows it if still wild.
        if (wildShown) post { onWildVisible(false) }
    }

    @Suppress("DEPRECATION")
    private fun maxRefresh(): Float = display?.refreshRate ?: 60f

    companion object { const val TAG = "PeekabooGame" }
}
