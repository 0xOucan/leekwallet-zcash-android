package io.leekwallet.zcash.hw

/**
 * The LeekWallet device client, in Rust (host/leek-device via host/leek-android): framing, the
 * v2 pairing handshake, the encrypted session and the Zcash calls, the same code the zecz CLI
 * uses with the board. Kotlin supplies the USB link ([UsbIo]); every call blocks, so call from
 * Dispatchers.IO. Failures are thrown as IllegalStateException with the device's reason.
 */
object LeekDeviceNative {
    init {
        System.loadLibrary("leek_android")
    }

    external fun open(io: UsbIo): Long

    external fun close(handle: Long)

    /** The v2 handshake; [listener] gets the six digits the user compares with the device. */
    external fun pair(handle: Long, listener: PasskeyListener)

    /** Returns once the device is unlocked (the user enters the PIN on the device). */
    external fun ensureUnlocked(handle: Long)

    /**
     * zGetAccount as JSON: account, network, ufvk, ua, tAddress, seedFingerprint (hex), and
     * birthday when the device knows it.
     */
    external fun getAccount(handle: Long, account: Int, network: String): String

    /** zSetAccountMeta; true when the device stored it in flash. */
    external fun setBirthday(handle: Long, account: Int, birthday: Long): Boolean

    /** zShowAddress: the user confirms on the device that [expectedUa] is the one it shows. */
    external fun showAddress(handle: Long, account: Int, network: String, expectedUa: String)

    /** Upload [pczt], wait for the user's review on the device; returns the .zsig. */
    external fun sign(handle: Long, pczt: ByteArray, account: Int, network: String): ByteArray
}

/** A raw byte link to the device. */
interface UsbIo {
    fun write(data: ByteArray)

    /** Up to one transfer's worth of bytes, or null when nothing arrived within [timeoutMs]. */
    fun read(timeoutMs: Int): ByteArray?
}

fun interface PasskeyListener {
    fun onPasskey(passkey: String)
}

/** Applies a device .zsig to the PCZT that was sent to it (pczt crate Signer role, in Rust). */
object PcztSignatures {
    init {
        System.loadLibrary("leek_android")
    }

    /** @throws IllegalArgumentException when the .zsig is not for these bytes or a signature fails. */
    external fun apply(pczt: ByteArray, zsig: ByteArray): ByteArray
}
