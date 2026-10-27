package app.teron.superapp.audio

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object ProceduralSynth {

    private const val SAMPLE_RATE = 22050

    private fun midiToHz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    fun ensurePresetWav(context: Context, presetId: Int): File {
        val dir = File(context.cacheDir, "synth_tracks")
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, "teron_synth_v1_$presetId.wav")
        if (outFile.exists() && outFile.length() > 40_000L) return outFile

        val durationSec = 28
        val totalSamples = SAMPLE_RATE * durationSec
        val pcm = ShortArray(totalSamples)

        // Chord progressions & scales per preset
        val chords: Array<IntArray>
        val arpScale: IntArray
        val bpm: Double
        val pluckStyle: Boolean

        when (presetId) {
            0 -> { // Огни Находки (Synthwave)
                chords = arrayOf(
                    intArrayOf(57, 60, 64, 69), // Am
                    intArrayOf(53, 57, 60, 65), // F
                    intArrayOf(48, 55, 60, 64), // C
                    intArrayOf(55, 59, 62, 67)  // G
                )
                arpScale = intArrayOf(69, 72, 76, 79, 81, 79, 76, 72)
                bpm = 108.0
                pluckStyle = false
            }
            1 -> { // Ночная Тайга (Lo-Fi Chill)
                chords = arrayOf(
                    intArrayOf(50, 57, 60, 65), // Dm7
                    intArrayOf(55, 59, 62, 65), // G7
                    intArrayOf(48, 55, 59, 64), // Cmaj7
                    intArrayOf(57, 60, 64, 67)  // Am7
                )
                arpScale = intArrayOf(65, 67, 69, 72, 74, 72, 69, 67)
                bpm = 82.0
                pluckStyle = false
            }
            2 -> { // Шёпот Гучжэна (Oriental Pentatonic)
                chords = arrayOf(
                    intArrayOf(50, 57, 62, 69),
                    intArrayOf(47, 54, 59, 66),
                    intArrayOf(52, 59, 64, 71),
                    intArrayOf(57, 64, 69, 74)
                )
                arpScale = intArrayOf(62, 64, 66, 69, 71, 74, 71, 69)
                bpm = 90.0
                pluckStyle = true
            }
            3 -> { // Орбита AirMesh (Cyber Ambient)
                chords = arrayOf(
                    intArrayOf(52, 59, 64, 67), // Em
                    intArrayOf(48, 55, 60, 67), // C
                    intArrayOf(50, 57, 62, 69), // D
                    intArrayOf(47, 54, 59, 66)  // Bm
                )
                arpScale = intArrayOf(64, 67, 71, 74, 76, 74, 71, 67)
                bpm = 96.0
                pluckStyle = false
            }
            else -> { // Тихий Океан (Dawn Acoustic)
                chords = arrayOf(
                    intArrayOf(53, 57, 60, 64), // Fmaj7
                    intArrayOf(55, 59, 62, 67), // G6
                    intArrayOf(57, 60, 64, 69), // Am
                    intArrayOf(48, 55, 60, 64)  // C
                )
                arpScale = intArrayOf(60, 64, 67, 72, 76, 72, 67, 64)
                bpm = 86.0
                pluckStyle = true
            }
        }

        val beatSec = 60.0 / bpm
        val barSec = beatSec * 4.0
        val stepSec = beatSec / 2.0

        // Simple delay ring buffer for lush space echo
        val delaySamples = (SAMPLE_RATE * (beatSec * 0.75)).toInt().coerceAtLeast(1000)
        val delayBuf = FloatArray(delaySamples)
        var delayIdx = 0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val barIdx = ((t / barSec).toInt()) % chords.size
            val chord = chords[barIdx]
            val posInBar = t % barSec

            // Warm Pad / Chord Layer
            var pad = 0.0
            val padEnv = (1.0 - exp(-posInBar * 6.0)) * exp(-posInBar * 0.35)
            for (n in chord) {
                val f = midiToHz(n)
                pad += sin(2.0 * PI * f * t) + 0.3 * sin(4.0 * PI * f * t)
            }
            pad = (pad / chord.size) * 0.26 * padEnv

            // Deep Bass Layer
            val bassHz = midiToHz(chord[0] - 12)
            val beatPos = t % beatSec
            val bassEnv = exp(-beatPos * 3.8)
            val bass = (sin(2.0 * PI * bassHz * t) + 0.4 * sin(4.0 * PI * bassHz * t)) * 0.30 * bassEnv

            // Melodic Arp / Guzheng Pluck Layer
            val stepIdx = ((t / stepSec).toInt()) % arpScale.size
            val posInStep = t % stepSec
            val arpNote = arpScale[(stepIdx + barIdx) % arpScale.size]
            val arpHz = midiToHz(arpNote)
            val decayRate = if (pluckStyle) 9.5 else 5.2
            val arpEnv = (1.0 - exp(-posInStep * 90.0)) * exp(-posInStep * decayRate)
            var lead = sin(2.0 * PI * arpHz * t)
            if (pluckStyle) {
                lead += 0.45 * sin(4.0 * PI * arpHz * t) + 0.22 * sin(6.0 * PI * arpHz * t)
            }
            lead *= 0.24 * arpEnv

            // Soft kick pulse on beats
            val kick = if (beatPos < 0.09) {
                val kf = 110.0 * exp(-beatPos * 28.0) + 42.0
                sin(2.0 * PI * kf * beatPos) * exp(-beatPos * 24.0) * 0.25
            } else 0.0

            val dry = (pad + bass + lead + kick).toFloat()
            val echoed = delayBuf[delayIdx]
            val mixed = (dry + echoed * 0.34f).coerceIn(-0.96f, 0.96f)
            delayBuf[delayIdx] = dry + echoed * 0.28f
            delayIdx = (delayIdx + 1) % delaySamples

            // Smooth fade-in and fade-out for seamless looping
            val fade = when {
                t < 0.4 -> (t / 0.4).toFloat()
                t > durationSec - 0.8 -> ((durationSec - t) / 0.8).toFloat().coerceAtLeast(0f)
                else -> 1f
            }
            pcm[i] = (mixed * fade * 31000f).toInt().toShort()
        }

        writeMono16Wav(outFile, pcm, SAMPLE_RATE)
        return outFile
    }

    private fun writeMono16Wav(file: File, samples: ShortArray, sampleRate: Int) {
        val byteDataSize = samples.size * 2
        val totalSize = 36 + byteDataSize
        BufferedOutputStream(FileOutputStream(file), 65536).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, totalSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16) // PCM chunk size
            writeShortLE(out, 1) // AudioFormat = PCM
            writeShortLE(out, 1) // NumChannels = 1
            writeIntLE(out, sampleRate)
            writeIntLE(out, sampleRate * 2) // ByteRate
            writeShortLE(out, 2) // BlockAlign
            writeShortLE(out, 16) // BitsPerSample
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, byteDataSize)

            val buf = ByteArray(4096)
            var bIdx = 0
            for (s in samples) {
                val v = s.toInt()
                buf[bIdx++] = (v and 0xFF).toByte()
                buf[bIdx++] = ((v ushr 8) and 0xFF).toByte()
                if (bIdx == buf.size) {
                    out.write(buf, 0, bIdx)
                    bIdx = 0
                }
            }
            if (bIdx > 0) out.write(buf, 0, bIdx)
        }
    }

    private fun writeIntLE(out: BufferedOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeShortLE(out: BufferedOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
