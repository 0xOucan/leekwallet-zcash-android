package io.leekwallet.zcash.hw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeekBoardCompatTest {
    private val reading = LeekStatus.Working("Reading the account")

    @Test
    fun v010BoardIsNotPromisedAPress() {
        // v0.1.0 reports no level, which the bridge hands over as 1. It shares without asking.
        assertEquals(reading, viewKeyStatus(1))
    }

    @Test
    fun unreadLevelIsNotPromisedAPress() {
        assertEquals(reading, viewKeyStatus(null))
    }

    @Test
    fun boardsThatAskArePromisedAPress() {
        assertEquals(LeekStatus.ShareViewKey, viewKeyStatus(2))
        assertEquals(LeekStatus.ShareViewKey, viewKeyStatus(3))
    }

    @Test
    fun updateNoticeKeepsTheTextAndSaysWhereToGo() {
        val notice = boardUpdateNotice("Update your board: x.")
        assertTrue(notice.startsWith("Update your board: x."), notice)
        assertTrue(notice.contains("zcash.leekwallet.xyz"), notice)
    }
}
