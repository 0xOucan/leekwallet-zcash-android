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
import io.leekwallet.zcash.sdk.AccountMeta
import io.leekwallet.zcash.sdk.AddressCheck
import io.leekwallet.zcash.sdk.LeekDevice
import io.leekwallet.zcash.sdk.LinkKind
import io.leekwallet.zcash.sdk.Network
import io.leekwallet.zcash.sdk.PairingListener
import io.leekwallet.zcash.sdk.SignListener
import io.leekwallet.zcash.sdk.SignProgress
import io.leekwallet.zcash.sdk.SignerView
import io.leekwallet.zcash.sdk.applyZsig
import io.leekwallet.zcash.sdk.boardUpdateText
import io.leekwallet.zcash.sdk.preflight
import io.leekwallet.zcash.sdk.prepareForSigner
import io.leekwallet.zcash.sdk.signerViewBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** How the phone reaches the board. */
enum class LeekTransport(
    val label: String
) {
    USB("USB"),
    BLUETOOTH("Bluetooth"),

    /** Signing only: the PCZT and the signatures cross as QR codes, no link at all. */
    QR("QR"),
}

/** What the LeekWallet screens tell the user while a device session runs. */
sealed interface LeekStatus {
    data object Idle : LeekStatus

    data object Connecting : LeekStatus

    /** Compare these six digits with the device, then press ALLOW on it. */
    data class ComparePasskey(
        val passkey: String
    ) : LeekStatus

    /** Enter the PIN on the device if it asks. */
    data object Unlock : LeekStatus

    /** Working with the device; [what] says on what. */
    data class Working(
        val what: String
    ) : LeekStatus

    /** The transaction is on the device's screen: review it there. */
    data object ReviewOnDevice : LeekStatus

    /** The device shows its address: compare it with the app's, answer on the device. */
    data object CompareAddress : LeekStatus

    /**
     * zGetAccount: the device asks before it shares the viewing key (once per session), and
     * answers only after OK on it.
     */
    data object ShareViewKey : LeekStatus
}

/** The account a LeekWallet device exports (zGetAccount, or its View key QR). */
data class LeekAccountInfo(
    val ufvk: String,
    val ua: String?,
    val seedFingerprintHex: String,
    val index: Long,
    val birthday: Long?,
)

/** The board account that signs: what the SDK checks the PCZT and the board's seed against. */
class LeekSigner(
    val seedFingerprint: ByteArray,
    val index: UInt,
    val ufvk: String,
)

/**
 * A QR signing request: [prepared] is the wallet's PCZT with every derivation the board needs,
 * [sent] the signer view shown to the board. The board's .zsig applies to both.
 */
class LeekQrRequest(
    val prepared: ByteArray,
    val sent: ByteArray,
)

/**
 * One session with the board per operation, through the LeekWallet SDK (LeekDevice): find it
 * over USB-OTG or Bluetooth LE, pair (the user compares six digits on every connection: no host
 * is remembered), wait for the PIN, do the operation, close. The links are ours ([UsbSerialIo],
 * [LeekBleLink]); framing, the encrypted session, preflight and signature checks are the SDK's.
 */
class LeekWalletDevice(
    private val context: Context,
) {
    private val mutableStatus = MutableStateFlow<LeekStatus>(LeekStatus.Idle)
    val status: StateFlow<LeekStatus> = mutableStatus.asStateFlow()

    private val mutableBoardUpdate = MutableStateFlow<String?>(null)

    /** A suggestion to update the board, once this session has read its firmware level. */
    val boardUpdate: StateFlow<String?> = mutableBoardUpdate.asStateFlow()

    /** The board's firmware level in this session; null when it could not be read. */
    @Volatile
    private var fwCompat: Int? = null

    /** The running session's device, for [cancel]. */
    @Volatile
    private var current: LeekDevice? = null

    @Volatile
    private var cancelRequested = false

    private val prefs = context.getSharedPreferences("leekwallet_device", Context.MODE_PRIVATE)

    /** The link to use, remembered between sessions. */
    var transport: LeekTransport
        get() =
            runCatching { LeekTransport.valueOf(prefs.getString(KEY_TRANSPORT, null)!!) }
                .getOrDefault(LeekTransport.USB)
        set(value) = prefs.edit().putString(KEY_TRANSPORT, value.name).apply()

    /** Send the device the compact signer view (half the bytes). Off = the full view. */
    var compactPczt: Boolean
        get() = prefs.getBoolean(KEY_COMPACT, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT, value).apply()

    /** The QR speed preset, remembered between sessions. */
    var qrPreset: LeekQrPreset
        get() = LeekQrPreset.byName(prefs.getString(KEY_QR_PRESET, null))
        set(value) = prefs.edit().putString(KEY_QR_PRESET, value.name).apply()

    private val view: SignerView
        get() = if (compactPczt) SignerView.COMPACT else SignerView.FULL

    val network: Network
        get() = if (ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet) Network.TEST else Network.MAIN

    /** Account 0 of the device: what the app imports as a watch-only, device-signed account. */
    suspend fun readAccount(): LeekAccountInfo =
        session { d ->
            mutableStatus.value = viewKeyStatus(fwCompat)
            val a = d.getAccount(ACCOUNT, network)
            LeekAccountInfo(
                ufvk = a.ufvk,
                ua = a.ua,
                seedFingerprintHex = a.seedFingerprint.toHex(),
                index = a.index.toLong(),
                birthday = a.birthday?.toLong(),
            )
        }

    /**
     * Signs [pczt] (the wallet's redacted signer PCZT) with the board: preflight, upload of the
     * signer view, the user's review on the device, every signature verified and applied. Returns
     * [pczt] with the signatures, what the Keystone pipeline combines with the proven one.
     */
    suspend fun sign(
        pczt: ByteArray,
        signer: LeekSigner
    ): ByteArray =
        session { d ->
            val prepared = prepareForSigner(pczt, signer.ufvk, signer.seedFingerprint, signer.index)
            d.signPczt(prepared, signer.meta(network), view, true, signListener).signedPczt
        }

    /**
     * QR: checks [pczt] for [signer] as the board will, and returns what to show it. Nothing goes
     * over a link; the board's ur:leek-zsig reply goes to [applyQrSignature].
     */
    fun qrRequest(
        pczt: ByteArray,
        signer: LeekSigner
    ): LeekQrRequest {
        val prepared = prepareForSigner(pczt, signer.ufvk, signer.seedFingerprint, signer.index)
        preflight(prepared, signer.meta(network), null)
        return LeekQrRequest(prepared, signerViewBytes(prepared, view))
    }

    /** The PCZT of [request] with the board's [zsig] applied, every signature verified. */
    fun applyQrSignature(
        request: LeekQrRequest,
        zsig: ByteArray
    ): ByteArray = applyZsig(request.prepared, request.sent, zsig)

    /** zShowAddress: the device shows its address and the user answers there. */
    suspend fun verifyAddress(
        ua: String,
        index: UInt
    ): AddressCheck =
        session { d ->
            mutableStatus.value = LeekStatus.CompareAddress
            d.showAddress(index, network, ua)
        }

    /** Stops the running session (any thread); its call ends with the SDK's "cancelled". */
    fun cancel() {
        cancelRequested = true
        current?.cancel()
    }

    fun reset() {
        mutableStatus.value = LeekStatus.Idle
        mutableBoardUpdate.value = null
    }

    /** Listeners run on the device thread: they only post state, and never throw. */
    private val pairingListener =
        object : PairingListener {
            override fun onPairingCode(code: String) {
                mutableStatus.value = LeekStatus.ComparePasskey(code)
            }
        }

    private val signListener =
        object : SignListener {
            override fun onProgress(progress: SignProgress) {
                mutableStatus.value =
                    when (progress) {
                        SignProgress.Checking -> {
                            LeekStatus.Working("Checking the transaction")
                        }

                        is SignProgress.Uploading -> {
                            val pct = if (progress.total == 0uL) 0uL else progress.sent * 100uL / progress.total
                            LeekStatus.Working("Sending it to the device ($pct %)")
                        }

                        SignProgress.ReviewOnDevice -> {
                            LeekStatus.ReviewOnDevice
                        }

                        SignProgress.Applying -> {
                            LeekStatus.Working("Checking and applying the signatures")
                        }
                    }
            }
        }

    private suspend fun <T> session(block: (LeekDevice) -> T): T =
        withContext(Dispatchers.IO) {
            cancelRequested = false
            mutableStatus.value = LeekStatus.Connecting
            mutableBoardUpdate.value = null
            fwCompat = null
            if (transport == LeekTransport.BLUETOOTH) {
                LeekBleLink.scanAndConnect(context).use { link ->
                    return@withContext run(LeekDevice(link, LinkKind.FRAMES), block)
                }
            }
            val manager = context.getSystemService(UsbManager::class.java)
            val device =
                manager.deviceList.values.firstOrNull(UsbSerialIo::isLeekWallet)
                    ?: error("No LeekWallet found. Connect the board's USB port with a USB-OTG cable.")
            if (!manager.hasPermission(device)) {
                check(requestPermission(manager, device)) { "USB permission was not granted." }
            }
            UsbSerialIo.open(manager, device).use { io -> run(LeekDevice(io, LinkKind.STREAM), block) }
        }

    /** Pair (afresh, every time), wait for the PIN, run [block], close: the same on every link. */
    private fun <T> run(
        device: LeekDevice,
        block: (LeekDevice) -> T
    ): T =
        device.use { d ->
            current = d
            try {
                if (cancelRequested) d.cancel()
                d.pair(pairingListener)
                mutableStatus.value = LeekStatus.Unlock
                d.ensureUnlocked()
                readBoardStatus(d)
                block(d)
            } finally {
                current = null
            }
        }

    /** A suggestion only: when the level cannot be read, the operation still goes ahead. */
    private fun readBoardStatus(device: LeekDevice) {
        val features = runCatching { device.getFeatures() }.getOrNull() ?: return
        fwCompat = features.fwCompat?.toInt() ?: 1
        if (features.firmwareOutdated) {
            mutableBoardUpdate.value = boardUpdateNotice(boardUpdateText())
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
        private const val ACCOUNT = 0u
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_COMPACT = "compact_pczt"
        private const val KEY_QR_PRESET = "qr_preset"
        private const val ACTION_USB_PERMISSION = "io.leekwallet.zcash.USB_PERMISSION"
    }
}

private fun LeekSigner.meta(network: Network) = AccountMeta(seedFingerprint, index, network, ufvk)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
