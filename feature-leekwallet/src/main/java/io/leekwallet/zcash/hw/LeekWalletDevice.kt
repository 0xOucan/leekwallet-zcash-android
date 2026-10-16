package io.leekwallet.zcash.hw

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/** What the LeekWallet screens tell the user while a device session runs. */
sealed interface LeekStatus {
    data object Idle : LeekStatus

    data object Connecting : LeekStatus

    /** Compare these six digits with the device, then press ALLOW on it. */
    data class ComparePasskey(val passkey: String) : LeekStatus

    /** Enter the PIN on the device if it asks. */
    data object Unlock : LeekStatus

    /** Working with the device; [what] says on what. */
    data class Working(val what: String) : LeekStatus

    /** The transaction is on the device's screen: review it there. */
    data object ReviewOnDevice : LeekStatus
}

/** The account a LeekWallet device exports (zGetAccount). */
data class LeekAccountInfo(
    val ufvk: String,
    val ua: String,
    val seedFingerprintHex: String,
    val birthday: Long?,
)

/**
 * One USB-OTG session with the board per operation: find it, get permission, pair (the user
 * compares six digits), wait for the PIN, do the operation, close. The protocol itself is
 * [LeekDeviceNative].
 */
class LeekWalletDevice(
    private val context: Context,
) {
    private val mutableStatus = MutableStateFlow<LeekStatus>(LeekStatus.Idle)
    val status: StateFlow<LeekStatus> = mutableStatus.asStateFlow()

    val networkName: String
        get() = if (ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet) "test" else "main"

    /** Account 0 of the device: what the app imports as a watch-only, device-signed account. */
    suspend fun readAccount(): LeekAccountInfo =
        session { h ->
            mutableStatus.value = LeekStatus.Working("Reading the account")
            val json = JSONObject(LeekDeviceNative.getAccount(h, ACCOUNT, networkName))
            LeekAccountInfo(
                ufvk = json.getString("ufvk"),
                ua = json.getString("ua"),
                seedFingerprintHex = json.getString("seedFingerprint"),
                birthday = if (json.has("birthday")) json.getLong("birthday") else null,
            )
        }

    /**
     * Sends [pczt] (the signer view) to the device, waits for the user's review, and returns the
     * PCZT with the device's signatures applied - what the SDK combines with the proven one.
     */
    suspend fun sign(pczt: ByteArray): ByteArray =
        session { h ->
            mutableStatus.value = LeekStatus.ReviewOnDevice
            val zsig = LeekDeviceNative.sign(h, pczt, ACCOUNT, networkName)
            mutableStatus.value = LeekStatus.Working("Applying the signatures")
            PcztSignatures.apply(pczt, zsig)
        }

    fun reset() {
        mutableStatus.value = LeekStatus.Idle
    }

    private suspend fun <T> session(block: (Long) -> T): T =
        withContext(Dispatchers.IO) {
            mutableStatus.value = LeekStatus.Connecting
            val manager = context.getSystemService(UsbManager::class.java)
            val device =
                manager.deviceList.values.firstOrNull(UsbSerialIo::isLeekWallet)
                    ?: error("No LeekWallet found. Connect the board's USB port with a USB-OTG cable.")
            if (!manager.hasPermission(device)) {
                check(requestPermission(manager, device)) { "USB permission was not granted." }
            }
            UsbSerialIo.open(manager, device).use { io ->
                val handle = LeekDeviceNative.open(io)
                try {
                    LeekDeviceNative.pair(handle) { mutableStatus.value = LeekStatus.ComparePasskey(it) }
                    mutableStatus.value = LeekStatus.Unlock
                    LeekDeviceNative.ensureUnlocked(handle)
                    block(handle)
                } finally {
                    LeekDeviceNative.close(handle)
                }
            }
        }

    private suspend fun requestPermission(
        manager: UsbManager,
        device: UsbDevice
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent
                    ) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) {
                            cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        }
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(device, pending)
        }

    companion object {
        private const val ACCOUNT = 0
        private const val ACTION_USB_PERMISSION = "io.leekwallet.zcash.USB_PERMISSION"
    }
}
