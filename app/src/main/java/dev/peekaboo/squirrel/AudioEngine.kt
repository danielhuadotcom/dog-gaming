package dev.peekaboo.squirrel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * One low-latency streaming AudioTrack fed by a tiny software mixer.
 * Avoids SoundPool/MediaPlayer overhead and lets us log exactly what is sent to the
 * speaker (and optionally tee it to a WAV file for automated verification).
 */
class AudioEngine(private val dumpFile: File?) {
    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .takeIf { it in 8000..192000 } ?: 48000

    private val sounds: Map<Sound, FloatArray> = Synth.all(sampleRate)
    private val pending = ConcurrentLinkedQueue<Sound>()

    private class Voice(val name: String, val data: FloatArray) { var pos = 0 }

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun play(s: Sound) {
        pending.add(s)
    }

    fun start() {
        if (running) return
        running = true
        pending.clear() // don't burst sounds queued while paused
        val t = Thread({ loop() }, "peekaboo-audio").apply { priority = Thread.MAX_PRIORITY }
        thread = t // assign before start(): loop() exits as soon as thread !== itself
        t.start()
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val me = Thread.currentThread()
        // Rebuild the track if it dies (e.g. ERROR_DEAD_OBJECT after an audio route change or
        // audioserver restart); otherwise sound would stay silent until the next pause/resume.
        while (running && thread === me) {
            if (!runTrack(me)) return
            try { Thread.sleep(200) } catch (_: InterruptedException) { return }
        }
    }

    /** Returns true if the track died and should be rebuilt. */
    private fun runTrack(me: Thread): Boolean {
        val chunk = 256
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(chunk * 2)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = try {
            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed", e); if (thread === me) running = false; return false
        }
        val dump = dumpFile?.let { WavWriter(it, sampleRate) }
        Log.i(TAG, "ENGINE start sr=$sampleRate buf=$minBuf dump=${dumpFile?.absolutePath}")

        val voices = ArrayList<Voice>(8)
        val mix = FloatArray(chunk)
        val pcm = ShortArray(chunk)
        var active = false
        var peak = 0f
        try {
            track.play() // throws IllegalStateException if the track is unusable
            // Also exit if stop()'s join timed out and a newer thread replaced us.
            while (running && thread === me) {
                while (true) {
                    val s = pending.poll() ?: break
                    val data = sounds[s] ?: continue
                    if (voices.size >= 8) voices.removeAt(0)
                    voices.add(Voice(s.name, data))
                    Log.i(TAG, "PLAY ${s.name} frames=${data.size}")
                }
                java.util.Arrays.fill(mix, 0f)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = minOf(chunk, v.data.size - v.pos)
                    for (i in 0 until n) mix[i] += v.data[v.pos + i]
                    v.pos += n
                    if (v.pos >= v.data.size) it.remove()
                }
                var sumSq = 0.0
                for (i in 0 until chunk) {
                    var x = mix[i]
                    // Soft knee limiter above 0.8 so overlapping sounds never hard-clip.
                    val ax = abs(x)
                    if (ax > 0.8f) x = Math.copySign(0.8f + 0.2f * tanh((ax - 0.8f) / 0.2f), x)
                    sumSq += x * x
                    if (abs(x) > peak) peak = abs(x)
                    pcm[i] = (x * 32767f).toInt().toShort()
                }
                val rms = sqrt(sumSq / chunk)
                if (!active && rms > 0.005) {
                    active = true; peak = 0f
                    Log.i(TAG, "OUT active")
                } else if (active && rms <= 0.0005 && voices.isEmpty()) {
                    active = false
                    Log.i(TAG, "OUT silent peak=${"%.3f".format(peak)}")
                    dump?.flushHeader()
                }
                val wrote = track.write(pcm, 0, chunk)
                if (wrote < 0) { Log.w(TAG, "ENGINE write error $wrote, rebuilding"); return true }
                dump?.write(pcm, chunk)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "ENGINE failed", e); return true
        } finally {
            dump?.close()
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
            Log.i(TAG, "ENGINE stop")
        }
        return false
    }

    /** Streams 16-bit mono PCM to a WAV file, keeping the header valid as it grows. */
    private class WavWriter(file: File, private val sr: Int) {
        private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        private val buf = ByteArray(256 * 2)
        private var dataBytes = 0L
        private val maxBytes = sr * 2L * 300 // cap at 5 minutes

        init { flushHeader() }

        fun write(pcm: ShortArray, n: Int) {
            if (dataBytes >= maxBytes) return
            for (i in 0 until n) {
                buf[2 * i] = (pcm[i].toInt() and 0xff).toByte()
                buf[2 * i + 1] = (pcm[i].toInt() shr 8 and 0xff).toByte()
            }
            raf.write(buf, 0, n * 2)
            dataBytes += n * 2
        }

        fun flushHeader() {
            val pos = raf.filePointer
            raf.seek(0)
            fun i32(v: Int) { raf.write(v and 0xff); raf.write(v shr 8 and 0xff); raf.write(v shr 16 and 0xff); raf.write(v shr 24 and 0xff) }
            fun i16(v: Int) { raf.write(v and 0xff); raf.write(v shr 8 and 0xff) }
            raf.writeBytes("RIFF"); i32((36 + dataBytes).toInt()); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16)
            raf.writeBytes("data"); i32(dataBytes.toInt())
            raf.seek(pos)
        }

        fun close() { flushHeader(); raf.close() }
    }

    companion object { const val TAG = "PeekabooAudio" }
}
