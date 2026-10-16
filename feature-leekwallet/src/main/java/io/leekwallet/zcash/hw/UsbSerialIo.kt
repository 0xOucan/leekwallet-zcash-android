package io.leekwallet.zcash.hw

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * The board's USB Serial/JTAG port as a CDC-ACM device, read and written with bulk transfers on
 * its data interface. No line coding or DTR/RTS is set: on the ESP32-S3 those lines reset the
 * chip, and the protocol does not need them.
 */
class UsbSerialIo private constructor(
    private val connection: UsbDeviceConnection,
    private val dataInterface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : UsbIo, AutoCloseable {
    private val buffer = ByteArray(inEp.maxPacketSize.coerceAtLeast(64) * 8)

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = connection.bulkTransfer(outEp, data, off, data.size - off, WRITE_TIMEOUT_MS)
            check(n > 0) { "USB write failed" }
            off += n
        }
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val n = connection.bulkTransfer(inEp, buffer, buffer.size, timeoutMs)
        return if (n > 0) buffer.copyOf(n) else null
    }

    override fun close() {
        connection.releaseInterface(dataInterface)
        connection.close()
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 2000

        /** Espressif's USB vendor ID (the S3's built-in USB Serial/JTAG). */
        const val ESPRESSIF_VID = 0x303A

        fun isLeekWallet(device: UsbDevice) = device.vendorId == ESPRESSIF_VID

        /** Opens [device]; the app must already hold USB permission for it. */
        fun open(manager: UsbManager, device: UsbDevice): UsbSerialIo {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_CDC_DATA) continue
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                if (inEp == null || outEp == null) continue
                val connection = checkNotNull(manager.openDevice(device)) { "cannot open the USB device" }
                check(connection.claimInterface(intf, true)) { "cannot claim the USB interface" }
                return UsbSerialIo(connection, intf, inEp, outEp)
            }
            error("no CDC data interface: is this the board's USB port?")
        }
    }
}
