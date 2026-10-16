package io.leekwallet.zcash.hw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code for the board's camera to read: as large as the screen allows, the way the LeekWallet
 * companion draws its eth-sign-request (min(96 % of the width, 70 % of the height)): the board's
 * camera is fixed-focus and low-resolution, so every pixel per module counts. Error correction L,
 * the least modules for the text; whole pixels per module; a four-module quiet zone; black on
 * white. The wallet's ZashiQr (correction M, a framed card) suits a phone reading an address.
 */
@Composable
fun LeekBigQr(text: String) {
    val config = LocalConfiguration.current
    val side = min((config.screenWidthDp * 0.96f).dp, (config.screenHeightDp * 0.70f).dp)
    val matrix =
        remember(text) {
            Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")).matrix
        }
    BoxWithConstraints {
        Canvas(Modifier.size(side)) {
            val modules = matrix.width + QUIET * 2
            val px = kotlin.math.floor(size.minDimension / modules)
            val origin = (size.minDimension - px * modules) / 2
            drawRect(Color.White, Offset(origin, origin), Size(px * modules, px * modules))
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix.get(x, y).toInt() == 1) {
                        drawRect(
                            Color.Black,
                            Offset(origin + (x + QUIET) * px, origin + (y + QUIET) * px),
                            Size(px, px)
                        )
                    }
                }
            }
        }
    }
}

private const val QUIET = 4
