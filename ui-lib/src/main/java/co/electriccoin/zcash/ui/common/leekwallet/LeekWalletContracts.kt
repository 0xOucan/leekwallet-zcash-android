package co.electriccoin.zcash.ui.common.leekwallet

import androidx.navigation.NavGraphBuilder

/**
 * The seam between ui-lib and the feature-leekwallet module (the LeekWallet DIY Zcash hardware
 * signer, over USB OTG), shaped like LedgerContracts.kt: ui-lib talks only to these, and the app
 * module wires the implementations in through Koin.
 *
 * A LeekWallet account is a [co.electriccoin.zcash.ui.common.model.KeystoneAccount] whose key
 * source is [LEEKWALLET_KEY_SOURCE]: watch-only in the app, spend authority on the device, the
 * same PCZT flow as Keystone. Only connecting and signing differ, and those live behind these
 * contracts.
 */
const val LEEKWALLET_KEY_SOURCE = "leekwallet"

/** Routes from the shared flows into the LeekWallet screens. */
interface LeekWalletNavigator {
    /** Starts connecting a LeekWallet (the Choose Hardware Wallet entry point). */
    fun forwardToConnect()

    /** Opens the LeekWallet sign sheet for the pending Keystone-pipeline proposal. */
    fun forwardToSign()

    /** Asks the device to show its address, so the user checks [ua] against it there. */
    fun forwardToVerifyAddress(ua: String)
}

/** Installs the LeekWallet destinations into the wallet nav graph. */
interface LeekWalletNavContributor {
    fun contribute(navGraphBuilder: NavGraphBuilder)
}
