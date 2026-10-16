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

/** What either screen shows: a title, the device status, an error, and the buttons that apply. */
data class LeekScreenState(
    val title: String,
    val status: LeekStatus,
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
private fun LeekView(state: LeekScreenState) {
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
            Spacer(Modifier.height(24.dp))
            when {
                state.error != null -> Message(state.error, ZashiColors.Text.textError)
                state.done != null -> Message(state.done, ZashiColors.Text.textPrimary)
                else -> StatusView(state.status)
            }
            Spacer(Modifier.weight(1f))
            state.onRetry?.takeIf { state.error != null }?.let {
                ZashiButton(modifier = Modifier.fillMaxWidth(), text = "Try again", onClick = it)
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
        LeekStatus.Connecting, LeekStatus.Idle ->
            Busy("Connect the LeekWallet's USB port to this phone with a USB-OTG cable.")
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
