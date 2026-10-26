package com.nefelion.fourpartwriting.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Listens to every connected MIDI keyboard (USB, or Bluetooth already paired by the system).
 * [onNote] gets (note, true) for Note On and (note, false) for Note Off, on the main thread.
 */
class MidiIn(context: Context, private val onNote: (Int, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
        context.getSystemService(Context.MIDI_SERVICE) as MidiManager? else null
    private val open = HashMap<Int, MidiDevice>()

    private val receiver = object : MidiReceiver() {
        /** Last channel status byte: messages may leave it out (running status). */
        private var running = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) { i++; continue } // real-time byte (clock, active sensing)
                if (b == 0xF0) { while (i < end && msg[i].toInt() and 0xFF != 0xF7) i++; i++; running = 0; continue } // sysex
                val status = if (b >= 0x80) b.also { i++ } else running
                if (status in 0x80..0xEF) running = status else if (status >= 0xF0) { running = 0; continue }
                if (status == 0) { i++; continue } // stray data byte
                val kind = status and 0xF0
                val len = if (kind == 0xC0 || kind == 0xD0) 1 else 2
                if (i + len > end) return
                if (kind == 0x90 || kind == 0x80) {
                    val note = msg[i].toInt() and 0x7F
                    val on = kind == 0x90 && msg[i + 1].toInt() and 0x7F > 0 // velocity 0 = off
                    main.post { onNote(note, on) }
                }
                i += len
            }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = connect(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) { open.remove(info.id)?.close() }
    }

    init {
        manager?.let { m ->
            @Suppress("DEPRECATION") m.devices.forEach(::connect)
            @Suppress("DEPRECATION") m.registerDeviceCallback(callback, main)
        }
    }

    private fun connect(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || info.id in open) return
        manager?.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            open[info.id] = dev
            // A keyboard's "output" port is what it plays; listen to all of them
            for (p in 0 until info.outputPortCount) dev.openOutputPort(p)?.connect(receiver)
        }, main)
    }

    fun close() {
        manager?.unregisterDeviceCallback(callback)
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }
}
