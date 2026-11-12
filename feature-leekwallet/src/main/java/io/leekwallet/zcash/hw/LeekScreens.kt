package io.leekwallet.zcash.hw

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
data class LeekVerifyArgs(
    val ua: String
)

/**
 * Check on board (QR): show [address] as a still QR for the board's Main menu >
 * Check address. No link: it works without a cable, and nothing comes back to the phone.
 */
@Serializable
data class LeekCheckAddressArgs(
    val address: String
)

/** Import a board account from its View key QR (ur:leek-zcash-accounts), with no link. */
@Serializable
data object LeekViewKeyImportArgs

/** What every LeekWallet screen shows: a title, the device status, an error, the buttons that apply. */
data class LeekScreenState(
    val title: String,
    val status: LeekStatus,
    val transport: LeekTransport = LeekTransport.USB,
    val transports: List<LeekTransport> = listOf(LeekTransport.USB, LeekTransport.BLUETOOTH),
    /** What to say before Start; null: the link's own instructions. */
    val idleText: String? = null,
    /** QR mode: the frame to show the device, or null. */
    val qrPart: String? = null,
    /** QR mode: the longest frame of the request, so the code keeps one size as it animates. */
    val qrLongest: Int = 0,
    /** True while the camera reads the device's QR. */
    val scanning: Boolean = false,
    val scanText: String = "",
    /** "12/40 parts (30 %)" while a scan is under way. */
    val qrProgress: String? = null,
    val onScanned: (String) -> Unit = {},
    val onScanSignature: () -> Unit = {},
    /** QR mode: the speed preset (bytes per fragment, ms per frame). */
    val qrPreset: LeekQrPreset = LeekQrPreset.DEFAULT,
    val onQrPreset: (LeekQrPreset) -> Unit = {},
    /** QR mode: the window brightness while the code shows, and the button that cycles it. */
    val qrBrightness: LeekQrBrightness = LeekQrBrightness.DEFAULT,
    val onQrBrightness: () -> Unit = {},
    /** QR mode, once Scan signature was pressed: show the same request again; null hides it. */
    val onShowCodeAgain: (() -> Unit)? = null,
    val onTransport: (LeekTransport) -> Unit = {},
    /** Signing: whether the device gets the compact PCZT, and a toggle; null hides it. */
    val compactPczt: Boolean? = null,
    val onCompactPczt: () -> Unit = {},
    /** Connect: import from the board's View key QR instead; null hides it. */
    val onViewKeyQr: (() -> Unit)? = null,
    /** True until the first attempt starts: the user picks the link, then presses Start. */
    val idle: Boolean = true,
    val onStart: () -> Unit = {},
    val done: String? = null,
    val error: String? = null,
    /** Suggests updating the board. Shown beside the rest, it never stops the operation. */
    val boardUpdate: String? = null,
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
    val vm =
        koinViewModel<LeekVerifyVM> {
            org.koin.core.parameter
                .parametersOf(ua)
        }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onClose() }
    LeekView(state)
}

@Composable
fun LeekViewKeyImportScreen() {
    val vm = koinViewModel<LeekViewKeyImportVM>()
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
            androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions()
        ) { granted -> if (granted.values.all { it }) state.onStart() }
    val start = {
        val missing =
            state.transport == LeekTransport.BLUETOOTH &&
                blePermissions.any {
                    androidx.core.content.ContextCompat
                        .checkSelfPermission(context, it) !=
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
            if (state.compactPczt != null && state.transport != LeekTransport.QR) {
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
                    Message(state.scanText, null)
                    Spacer(Modifier.height(8.dp))
                    Message(state.qrProgress ?: "Waiting for the first part...", ZashiColors.Text.textPrimary)
                    Spacer(Modifier.height(16.dp))
                    LeekCameraPermission { LeekQrScanner(onScanned = state.onScanned) }
                }

                state.idle -> {
                    Message(state.idleText ?: idleText(state.transport), null)
                }

                state.error != null -> {
                    Message(state.error, ZashiColors.Text.textError)
                }

                state.done != null -> {
                    Message(state.done, ZashiColors.Text.textPrimary)
                }

                else -> {
                    StatusView(state.status)
                }
            }
            if (state.boardUpdate != null && !state.idle && !state.scanning) {
                Spacer(Modifier.height(16.dp))
                Message(state.boardUpdate, ZashiColors.Text.textPrimary)
            }
            Spacer(Modifier.weight(1f))
            if (state.onViewKeyQr != null && (state.idle || state.error != null)) {
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = "No cable: import from the View key QR",
                    onClick = state.onViewKeyQr,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (state.onShowCodeAgain != null && (state.scanning || state.error != null)) {
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Show code again",
                    onClick = state.onShowCodeAgain,
                )
                Spacer(Modifier.height(8.dp))
            }
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

        LeekStatus.Unlock -> {
            Busy("Enter your PIN on the device if it asks.")
        }

        LeekStatus.ReviewOnDevice -> {
            Busy("Review the transaction on the device. Accept on its last page to sign.")
        }

        LeekStatus.ShareViewKey -> {
            Busy(
                "Confirm on the device to share this account's viewing key. It lets this phone " +
                    "see your balance and history, but not spend."
            )
        }

        is LeekStatus.Working -> {
            Busy(status.what + "...")
        }

        LeekStatus.Connecting, LeekStatus.Idle -> {
            Busy("Connecting to the LeekWallet...")
        }

        LeekStatus.CompareAddress -> {
            Message(
                "The device shows its address. Check it against the one on this phone's Receive " +
                    "screen, then confirm on the device.",
                null
            )
        }
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

private fun idleText(transport: LeekTransport): String =
    when (transport) {
        LeekTransport.BLUETOOTH -> {
            "On the device: Settings > Link > Bluetooth. Then press Start."
        }

        LeekTransport.QR -> {
            "Press Start to show the transaction as an animated QR. On the device: Scan, and point its " +
                "camera at this phone (3x zoom, about 30 cm away)."
        }

        LeekTransport.USB -> {
            "Connect the LeekWallet's USB port with a USB-OTG cable. Then press Start."
        }
    }

/** Asks for the camera once, then shows [content]; the scanners need it. */
@Composable
private fun LeekCameraPermission(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    fun granted() =
        androidx.core.content.ContextCompat
            .checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    val launcher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission()
        ) { allowed = it }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!allowed) launcher.launch(android.Manifest.permission.CAMERA)
    }
    if (allowed) {
        content()
    } else {
        Message("Allow the camera to read the board's QR.", ZashiColors.Text.textError)
    }
}

/**
 * QR mode, showing the PCZT: a black page with the code as large as the window allows, the
 * window kept on at the chosen brightness while it shows (both restored after), the presets,
 * the brightness button and the phone tip.
 */
@Composable
private fun LeekQrSendView(state: LeekScreenState) {
    LeekQrWindow(state.qrBrightness)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        LeekBigQr(state.qrPart.orEmpty(), state.qrLongest)
        Spacer(Modifier.weight(1f))
        Text(
            text = "On the device: Scan, and point its camera here. Accept on its last page, then Scan signature.",
            style = ZashiTypography.textSm,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = LEEK_QR_TIP,
            style = ZashiTypography.textXs,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LeekQrPreset.entries.forEach { p ->
                ZashiButton(
                    text = if (p == state.qrPreset) "● ${p.label}" else p.label,
                    onClick = { state.onQrPreset(p) },
                )
            }
        }
        Text(
            text = state.qrPreset.detail,
            style = ZashiTypography.textXs,
            color = Color.LightGray,
        )
        Spacer(Modifier.height(6.dp))
        ZashiButton(text = state.qrBrightness.label, onClick = state.onQrBrightness)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZashiButton(text = "Scan signature", onClick = state.onScanSignature)
            ZashiButton(text = "Cancel", onClick = state.onClose)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Check on board (QR): [address] as a still code on a black page, white quiet
 * zone, the same brightness button as QR signing, and what to do on the board.
 */
@Composable
fun LeekCheckAddressScreen(address: String) {
    val vm =
        koinViewModel<LeekCheckAddressVM> {
            org.koin.core.parameter
                .parametersOf(address)
        }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { vm.close() }
    LeekQrWindow(state.brightness)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = LEEK_CHECK_ADDRESS_TITLE,
            style = ZashiTypography.textMd,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        LeekBigQr(state.address, 0)
        Spacer(Modifier.weight(1f))
        Text(
            text = LEEK_CHECK_ADDRESS_HINT,
            style = ZashiTypography.textSm,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = LEEK_CHECK_ADDRESS_TIP,
            style = ZashiTypography.textXs,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZashiButton(text = state.brightness.label, onClick = vm::cycleBrightness)
            ZashiButton(text = "Done", onClick = vm::close)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * While composed: this window kept on, at [brightness] (the phone's own brightness for
 * [LeekQrBrightness.SYSTEM]), so the board's camera sees a steady code. The brightness and the
 * keep-on flag the window had before come back when it leaves: on a scan, an error, Cancel or Back.
 */
@Composable
private fun LeekQrWindow(brightness: LeekQrBrightness) {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window) {
        val before = window?.attributes?.screenBrightness
        val keptOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.let { w ->
                w.attributes =
                    w.attributes.apply {
                        screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                if (!keptOn) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    SideEffect {
        window?.let { w ->
            val wanted = brightness.level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            if (w.attributes.screenBrightness != wanted) {
                w.attributes = w.attributes.apply { screenBrightness = wanted }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
