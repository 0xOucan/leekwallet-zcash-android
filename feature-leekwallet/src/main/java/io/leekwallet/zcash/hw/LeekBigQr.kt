package io.leekwallet.zcash.hw

import androidx.compose.foundation.Canvas
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
 * A QR code for the board's camera, as docs/zcash/QR-BENCH.md says a phone should show it: on
 * the black page, as large as the window allows (the board's camera is fixed-focus and
 * low-resolution, so every pixel per module counts), with a 2-module white border. Error
 * correction L, the text in the alphanumeric mode (the SDK's frames are uppercase), whole
 * pixels per module. [longest] is the longest frame of the loop: the QR version is chosen once
 * for it, so the code keeps one size while it animates.
 */
@Composable
fun LeekBigQr(
    text: String,
    longest: Int
) {
    val config = LocalConfiguration.current
    val side = min((config.screenWidthDp - 8).dp, (config.screenHeightDp * 0.62f).dp)
    val version = remember(longest) { versionFor(longest) }
    val matrix =
        remember(text, version) {
            val hints =
                buildMap<EncodeHintType, Any> {
                    put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
                    if (version != null) put(EncodeHintType.QR_VERSION, version)
                }
            runCatching { Encoder.encode(text, ErrorCorrectionLevel.L, hints) }
                .getOrElse { Encoder.encode(text, ErrorCorrectionLevel.L) }
                .matrix
        }
    Canvas(Modifier.size(side)) {
        val modules = matrix.width + BORDER * 2
        val px = kotlin.math.floor(size.minDimension / modules)
        val origin = (size.minDimension - px * modules) / 2
        drawRect(Color.White, Offset(origin, origin), Size(px * modules, px * modules))
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() == 1) {
                    drawRect(
                        Color.Black,
                        Offset(origin + (x + BORDER) * px, origin + (y + BORDER) * px),
                        Size(px, px)
                    )
                }
            }
        }
    }
}

/** The QR version an alphanumeric text of [length] characters needs at correction L. */
private fun versionFor(length: Int): Int? {
    if (length <= 0) return null
    return runCatching {
        Encoder.encode("A".repeat(length), ErrorCorrectionLevel.L).version.versionNumber
    }.getOrNull()
}

/** White modules round the code, on the black page. */
private const val BORDER = 2
