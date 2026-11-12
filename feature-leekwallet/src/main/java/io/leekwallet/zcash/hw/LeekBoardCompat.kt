package io.leekwallet.zcash.hw

/**
 * The first firmware level with the "Share view key?" prompt. v0.1.0 reports no level (it counts
 * as 1) and shares the viewing key without asking.
 */
private const val VIEW_KEY_PROMPT_COMPAT = 2

/**
 * What to say while the board answers zGetAccount. Only firmware known to ask gets the promise of
 * a press; [fwCompat] is null when the level could not be read.
 */
internal fun viewKeyStatus(fwCompat: Int?): LeekStatus =
    if (fwCompat != null && fwCompat >= VIEW_KEY_PROMPT_COMPAT) {
        LeekStatus.ShareViewKey
    } else {
        LeekStatus.Working("Reading the account")
    }

/** The suggestion for a board whose Features.firmwareOutdated is true. Not a block. */
internal fun boardUpdateNotice(updateText: String): String = "$updateText How to update: zcash.leekwallet.xyz"
