package io.leekwallet.zcash.hw

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.electriccoin.zcash.ui.screen.scan.util.QrCodeAnalyzerImpl
import co.electriccoin.zcash.ui.screen.scankeystone.view.FramePosition

/**
 * A small camera view that reports every QR it reads, for the device's ur:leek-zsig. Uses the
 * wallet's own analyzer (ML Kit or zxing-cpp, per flavor), and meters for a screen: the device's
 * signature is on an OLED (see the scanner's screen mode).
 */
@Composable
fun LeekQrScanner(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val provider by produceState<ProcessCameraProvider?>(null) { value = ProcessCameraProvider.awaitInstance(context) }
    val view = remember { PreviewView(context) }
    DisposableEffect(provider) {
        val p = provider
        if (p != null) {
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            analysis.setAnalyzer(
                ContextCompat.getMainExecutor(context),
                QrCodeAnalyzerImpl(FramePosition(0f, 0f, 1f, 1f), onScanned)
            )
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
        onDispose { provider?.unbindAll() }
    }
    AndroidView(modifier = Modifier.size(320.dp), factory = { view })
}
