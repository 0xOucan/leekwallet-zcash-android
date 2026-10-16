package io.leekwallet.zcash.hw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Whole protocol frames over a link that delimits them (Bluetooth LE). */
interface BleIo {
    /** One frame: big-endian u16 length (type included), type, payload. */
    fun sendFrame(frame: ByteArray)

    /** The next whole frame, or null when none arrived within [timeoutMs]. */
    fun recvFrame(timeoutMs: Int): ByteArray?
}

/**
 * The LeekWallet board's GATT service (firmware src/ble.c, PROTOCOL.md 2): writes to RX carry
 * host-to-device chunks, notifications on TX carry device-to-host chunks. A chunk is one header
 * byte - bit 7 "more follows", bits 0-6 a sequence number from 0 per frame - then at most
 * MTU - 4 bytes of the frame. The mirror of firmware ble-chunk.c.
 *
 * The board advertises only when its link is set to Bluetooth (Settings > Link on the device).
 */
@SuppressLint("MissingPermission") // the screens hold BLUETOOTH_SCAN/CONNECT before calling
class LeekBleLink private constructor(
    private val gatt: BluetoothGatt,
    private val rx: BluetoothGattCharacteristic,
    private val callback: Callback,
) : BleIo, AutoCloseable {
    override fun sendFrame(frame: ByteArray) {
        val capacity = (callback.mtu - 3 - 1).coerceAtLeast(1)
        var offset = 0
        var seq = 0
        while (offset < frame.size) {
            val take = minOf(capacity, frame.size - offset)
            val more = offset + take < frame.size
            val chunk = ByteArray(take + 1)
            chunk[0] = ((if (more) 0x80 else 0) or (seq and 0x7f)).toByte()
            System.arraycopy(frame, offset, chunk, 1, take)
            callback.writeDone.clear()
            check(writeChunk(chunk)) { "Bluetooth write refused" }
            check(callback.writeDone.poll(WRITE_TIMEOUT_S, TimeUnit.SECONDS) == true) { "Bluetooth write failed" }
            offset += take
            seq++
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChunk(chunk: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rx.value = chunk
            gatt.writeCharacteristic(rx)
        }

    override fun recvFrame(timeoutMs: Int): ByteArray? =
        callback.frames.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)

    override fun close() {
        gatt.disconnect()
        gatt.close()
    }

    private class Callback : BluetoothGattCallback() {
        val connected = LinkedBlockingQueue<Boolean>()
        val discovered = LinkedBlockingQueue<Boolean>()
        val mtuDone = LinkedBlockingQueue<Int>()
        val descriptorDone = LinkedBlockingQueue<Boolean>()
        val writeDone = LinkedBlockingQueue<Boolean>()
        val frames = LinkedBlockingQueue<ByteArray>()

        @Volatile var mtu = 23
        private val assembling = ByteArrayOutputStream()
        private var nextSeq = 0

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            connected.offer(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED)
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int
        ) {
            discovered.offer(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(
            g: BluetoothGatt,
            newMtu: Int,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuDone.offer(newMtu)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorDone.offer(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeDone.offer(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Android 12 and older deliver the value on the characteristic")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onChunk(c, c.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onChunk(c, value)
        }

        private fun onChunk(
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (c.uuid != TX || value.isEmpty()) return
            val seq = value[0].toInt() and 0x7f
            val more = value[0].toInt() and 0x80 != 0
            if (seq != nextSeq) {
                // Out of step: drop the partial frame, as the device does.
                assembling.reset()
                nextSeq = 0
                if (seq != 0) return
            }
            assembling.write(value, 1, value.size - 1)
            nextSeq = (nextSeq + 1) and 0x7f
            if (!more) {
                frames.offer(assembling.toByteArray())
                assembling.reset()
                nextSeq = 0
            }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6c65656b-7761-6c6c-6574-000000000001")
        private val RX: UUID = UUID.fromString("6c65656b-7761-6c6c-6574-000000000002")
        private val TX: UUID = UUID.fromString("6c65656b-7761-6c6c-6574-000000000003")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val WRITE_TIMEOUT_S = 5L
        private const val STEP_TIMEOUT_S = 15L
        private const val WANTED_MTU = 517

        /** Scan for the LeekWallet service, connect to the first board found, set the link up. */
        fun scanAndConnect(
            context: Context,
            scanTimeoutMs: Long = 20_000
        ): LeekBleLink {
            val adapter =
                context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("This device has no Bluetooth")
            check(adapter.isEnabled) { "Turn Bluetooth on" }
            val found = LinkedBlockingQueue<BluetoothDevice>()
            val scanCallback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult
                    ) {
                        found.offer(result.device)
                    }
                }
            val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth scanning is unavailable")
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
            val device =
                try {
                    found.poll(scanTimeoutMs, TimeUnit.MILLISECONDS)
                } finally {
                    scanner.stopScan(scanCallback)
                } ?: error("No LeekWallet found over Bluetooth. On the device: Settings > Link > Bluetooth.")

            val cb = Callback()
            val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            try {
                check(cb.connected.poll(STEP_TIMEOUT_S, TimeUnit.SECONDS) == true) { "Bluetooth connection failed" }
                gatt.requestMtu(WANTED_MTU)
                cb.mtuDone.poll(STEP_TIMEOUT_S, TimeUnit.SECONDS)
                check(gatt.discoverServices()) { "Bluetooth service discovery failed" }
                check(cb.discovered.poll(STEP_TIMEOUT_S, TimeUnit.SECONDS) == true) { "Bluetooth service discovery failed" }
                val service = gatt.getService(SERVICE) ?: error("Not a LeekWallet (no service)")
                val rx = service.getCharacteristic(RX) ?: error("LeekWallet RX missing")
                val tx = service.getCharacteristic(TX) ?: error("LeekWallet TX missing")
                check(gatt.setCharacteristicNotification(tx, true)) { "Bluetooth notifications refused" }
                val cccd = tx.getDescriptor(CCCD) ?: error("LeekWallet TX has no CCCD")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                check(cb.descriptorDone.poll(STEP_TIMEOUT_S, TimeUnit.SECONDS) == true) {
                    "Bluetooth notifications failed"
                }
                return LeekBleLink(gatt, rx, cb)
            } catch (e: Throwable) {
                gatt.close()
                throw e
            }
        }
    }
}
