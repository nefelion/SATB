package com.nefelion.fourpartwriting.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nefelion.fourpartwriting.theory.Overtones
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A small real-time synth for the overtone lesson: piano partials (see [Overtones.piano]) rendered as they play,
 * so a held tone can last until the next step and carry on into it without a gap. No SoundFont needed.
 */
object Tones {
    private const val RATE = 44100
    private const val BLOCK = 512
    private const val FADE = RATE / 16

    /** A struck tone starts this much stronger than a held one: the hammer hits harder than a held tone sounds. */
    const val STRIKE = 1.6

    /**
     * Partial [p] from [start] seconds: [held] stays steady until [end], like a held organ pipe; otherwise it rings out like a struck string.
     * After [end] it fades smoothly to silence over [release] seconds.
     */
    class Tone(val p: Overtones.Partial, val start: Double, val held: Boolean, val end: Double = Double.POSITIVE_INFINITY, val release: Double = 1.0 / 16)

    /** Partials [p] struck at [at] seconds, fading out after [end]. */
    fun strike(p: List<Overtones.Partial>, at: Double = 0.0, end: Double = Double.POSITIVE_INFINITY) = p.map { Tone(it, at, held = false, end = end) }

    /** A tone being rendered: two strings a hair apart, so the note shimmers like a real piano. Times in samples. */
    private class Voice(val p: Overtones.Partial, val start: Long, val held: Boolean, var end: Long, var release: Long) {
        private val rnd = Random(p.hz.toRawBits())
        private val spread = if (held) 0.0 else rnd.nextDouble(0.00002, 0.00008)
        private val c = DoubleArray(2); private val s = DoubleArray(2)
        private val x = DoubleArray(2); private val y = DoubleArray(2)
        private var ef = STRIKE * 0.7 * p.amp / 2; private var es = STRIKE * 0.3 * p.amp / 2
        private val df = exp(-p.fast / RATE); private val ds = exp(-p.slow / RATE)
        var done = false

        init {
            // Both strings start together (as the hammer strikes them at once) and drift apart slowly
            val ph = if (held) 0.0 else rnd.nextDouble(0.0, 2 * PI)
            for (k in 0..1) {
                val w = 2 * PI * p.hz * (if (k == 0) 1 - spread else 1 + spread) / RATE
                c[k] = cos(w); s[k] = sin(w)
                x[k] = cos(ph); y[k] = sin(ph)
            }
        }

        /** Adds samples [from] until [from] + out.size to [out]. */
        fun render(out: DoubleArray, from: Long) {
            for (i in out.indices) {
                val t = from + i
                if (t < start) continue
                val k = t - start
                // Held: fades in over 40 ms; struck: a 2 ms attack, then a quick drop and a long tail. Both fade out after [end].
                val env = if (held) p.amp / 2 * min(1.0, k / (RATE / 25.0)) else (ef + es) * min(1.0, k / (RATE / 500.0))
                if (!held) { ef *= df; es *= ds }
                // After [end], a smooth (raised-cosine) fade to silence
                val u = if (t < end) 0.0 else (t - end).toDouble() / release
                // A struck tone is gone once it has rung down (not during its attack, which starts at zero)
                if (u >= 1 || (!held && k > RATE / 100 && env < 1e-5)) { done = true; return }
                val fade = 0.5 * (1 + cos(PI * u))
                var v = 0.0
                for (j in 0..1) {
                    // Rotating phasor: a sine without calling sin() per sample
                    val nx = x[j] * c[j] - y[j] * s[j]; y[j] = x[j] * s[j] + y[j] * c[j]; x[j] = nx
                    v += y[j]
                }
                if (t and 1023L == 0L) for (j in 0..1) { val r = 1 / sqrt(x[j] * x[j] + y[j] * y[j]); x[j] *= r; y[j] *= r }
                out[i] += v * env * fade
            }
        }
    }

    private val lock = Any()
    private val voices = ArrayList<Voice>()
    /** Sample times of hammer blows still sounding. */
    private val hammers = ArrayList<Long>()
    /** Samples rendered so far on the current track; [origin]: where the current schedule starts. */
    private var clock = 0L
    private var origin = 0L
    private var track: AudioTrack? = null
    private var running = false

    /**
     * Plays [tones] (times from now) with a hammer at each time in [hammerTimes], replacing what sounds:
     * old tones fade out quickly, except a held partial that [tones] also holds from the start, which carries on.
     */
    fun play(tones: List<Tone>, hammerTimes: List<Double>) = synchronized(lock) {
        if (!running) start()
        origin = clock
        fun at(sec: Double) = if (sec.isInfinite()) Long.MAX_VALUE else origin + (sec * RATE).toLong()
        val fresh = tones.toMutableList()
        for (v in voices) {
            val same = fresh.firstOrNull { it.held && v.held && it.start == 0.0 && abs(it.p.hz - v.p.hz) < 1e-6 && v.end > clock }
            if (same != null) { fresh -= same; v.end = at(same.end); v.release = (same.release * RATE).toLong() } else cut(v)
        }
        for (t in fresh) voices += Voice(t.p, at(t.start), t.held, at(t.end), (t.release * RATE).toLong())
        hammers.clear()
        hammerTimes.forEach { hammers += at(it) }
    }

    /** Fades out everything playing. */
    fun stop() = synchronized(lock) {
        voices.forEach(::cut)
        hammers.clear()
    }

    /** Ends [v] now with a quick fade, or sooner if it was ending already. */
    private fun cut(v: Voice) {
        if (v.end > clock) { v.end = clock; v.release = minOf(v.release, FADE.toLong()) }
    }

    /** Seconds the current schedule has been heard (the speaker lags behind rendering). */
    fun elapsed(): Double = synchronized(lock) {
        val head = track?.playbackHeadPosition?.toLong() ?: return 0.0
        maxOf(0.0, (head - origin).toDouble() / RATE)
    }

    private fun start() {
        val size = maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), BLOCK * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size).build()
        track = t; clock = 0; origin = 0; running = true
        t.play()
        Thread({ loop(t) }, "tones").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    /** Renders block after block; writing blocks until the track has room, which paces it. Ends after half a second of silence. */
    private fun loop(t: AudioTrack) {
        val buf = DoubleArray(BLOCK)
        val pcm = ShortArray(BLOCK)
        val noise = Random(1)
        var lp = 0.0
        var idle = 0
        var quit = false
        while (true) {
            synchronized(lock) {
                buf.fill(0.0)
                for (v in voices) v.render(buf, clock)
                voices.removeAll { it.done }
                // The hammer's soft thump: a short burst of muffled noise
                for (h in hammers) for (i in buf.indices) {
                    val k = clock + i - h
                    if (k in 0 until RATE / 10) { lp += 0.2 * (noise.nextDouble(-1.0, 1.0) - lp); buf[i] += 0.12 * lp * exp(-k / (RATE * 0.012)) }
                }
                hammers.removeAll { clock - it > RATE / 10 }
                clock += BLOCK
                idle = if (voices.isEmpty() && hammers.isEmpty()) idle + BLOCK else 0
                if (idle > RATE / 2) { running = false; track = null; quit = true }
            }
            if (quit) break
            // Gentle limiter: nearly linear for a held note, so the louder strike stays louder; never clips
            for (i in buf.indices) pcm[i] = (tanh(buf[i] * 0.25) * 0.95 * Short.MAX_VALUE).toInt().toShort()
            t.write(pcm, 0, BLOCK)
        }
        runCatching { t.stop() }
        t.release()
    }
}
