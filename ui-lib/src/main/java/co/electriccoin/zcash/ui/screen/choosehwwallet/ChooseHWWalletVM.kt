package co.electriccoin.zcash.ui.screen.choosehwwallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.canConnectLeekWallet
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ChooseHWWalletVM(
    getWalletAccounts: GetWalletAccountsUseCase,
    private val navigationRouter: NavigationRouter,
    private val leekWalletNavigator: co.electriccoin.zcash.ui.common.leekwallet.LeekWalletNavigator,
) : ViewModel() {
    val state: StateFlow<ChooseHWWalletState> =
        getWalletAccounts
            .observe()
            .map { createState(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(getWalletAccounts.observe().value)
            )

    private fun createState(accounts: List<WalletAccount>?) =
        ChooseHWWalletState(
            title = stringRes(R.string.chooseHWWallet_title),
            subtitle = stringRes(R.string.chooseHWWallet_subtitle),
            cards =
                listOfNotNull(
                    HWWalletCardState(
                        image = imageRes(R.drawable.img_hw_wallet_leekwallet),
                        contentDescription = stringRes(R.string.accounts_leekwallet),
                        testTag = ChooseHWWalletTag.LEEKWALLET_CARD,
                        onClick = leekWalletNavigator::forwardToConnect,
                    ).takeIf { accounts.canConnectLeekWallet() },
                ),
            onBack = ::onBack,
        )

    private fun onBack() = navigationRouter.back()
}
