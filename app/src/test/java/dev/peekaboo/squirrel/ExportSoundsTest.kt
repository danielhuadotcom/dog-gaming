package dev.peekaboo.squirrel

import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Writes every synthesized sound to app/build/sounds/<NAME>.wav (48 kHz mono) for recordings/tools. */
class ExportSoundsTest {
    @Test fun export() {
        val sr = 48000
        val dir = File("build/sounds").apply { mkdirs() }
        for ((s, d) in Synth.all(sr)) {
            DataOutputStream(FileOutputStream(File(dir, "${s.name}.wav"))).use { o ->
                fun i32(v: Int) = o.writeInt(Integer.reverseBytes(v))
                fun i16(v: Int) = o.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
                o.writeBytes("RIFF"); i32(36 + d.size * 2); o.writeBytes("WAVEfmt "); i32(16)
                i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16); o.writeBytes("data"); i32(d.size * 2)
                for (x in d) i16((x.coerceIn(-1f, 1f) * 32767).toInt())
            }
        }
    }
}
