package io.leekwallet.zcash.hw

import io.leekwallet.zcash.sdk.LeekException
import io.leekwallet.zcash.sdk.QrProgress
import io.leekwallet.zcash.sdk.Recovery
import io.leekwallet.zcash.sdk.TransportFailure

/**
 * The QR speed presets a phone offers (docs/zcash/QR-BENCH.md, "Signing with a phone"): the
 * board's fixed-focus camera reads a phone held about 30 cm away at 3x zoom best with big
 * codes, so [BIG] is the default here. [SAFE] is the SDK's qrDefaults(), measured from a
 * computer screen at 2x.
 */
@Suppress("MagicNumber") // the bench's measured settings, named by the entries
enum class LeekQrPreset(
    val label: String,
    val fragmentLen: UInt,
    val frameMs: Long,
) {
    BIG("Big codes", 25u, 350),
    MEDIUM("40 B", 40u, 350),
    SAFE("50 B", 50u, 500);

    val detail: String get() = "$fragmentLen B / $frameMs ms"

    companion object {
        val DEFAULT = BIG

        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The window brightness while a code is on screen for the board's camera. [SYSTEM] leaves the
 * phone's own brightness alone (the default): forced full brightness washed the code out on an
 * OLED phone, so the user picks a level instead. [level] is the window's screenBrightness, null
 * for no override.
 *
 * TODO(leekwallet-sdk): take the levels and the default from the SDK's qr_display_hints()
 * brightness fields once it has them.
 */
@Suppress("MagicNumber") // the levels, named by the entries
enum class LeekQrBrightness(
    val level: Float?,
) {
    SYSTEM(null),
    P60(0.6f),
    P80(0.8f),
    P100(1f);

    /** "Brightness: system", "Brightness: 60 %". */
    val label: String
        get() = "Brightness: " + if (level == null) "system" else "${(level * 100).toInt()} %"

    /** The level after this one, round to [SYSTEM] after 100 %. */
    fun next(): LeekQrBrightness = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SYSTEM

        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Shown beside the animated code.
 *
 * TODO(leekwallet-sdk): take this from the SDK's qr_display_hints() once it has them.
 */
const val LEEK_QR_TIP =
    "If the board finds the code but doesn't read it, try a lower brightness first, then a " +
        "higher one. OLED phone: turn on anti-flicker/DC dimming. Board exposure 0, Big codes, " +
        "board at 3x about 30 cm away."

/**
 * Shown beside the address code of Check on board (experimental).
 *
 * TODO(leekwallet-sdk): take this from the SDK's address-check hint once it has one.
 */
const val LEEK_CHECK_ADDRESS_HINT =
    "On the board: Main menu → Check address, then scan this code. The board says whether " +
        "the address is yours and which index."

/** Beside the address code: the camera tips that apply to a still code. */
const val LEEK_CHECK_ADDRESS_TIP =
    "If the board finds the code but doesn't read it, try a lower brightness first, then a " +
        "higher one. OLED phone: turn on anti-flicker/DC dimming. Board at 3x about 30 cm away."

/** "12/40 parts (30 %)" for a scan in progress, or null before its first part. */
internal fun QrProgress.label(): String? =
    if (total == 0u) null else "$received/$total parts ($percent %)"

/**
 * What to show the user for [this]: the SDK's fixed sentence for a LeekException (no amounts,
 * keys or addresses in it), a word for a link failure, else the message.
 */
internal fun Throwable.leekMessage(): String =
    when (this) {
        is LeekException -> {
            val (reason, recovery) = reasonAndRecovery()
            when (recovery) {
                Recovery.RECONNECT -> "$reason Reconnect the board and try again."
                else -> reason
            }
        }

        is TransportFailure.NotFound -> {
            "No LeekWallet found."
        }

        is TransportFailure.PermissionDenied -> {
            "Permission to reach the LeekWallet was refused."
        }

        is TransportFailure -> {
            "The link to the LeekWallet dropped."
        }

        else -> {
            message ?: toString()
        }
    }

private fun LeekException.reasonAndRecovery(): Pair<String, Recovery> =
    when (this) {
        is LeekException.Link -> reason to recovery
        is LeekException.Session -> reason to recovery
        is LeekException.User -> reason to recovery
        is LeekException.Account -> reason to recovery
        is LeekException.Pczt -> reason to recovery
        is LeekException.Signature -> reason to recovery
        is LeekException.Storage -> reason to recovery
        is LeekException.Device -> reason to recovery
    }.let { (r, rec) -> r.replaceFirstChar { it.uppercase() }.withStop() to rec }

private fun String.withStop() = if (endsWith('.') || endsWith('!') || endsWith('?')) this else "$this."
