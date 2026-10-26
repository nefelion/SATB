package com.nefelion.fourpartwriting.audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Listens to every connected MIDI keyboard: USB ones at once, Bluetooth ones once connected with [connectBluetooth].
 * [onNote] gets (note, true) for Note On and (note, false) for Note Off, on the main thread.
 */
class MidiIn(
    context: Context,
    private val onNote: (Int, Boolean) -> Unit,
    /** Names of the connected keyboards, whenever they change. */
    private val onDevices: (List<String>) -> Unit,
) {
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
        override fun onDeviceRemoved(info: MidiDeviceInfo) { open.remove(info.id)?.close(); changed() }
    }

    init {
        manager?.let { m ->
            @Suppress("DEPRECATION") m.devices.forEach(::connect)
            @Suppress("DEPRECATION") m.registerDeviceCallback(callback, main)
        }
    }

    /** Names of the connected keyboards. */
    private fun changed() = onDevices(open.values.map { d ->
        d.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"
    })

    /** Listens to all of a keyboard's output ports (what it plays). */
    private fun listen(dev: MidiDevice) {
        open[dev.info.id] = dev
        for (p in 0 until dev.info.outputPortCount) dev.openOutputPort(p)?.connect(receiver)
        changed()
    }

    private fun connect(info: MidiDeviceInfo) {
        // Bluetooth keyboards are opened through [connectBluetooth] only, or they would be listened to twice
        if (info.outputPortCount == 0 || info.id in open || info.type == MidiDeviceInfo.TYPE_BLUETOOTH) return
        manager?.openDevice(info, { dev -> dev?.let(::listen) }, main)
    }

    /** Opens a Bluetooth MIDI keyboard found by [scan]; [done] gets whether it worked. */
    fun connectBluetooth(device: BluetoothDevice, done: (Boolean) -> Unit) {
        val m = manager ?: return done(false)
        try {
            m.openBluetoothDevice(device, { dev -> if (dev != null) listen(dev); done(dev != null) }, main)
        } catch (_: SecurityException) { done(false) }
    }

    private var scanCallback: ScanCallback? = null

    /**
     * Looks for Bluetooth MIDI keyboards (they advertise the BLE MIDI service) for [SCAN_MS].
     * [found] gets each one once; [ended] is called when the search stops. Needs the Bluetooth permissions.
     */
    fun scan(context: Context, found: (BluetoothDevice, String) -> Unit, ended: (String?) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        if (adapter == null || !adapter.isEnabled) return ended("Turn on Bluetooth first.")
        val scanner = adapter.bluetoothLeScanner ?: return ended("Turn on Bluetooth first.")
        stopScan(context)
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!seen.add(result.device.address)) return
                val name = try { result.scanRecord?.deviceName ?: result.device.name } catch (_: SecurityException) { null }
                found(result.device, name ?: result.device.address)
            }
            override fun onScanFailed(errorCode: Int) { scanCallback = null; ended("Bluetooth search failed (error $errorCode).") }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (_: SecurityException) { return ended("Bluetooth permission is needed to find keyboards.") }
        scanCallback = cb
        main.postDelayed({ if (scanCallback === cb) { stopScan(context); ended(null) } }, SCAN_MS)
    }

    fun stopScan(context: Context) {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {}
    }

    fun close() {
        manager?.unregisterDeviceCallback(callback)
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    companion object {
        /** BLE MIDI service UUID (MIDI over Bluetooth Low Energy spec). */
        private val MIDI_SERVICE = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        private const val SCAN_MS = 15_000L
    }
}
