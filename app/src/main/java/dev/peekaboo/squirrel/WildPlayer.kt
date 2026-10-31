package dev.peekaboo.squirrel

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import kotlin.random.Random

/**
 * Real wildlife footage (Creative Commons, see WILDLIFE_CREDITS.md) shown full screen
 * during an appearance. One MediaPlayer is kept prepared and paused on the *next* clip,
 * so showing it is instant even on a slow tablet.
 *
 * Threading: every MediaPlayer call runs on a private HandlerThread so decoder setup never
 * blocks the UI thread (which delivers touch input). show()/hide()/release() are called on
 * the main thread and only touch views directly. [ready] is thread-safe.
 */
class WildPlayer(
    private val ctx: Context,
    private val view: TextureView,
    seed: Long,
    /** Attribution caption shown while a clip plays (CC BY / BY-SA require credit). */
    private val caption: android.widget.TextView? = null,
    /** Test-only slow motion: footage plays at this speed (1 = normal). */
    private val speed: Float = 1f,
) : TextureView.SurfaceTextureListener {

    data class Clip(val res: Int, val name: String, val animal: String, val hasAudio: Boolean, val credit: String)

    val clips: List<Clip> = loadManifest()
    private val rng = Random(seed xor 0x5EED)
    private val bgThread = HandlerThread("peekaboo-video").apply { start() }
    private val bg = Handler(bgThread.looper)

    // Owned by the bg thread.
    private var player: MediaPlayer? = null
    private var lastIndex = -1

    @Volatile private var surface: Surface? = null
    @Volatile private var current: Clip? = null
    @Volatile private var prepared = false
    @Volatile private var showing = false
    /** Main thread: reveal the view only once a fresh frame of the new clip has arrived. */
    private var awaitingReveal = false
    @Volatile private var released = false

    val ready: Boolean get() = prepared && !showing

    init {
        view.surfaceTextureListener = this
        view.alpha = 0f
        Log.i(TAG, "WILD clips=${clips.size} ${clips.joinToString { it.name }}")
    }

    private fun loadManifest(): List<Clip> {
        val id = ctx.resources.getIdentifier("wild_manifest", "raw", ctx.packageName)
        if (id == 0) return emptyList()
        return ctx.resources.openRawResource(id).bufferedReader().readLines().mapNotNull { line ->
            val p = line.trim().split('|')
            if (p.size < 3 || line.startsWith("#")) return@mapNotNull null
            val res = ctx.resources.getIdentifier(p[0], "raw", ctx.packageName)
            if (res == 0) null else Clip(
                res, p[0], p[1], p[2] == "1",
                listOfNotNull(p.getOrNull(3), p.getOrNull(4)).joinToString(" · ") + " · Wikimedia Commons",
            )
        }
    }

    // ------------------------------------------------------------ main thread

    fun show() {
        if (!prepared || showing) return
        showing = true
        awaitingReveal = true
        view.animate().cancel()
        bg.post {
            val p = player ?: return@post
            try {
                p.start()
                if (speed != 1f && Build.VERSION.SDK_INT >= 23) p.playbackParams = p.playbackParams.setSpeed(speed)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WILD start failed", e); prepared = false
            }
        }
        Log.i(TAG, "WILD show clip=${current?.name} animal=${current?.animal}")
    }

    fun hide() {
        if (!showing) return
        showing = false
        awaitingReveal = false
        caption?.visibility = View.GONE
        bg.post {
            // pause() throws IllegalStateException if the player hit a playback error meanwhile.
            try { player?.pause() } catch (e: IllegalStateException) { Log.w(TAG, "WILD pause failed", e) }
        }
        // Prepare the next clip only after the fade, so its first frame never flashes on screen.
        view.animate().alpha(0f).setDuration(180).withEndAction { bg.post { prepareNext() } }.start()
        Log.i(TAG, "WILD hide clip=${current?.name}")
    }

    fun release() {
        view.animate().cancel() // also drops a pending fade's prepareNext()
        caption?.visibility = View.GONE
        view.alpha = 0f // else a stale frame would cover the game once the surface comes back
        prepared = false; showing = false; awaitingReveal = false
        bg.post { player?.release(); player = null }
    }

    /** Final teardown (activity destroyed). */
    fun shutdown() {
        released = true
        release()
        bgThread.quitSafely()
    }

    private fun reveal() {
        if (!awaitingReveal || !showing) return
        awaitingReveal = false
        view.alpha = 1f
        caption?.text = current?.credit
        caption?.visibility = View.VISIBLE
        Log.i(TAG, "WILD visible clip=${current?.name}")
    }

    // ------------------------------------------------------------ bg thread

    /** Buffer a random clip (never the same one twice in a row), paused at a random offset. */
    private fun prepareNext() {
        val s = surface ?: return
        if (clips.isEmpty() || released || showing) return
        prepared = false
        var i = rng.nextInt(clips.size)
        if (clips.size > 1 && i == lastIndex) i = (i + 1 + rng.nextInt(clips.size - 1)) % clips.size
        lastIndex = i
        val clip = clips[i]
        current = clip
        val mp = player ?: MediaPlayer().also { player = it }
        try {
            mp.reset()
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            mp.setDataSource(ctx, Uri.parse("android.resource://${ctx.packageName}/${clip.res}"))
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(1f, 1f)
            mp.setOnPreparedListener {
                val dur = it.duration
                val offset = if (dur > 4000) rng.nextInt(dur - 3000) else 0
                it.setOnSeekCompleteListener { _ ->
                    prepared = true
                    Log.i(TAG, "WILD ready clip=${clip.name} at=${offset}ms")
                }
                it.seekTo(offset)
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "WILD error clip=${clip.name} what=$what extra=$extra"); prepared = false; true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "WILD prepare failed ${clip.name}", e)
        }
    }

    // ------------------------------------------------------------ TextureView callbacks (main)

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        bg.post { prepareNext() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        val s = surface
        surface = null
        bg.post { s?.release() } // after the player has let go of it
        return true
    }

    /** A new frame landed in the texture: after show(), it's the new clip, so reveal now. */
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) { reveal() }

    companion object { const val TAG = "PeekabooGame" }
}
