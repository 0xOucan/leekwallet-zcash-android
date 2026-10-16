package io.leekwallet.zcash.hw

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.leekwallet.LeekWalletNavContributor
import co.electriccoin.zcash.ui.common.leekwallet.LeekWalletNavigator
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

class LeekWalletNavigatorImpl(
    private val navigationRouter: NavigationRouter,
) : LeekWalletNavigator {
    override fun forwardToConnect() = navigationRouter.forward(LeekConnectArgs)

    override fun forwardToSign() = navigationRouter.forward(LeekSignArgs)
}

class LeekWalletNavContributorImpl : LeekWalletNavContributor {
    override fun contribute(navGraphBuilder: NavGraphBuilder) {
        with(navGraphBuilder) {
            composable<LeekConnectArgs> { LeekConnectScreen() }
            composable<LeekSignArgs> { LeekSignScreen() }
        }
    }
}

val featureLeekWalletModule =
    module {
        single { LeekWalletDevice(androidContext()) }
        single { LeekWalletNavigatorImpl(get()) } bind LeekWalletNavigator::class
        single { LeekWalletNavContributorImpl() } bind LeekWalletNavContributor::class
        viewModelOf(::LeekConnectVM)
        viewModelOf(::LeekSignVM)
    }
