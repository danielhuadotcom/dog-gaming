package dev.peekaboo.squirrel

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.random.Random

/**
 * Real wildlife footage (Creative Commons, see WILDLIFE_CREDITS.md) shown full screen
 * during an appearance. One MediaPlayer is kept prepared and paused on the *next* clip,
 * so showing it is instant even on a slow tablet.
 *
 * All methods must be called on the main thread except [ready], which is thread-safe.
 */
class WildPlayer(
    private val ctx: Context,
    private val view: TextureView,
    seed: Long,
    /** Attribution caption shown while a clip plays (CC BY / BY-SA require credit). */
    private val caption: android.widget.TextView? = null,
) :
    TextureView.SurfaceTextureListener {

    data class Clip(val res: Int, val name: String, val animal: String, val hasAudio: Boolean, val credit: String)

    val clips: List<Clip> = loadManifest()
    private val rng = Random(seed xor 0x5EED)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var current: Clip? = null
    private var lastIndex = -1
    @Volatile private var prepared = false
    @Volatile private var showing = false

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

    fun show() {
        val p = player ?: return
        if (!prepared || showing) return
        try {
            p.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WILD start failed", e); prepared = false; return
        }
        showing = true
        view.animate().cancel()
        view.alpha = 1f
        caption?.text = current?.credit
        caption?.visibility = android.view.View.VISIBLE
        Log.i(TAG, "WILD show clip=${current?.name} animal=${current?.animal}")
    }

    fun hide() {
        if (!showing) return
        showing = false
        caption?.visibility = android.view.View.GONE
        view.animate().alpha(0f).setDuration(180).withEndAction { prepareNext() }.start()
        // pause() throws IllegalStateException if the player hit a playback error meanwhile.
        try { player?.pause() } catch (e: IllegalStateException) { Log.w(TAG, "WILD pause failed", e) }
        Log.i(TAG, "WILD hide clip=${current?.name}")
    }

    fun release() {
        // Cancelling also drops a pending fade's prepareNext() (would leak a new player after release).
        view.animate().cancel()
        caption?.visibility = android.view.View.GONE
        view.alpha = 0f // else a stale frame would cover the game once the surface comes back
        player?.release(); player = null
        prepared = false; showing = false
    }

    /** Buffer a random clip (never the same one twice in a row), paused at a random offset. */
    fun prepareNext() {
        val s = surface ?: return
        if (clips.isEmpty()) return
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
                it.seekTo(offset)
                it.setOnSeekCompleteListener { _ ->
                    prepared = true
                    Log.i(TAG, "WILD ready clip=${clip.name} at=${offset}ms")
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "WILD error clip=${clip.name} what=$what extra=$extra"); prepared = false; true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "WILD prepare failed ${clip.name}", e)
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        prepareNext()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release(); surface?.release(); surface = null; return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    companion object { const val TAG = "PeekabooGame" }
}
