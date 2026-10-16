package co.electriccoin.zcash.ui.screen.scan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import co.electriccoin.zcash.spackle.Twig

/**
 * Reading a QR code off a screen (LeekWallet fork).
 *
 * Always: meter and focus on the centre of the frame, where the scan window asks for the code,
 * rather than on the whole room. Harmless for a printed code, and it stops a small lit screen in
 * a dim room being exposed for the room.
 *
 * Screen mode, a switch (off by default): a short manual exposure, the remedy the LeekWallet
 * companion found for its OLED (referencialeekwallet app/src-tauri/src/camera.rs and
 * app/src/wc/qr.ts). An exposed-for-the-room OLED blooms its lit pixels over the dark modules.
 * 12.8 ms is two refreshes of the device's panel while it shows a QR (about 156 Hz); an OLED lights
 * one row at a time, so anything shorter records the unlit rows as dark bands. Off by default for
 * the same reason as there: phone cameras mostly read the panel fine untouched, while a
 * fixed-focus tablet camera may need it. A camera without manual exposure gets exposure
 * compensation near the bottom of its range instead.
 */
fun Camera.meterForQrCodes() {
    runCatching {
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, CENTRE_SIZE)
        cameraControl.startFocusAndMetering(
            FocusMeteringAction
                .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .disableAutoCancel()
                .build()
        )
    }.onFailure { Twig.warn(it) { "QR metering not applied" } }
}

@OptIn(ExperimentalCamera2Interop::class)
fun Camera.applyScreenMode(on: Boolean) {
    runCatching {
        val c2 = Camera2CameraControl.from(cameraControl)
        val exposure = cameraInfo.exposureState
        if (!on) {
            c2.clearCaptureRequestOptions()
            if (exposure.isExposureCompensationSupported) cameraControl.setExposureCompensationIndex(0)
            meterForQrCodes()
            return
        }
        val info = Camera2CameraInfo.from(cameraInfo)
        val manual =
            info
                .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val times = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isos = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (manual && times != null && isos != null) {
            c2.setCaptureRequestOptions(
                CaptureRequestOptions
                    .Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, times.clamp(EXPOSURE_NS))
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isos.clamp(ISO))
                    .build()
            )
            Twig.info { "QR screen mode: manual ${times.clamp(EXPOSURE_NS)} ns, ISO ${isos.clamp(ISO)}" }
        } else if (exposure.isExposureCompensationSupported) {
            val r = exposure.exposureCompensationRange
            cameraControl.setExposureCompensationIndex(r.lower + ((r.upper - r.lower) * EV_FRACTION).toInt())
            Twig.info { "QR screen mode: exposure compensation near the floor" }
        }
    }.onFailure { Twig.warn(it) { "QR screen mode not applied" } }
}

private const val PREFS = "leekwallet_scan"
private const val KEY_SCREEN_MODE = "screen_mode"

fun screenModePreference(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCREEN_MODE, false)

fun saveScreenModePreference(
    context: Context,
    on: Boolean
) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_MODE, on).apply()

private const val CENTRE_SIZE = 0.35f
private const val EXPOSURE_NS = 12_800_000L
private const val ISO = 400
private const val EV_FRACTION = 0.1f
