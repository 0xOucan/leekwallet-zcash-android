package io.leekwallet.zcash.hw

import io.leekwallet.zcash.sdk.LeekException
import io.leekwallet.zcash.sdk.QrPartState
import io.leekwallet.zcash.sdk.QrProgress
import io.leekwallet.zcash.sdk.Recovery
import io.leekwallet.zcash.sdk.TransportFailure
import kotlin.test.Test
import kotlin.test.assertEquals
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
        listOf("100 %", "anti-flicker", "DC dimming", "exposure 0", "Big codes", "3x", "30 cm").forEach {
            assertTrue(LEEK_QR_TIP.contains(it), it)
        }
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
