package io.leekwallet.zcash.hw

import android.content.Context
import android.content.SharedPreferences
import io.leekwallet.zcash.sdk.LeekException
import io.leekwallet.zcash.sdk.QrPartState
import io.leekwallet.zcash.sdk.QrProgress
import io.leekwallet.zcash.sdk.Recovery
import io.leekwallet.zcash.sdk.TransportFailure
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeekQrTest {
    @Test
    fun phonesDefaultToBigCodes() {
        assertEquals(LeekQrPreset.BIG, LeekQrPreset.DEFAULT)
        assertEquals(25u, LeekQrPreset.BIG.fragmentLen)
        assertEquals(350L, LeekQrPreset.BIG.frameMs)
    }

    @Test
    fun theThreeBenchPresets() {
        assertEquals(
            listOf(25u to 350L, 40u to 350L, 50u to 500L),
            LeekQrPreset.entries.map { it.fragmentLen to it.frameMs }
        )
    }

    @Test
    fun anUnknownStoredPresetFallsBackToTheDefault() {
        assertEquals(LeekQrPreset.DEFAULT, LeekQrPreset.byName(null))
        assertEquals(LeekQrPreset.DEFAULT, LeekQrPreset.byName("FAST"))
        assertEquals(LeekQrPreset.SAFE, LeekQrPreset.byName("SAFE"))
    }

    @Test
    fun theTipNamesEverySetting() {
        listOf(
            "lower brightness first",
            "then a higher one",
            "anti-flicker",
            "DC dimming",
            "exposure 0",
            "Big codes",
            "3x",
            "30 cm"
        ).forEach {
            assertTrue(LEEK_QR_TIP.contains(it), it)
        }
        assertFalse(LEEK_QR_TIP.contains("100 %"), "the tip no longer asks for full brightness")
    }

    @Test
    fun theCheckAddressHintSaysWhatToDoOnTheBoard() {
        assertEquals(
            "On the board: Main menu → Check address, then scan this code. The board says whether " +
                "the address is yours and which index.",
            LEEK_CHECK_ADDRESS_HINT
        )
        listOf("lower brightness first", "anti-flicker", "DC dimming", "3x", "30 cm").forEach {
            assertTrue(LEEK_CHECK_ADDRESS_TIP.contains(it), it)
        }
    }

    @Test
    fun brightnessDefaultsToTheSystemLevel() {
        assertEquals(LeekQrBrightness.SYSTEM, LeekQrBrightness.DEFAULT)
        assertNull(LeekQrBrightness.SYSTEM.level)
    }

    @Test
    fun brightnessLevelsAndLabels() {
        assertEquals(listOf(null, 0.6f, 0.8f, 1f), LeekQrBrightness.entries.map { it.level })
        assertEquals(
            listOf("Brightness: system", "Brightness: 60 %", "Brightness: 80 %", "Brightness: 100 %"),
            LeekQrBrightness.entries.map { it.label }
        )
    }

    @Test
    fun brightnessCyclesBackToSystem() {
        assertEquals(LeekQrBrightness.P60, LeekQrBrightness.SYSTEM.next())
        assertEquals(LeekQrBrightness.P80, LeekQrBrightness.P60.next())
        assertEquals(LeekQrBrightness.P100, LeekQrBrightness.P80.next())
        assertEquals(LeekQrBrightness.SYSTEM, LeekQrBrightness.P100.next())
    }

    @Test
    fun anUnknownStoredBrightnessFallsBackToSystem() {
        assertEquals(LeekQrBrightness.SYSTEM, LeekQrBrightness.byName(null))
        assertEquals(LeekQrBrightness.SYSTEM, LeekQrBrightness.byName("FULL"))
        assertEquals(LeekQrBrightness.P80, LeekQrBrightness.byName("P80"))
    }

    @Test
    fun theBrightnessIsRememberedInTheDevicePreferences() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences("leekwallet_device", Context.MODE_PRIVATE) } returns prefs
        val device = LeekWalletDevice(context)

        every { prefs.getString("qr_brightness", null) } returns null
        assertEquals(LeekQrBrightness.SYSTEM, device.qrBrightness)
        every { prefs.getString("qr_brightness", null) } returns "P60"
        assertEquals(LeekQrBrightness.P60, device.qrBrightness)

        device.qrBrightness = LeekQrBrightness.P100
        verify { editor.putString("qr_brightness", "P100") }
        verify { editor.apply() }
    }

    @Test
    fun progressLabel() {
        assertNull(QrProgress(QrPartState.IGNORED, 0u, 0u, 0u, null).label())
        assertEquals("3/12 parts (25 %)", QrProgress(QrPartState.ACCEPTED, 3u, 12u, 25u, null).label())
    }

    @Test
    fun sdkErrorsShowTheirReason() {
        val reason = "Update your app: it built this transaction for network rules that have ended. Nothing was signed."
        val e = LeekException.Pczt(520u, Recovery.DO_NOT_RETRY, reason)
        assertEquals(reason, e.leekMessage())
    }

    @Test
    fun reconnectErrorsSaySo() {
        val e = LeekException.Link(102u, Recovery.RECONNECT, "the link dropped")
        assertEquals("The link dropped. Reconnect the board and try again.", e.leekMessage())
    }

    @Test
    fun transportFailuresAreWords() {
        assertEquals("No LeekWallet found.", TransportFailure.NotFound().leekMessage())
        assertEquals("The link to the LeekWallet dropped.", TransportFailure.Disconnected().leekMessage())
    }

    @Test
    fun otherErrorsKeepTheirMessage() {
        val text = "USB permission was not granted."
        assertEquals(text, IllegalStateException(text).leekMessage())
    }

    @Test
    fun seedFingerprintHex() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
    }
}
