package io.leekwallet.zcash.hw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
data object LeekConnectArgs

@Serializable
data object LeekSignArgs

/** Verify on the device that [ua] is the device's own address. */
@kotlinx.serialization.Serializable
data class LeekVerifyArgs(val ua: String)

/** What either screen shows: a title, the device status, an error, and the buttons that apply. */
data class LeekScreenState(
    val title: String,
    val status: LeekStatus,
    val transport: LeekTransport = LeekTransport.USB,
    val transports: List<LeekTransport> = listOf(LeekTransport.USB, LeekTransport.BLUETOOTH),
    /** QR mode: the frame to show the device, or null. */
    val qrPart: String? = null,
    /** QR mode: true while the camera reads the device's signature QR. */
    val scanning: Boolean = false,
    val onScanned: (String) -> Unit = {},
    val onScanSignature: () -> Unit = {},
    /** QR mode: bytes per fragment and milliseconds per frame, and steppers for them. */
    val qrFragment: Int = 50,
    val qrFrameMs: Long = 500,
    val onQrFragment: () -> Unit = {},
    val onQrSpeed: () -> Unit = {},
    val onTransport: (LeekTransport) -> Unit = {},
    /** Signing: whether the device gets the compact PCZT, and a toggle; null hides it. */
    val compactPczt: Boolean? = null,
    val onCompactPczt: () -> Unit = {},
    /** True until the first attempt starts: the user picks the link, then presses Start. */
    val idle: Boolean = true,
    val onStart: () -> Unit = {},
    val done: String? = null,
    val error: String? = null,
    val onRetry: (() -> Unit)? = null,
    val onClose: () -> Unit,
)

@Composable
fun LeekConnectScreen() {
    val vm = koinViewModel<LeekConnectVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onClose() }
    LeekView(state)
}

@Composable
fun LeekSignScreen() {
    val vm = koinViewModel<LeekSignVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onClose() }
    LeekView(state)
}

@Composable
fun LeekVerifyScreen(ua: String) {
    val vm = koinViewModel<LeekVerifyVM> { org.koin.core.parameter.parametersOf(ua) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onClose() }
    LeekView(state)
}

@Composable
private fun LeekView(state: LeekScreenState) {
    val blePermissions =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { granted -> if (granted.values.all { it }) state.onStart() }
    val start = {
        val missing =
            state.transport == LeekTransport.BLUETOOTH &&
                blePermissions.any {
                    androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
        if (missing) permissionLauncher.launch(blePermissions) else state.onStart()
    }
    if (state.qrPart != null && !state.scanning && state.error == null) {
        LeekQrSendView(state)
        return
    }
    BlankBgScaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().scaffoldPadding(padding).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.title,
                style = ZashiTypography.header6,
                color = ZashiColors.Text.textPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.transports.forEach { t ->
                    ZashiButton(
                        text = if (t == state.transport) "● ${t.label}" else t.label,
                        enabled = state.idle || state.error != null,
                        onClick = { state.onTransport(t) },
                    )
                }
            }
            if (state.compactPczt != null) {
                Spacer(Modifier.height(8.dp))
                ZashiButton(
                    text = if (state.compactPczt) "PCZT: compact" else "PCZT: full",
                    enabled = state.idle || state.error != null,
                    onClick = state.onCompactPczt,
                )
            }
            Spacer(Modifier.height(24.dp))
            when {
                state.scanning -> {
                    Message("Point the camera at the signature QR on the device.", null)
                    Spacer(Modifier.height(16.dp))
                    LeekQrScanner(onScanned = state.onScanned)
                }
                state.idle -> Message(
                    if (state.transport == LeekTransport.BLUETOOTH) {
                        "On the device: Settings > Link > Bluetooth. Then press Start."
                    } else {
                        "Connect the LeekWallet's USB port with a USB-OTG cable. Then press Start."
                    },
                    null
                )
                state.error != null -> Message(state.error, ZashiColors.Text.textError)
                state.done != null -> Message(state.done, ZashiColors.Text.textPrimary)
                else -> StatusView(state.status)
            }
            Spacer(Modifier.weight(1f))
            if (state.idle || state.error != null) {
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (state.idle) "Start" else "Try again",
                    onClick = { start() }
                )
                Spacer(Modifier.height(8.dp))
            }
            ZashiButton(
                modifier = Modifier.fillMaxWidth(),
                text = if (state.done != null) "Done" else "Cancel",
                onClick = state.onClose,
            )
        }
    }
}

@Composable
private fun StatusView(status: LeekStatus) {
    when (status) {
        is LeekStatus.ComparePasskey -> {
            Message("Check that the device shows these six digits, then press ALLOW on it.", null)
            Spacer(Modifier.height(16.dp))
            Text(
                text = status.passkey.chunked(3).joinToString(" "),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = ZashiColors.Text.textPrimary,
            )
        }
        LeekStatus.Unlock -> Busy("Enter your PIN on the device if it asks.")
        LeekStatus.ReviewOnDevice ->
            Busy("Review the transaction on the device. Accept on its last page to sign.")
        is LeekStatus.Working -> Busy(status.what + "...")
        LeekStatus.Connecting, LeekStatus.Idle -> Busy("Connecting to the LeekWallet...")
        LeekStatus.CompareAddress ->
            Message(
                "The device shows its address. Check it against the one on this phone's Receive " +
                    "screen, then confirm on the device.",
                null
            )
    }
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Message(text, null)
}

@Composable
private fun Message(
    text: String,
    color: androidx.compose.ui.graphics.Color?,
) {
    Text(
        text = text,
        style = ZashiTypography.textMd,
        color = color ?: ZashiColors.Text.textTertiary,
        textAlign = TextAlign.Center,
    )
}


/** QR mode, showing the PCZT: the code as large as the screen allows, and the few controls. */
@Composable
private fun LeekQrSendView(state: LeekScreenState) {
    BlankBgScaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().scaffoldPadding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "On the device: Scan, and point its camera here. Accept on its last page, then Scan signature.",
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            LeekBigQr(state.qrPart.orEmpty())
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZashiButton(text = "${state.qrFragment} B", onClick = state.onQrFragment)
                ZashiButton(text = "${state.qrFrameMs} ms", onClick = state.onQrSpeed)
                ZashiButton(text = "Scan signature", onClick = state.onScanSignature)
                ZashiButton(text = "Cancel", onClick = state.onClose)
            }
        }
    }
}
