package com.nefelion.fourpartwriting.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.nefelion.fourpartwriting.theory.Overtones
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small real-time synth for the overtone lesson: single piano partials (see [Overtones.A4_PIANO]) as pure tones,
 * each struck: a hammer's quick attack, then the piano's fade.
 */
object Tones {
    private const val RATE = 44100
    private const val BLOCK = 512
    private const val FADE = RATE / 16

    /** Partial [p] struck at [start] seconds, fading as on the piano ([Overtones.Partial.level]); damped at [end] over [release] seconds. */
    class Tone(val p: Overtones.Partial, val start: Double, val end: Double, val release: Double = 1.0 / 16)

    /** A tone being rendered. Times in samples. */
    private class Voice(val p: Overtones.Partial, val start: Long, var end: Long, var release: Long) {
        private val c = cos(2 * PI * p.hz / RATE); private val s = sin(2 * PI * p.hz / RATE)
        private var x = 1.0; private var y = 0.0
        var done = false

        /** Adds samples [from] until [from] + out.size to [out]. */
        fun render(out: DoubleArray, from: Long) {
            for (i in out.indices) {
                val t = from + i
                if (t < start) continue
                // A 2 ms attack, then the piano's fade; after [end], a smooth (raised-cosine) fade to silence
                val env = p.level((t - start).toDouble() / RATE) * min(1.0, (t - start) / (RATE / 500.0))
                val u = if (t < end) 0.0 else (t - end).toDouble() / release
                if (u >= 1) { done = true; return }
                // Rotating phasor: a sine without calling sin() per sample
                val nx = x * c - y * s; y = x * s + y * c; x = nx
                if (t and 1023L == 0L) { val r = 1 / sqrt(x * x + y * y); x *= r; y *= r }
                out[i] += y * env * 0.5 * (1 + cos(PI * u))
            }
        }
    }

    private val lock = Any()
    private val voices = ArrayList<Voice>()
    /** Samples rendered so far on the current track; [origin]: where the current schedule starts; [until]: keep the clock running till then. */
    private var clock = 0L
    private var origin = 0L
    private var until = 0L
    private var track: AudioTrack? = null
    private var running = false

    /**
     * Plays [tones] (times from now), replacing what sounds: old tones fade out quickly.
     * [elapsed] keeps counting for at least [seconds], even through silence.
     */
    fun play(tones: List<Tone>, seconds: Double) = synchronized(lock) {
        if (!running) start()
        origin = clock
        fun at(sec: Double) = origin + (sec * RATE).toLong()
        until = at(seconds)
        voices.forEach(::cut)
        for (t in tones) voices += Voice(t.p, at(t.start), at(t.end), (t.release * RATE).toLong())
    }

    /** Fades out everything playing. */
    fun stop() = synchronized(lock) {
        voices.forEach(::cut)
        until = 0
    }

    /** Ends [v] now with a quick fade, or sooner if it was ending already. */
    private fun cut(v: Voice) {
        if (v.end > clock) { v.end = clock; v.release = minOf(v.release, FADE.toLong()) }
    }

    /** Seconds the current schedule has been heard (the speaker lags behind rendering). */
    fun elapsed(): Double = synchronized(lock) {
        val t = track ?: return 0.0
        // What the speaker has played: the head only says what the mixer took, a buffer or more earlier
        val heard = if (t.getTimestamp(stamp)) stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * RATE / 1e9
            else t.playbackHeadPosition.toDouble()
        maxOf(0.0, (heard - origin) / RATE)
    }
    private val stamp = AudioTimestamp()

    private fun start() {
        val size = maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), BLOCK * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        track = t; clock = 0; origin = 0; running = true
        t.play()
        Thread({ loop(t) }, "tones").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    /** Renders block after block; writing blocks until the track has room, which paces it. Ends after half a second of nothing to do. */
    private fun loop(t: AudioTrack) {
        val buf = DoubleArray(BLOCK)
        val pcm = ShortArray(BLOCK)
        var idle = 0
        var quit = false
        while (true) {
            synchronized(lock) {
                buf.fill(0.0)
                for (v in voices) v.render(buf, clock)
                voices.removeAll { it.done }
                clock += BLOCK
                idle = if (voices.isEmpty() && clock > until) idle + BLOCK else 0
                if (idle > RATE / 2) { running = false; track = null; quit = true }
            }
            if (quit) break
            // At the piano's own output level, so held partials are as loud as its strike. Linear (a soft clipper
            // would mix partials into new, out-of-tune ones); a clip only guards overflow
            for (i in buf.indices) pcm[i] = (buf[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            t.write(pcm, 0, BLOCK)
        }
        runCatching { t.stop() }
        t.release()
    }
}
