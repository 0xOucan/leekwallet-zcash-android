package co.electriccoin.zcash.ui.screen.choosehwwallet

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.leekwallet.LEEKWALLET_KEY_SOURCE
import co.electriccoin.zcash.ui.common.leekwallet.LeekWalletNavigator
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.canConnectHardwareWallet
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * New connections are LeekWallet only: the picker offers its card while no LeekWallet account
 * exists, whatever Keystone or Ledger accounts the wallet already holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChooseHWWalletVMTest {
    private val sdkAccount = Account.new(AccountUuid.new(ByteArray(16) { it.toByte() }))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun showsOnlyTheLeekWalletCardWhenNoHWWalletIsConnected() {
        val vm = vm(accounts = emptyList())

        assertEquals(
            listOf(ChooseHWWalletTag.LEEKWALLET_CARD),
            vm.state.value.cards
                .map { it.testTag }
        )
    }

    @Test
    fun existingKeystoneAndLedgerAccountsLeaveOnlyTheLeekWalletCard() {
        val vm = vm(accounts = listOf(keystoneAccount(), ledgerAccount()))

        assertEquals(
            listOf(ChooseHWWalletTag.LEEKWALLET_CARD),
            vm.state.value.cards
                .map { it.testTag }
        )
    }

    @Test
    fun aLeekWalletAccountHidesTheCard() {
        val vm = vm(accounts = listOf(leekWalletAccount()))

        assertEquals(emptyList(), vm.state.value.cards)
    }

    @Test
    fun aHardwareWalletCanBeConnectedUntilALeekWalletIs() {
        assertTrue(emptyList<WalletAccount>().canConnectHardwareWallet())
        assertTrue(listOf(keystoneAccount(), ledgerAccount()).canConnectHardwareWallet())
        assertFalse(listOf(leekWalletAccount()).canConnectHardwareWallet())
    }

    @Test
    fun theHeaderAndTheCardFollowTheConnectHardwareWalletFrame() {
        val state = vm(accounts = emptyList()).state.value

        assertEquals(stringRes(R.string.chooseHWWallet_title), state.title)
        assertEquals(stringRes(R.string.chooseHWWallet_subtitle), state.subtitle)
        assertEquals(listOf(imageRes(R.drawable.img_hw_wallet_leekwallet)), state.cards.map { it.image })
        assertEquals(listOf(stringRes(R.string.accounts_leekwallet)), state.cards.map { it.contentDescription })
    }

    @Test
    fun theCardRoutesToTheLeekWalletConnectFlow() {
        val leekWalletNavigator = mockk<LeekWalletNavigator>(relaxed = true)
        val vm = vm(accounts = emptyList(), leekWalletNavigator = leekWalletNavigator)

        vm.state.value.cards
            .single()
            .onClick()

        verify(exactly = 1) { leekWalletNavigator.forwardToConnect() }
    }

    private fun vm(
        accounts: List<WalletAccount>?,
        leekWalletNavigator: LeekWalletNavigator = mockk(relaxed = true),
    ): ChooseHWWalletVM {
        val getWalletAccounts =
            mockk<GetWalletAccountsUseCase> {
                every { observe() } returns MutableStateFlow(accounts)
            }
        return ChooseHWWalletVM(
            getWalletAccounts = getWalletAccounts,
            navigationRouter = mockk(relaxed = true),
            leekWalletNavigator = leekWalletNavigator,
        )
    }

    private fun keystoneAccount() =
        KeystoneAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = false,
        )

    private fun leekWalletAccount() =
        keystoneAccount().copy(
            sdkAccount =
                mockk {
                    every { keySource } returns LEEKWALLET_KEY_SOURCE
                }
        )

    private fun ledgerAccount() =
        LedgerAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = false,
            deviceIdentity = null,
            zip32AccountIndex = Zip32AccountIndex.new(0L),
        )
}
