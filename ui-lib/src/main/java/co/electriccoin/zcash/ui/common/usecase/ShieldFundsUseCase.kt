package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.leekwallet.LeekWalletNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShieldFundsUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val ledgerNavigator: LedgerNavigator,
    private val leekWalletNavigator: LeekWalletNavigator,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val navigateToError: NavigateToErrorUseCase,
    private val messageAvailabilityDataSource: MessageAvailabilityDataSource,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var job: Job? = null

    operator fun invoke(closeCurrentScreen: Boolean) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                messageAvailabilityDataSource.onShieldingInitiated()

                when (val account = accountDataSource.getSelectedAccount()) {
                    is KeystoneAccount -> {
                        createKeystoneShieldProposal(isLeekWallet = account.isLeekWallet)
                    }

                    is LedgerAccount -> {
                        createLedgerShieldProposal()
                    }

                    is ZashiAccount -> {
                        if (closeCurrentScreen) {
                            navigationRouter.back()
                        }
                        shieldZashiFunds()
                    }
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun shieldZashiFunds() {
        try {
            zashiProposalRepository.createShieldProposal()
            when (val result = zashiProposalRepository.submit()) {
                is SubmitResult.Failure,
                is SubmitResult.GrpcFailure,
                is SubmitResult.Error,
                is SubmitResult.Partial -> {
                    navigateToError(ErrorArgs.ShieldingError(result))
                }

                is SubmitResult.Success -> {
                    // do nothing
                }
            }
        } catch (e: Exception) {
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        } finally {
            zashiProposalRepository.clear()
        }
    }

    /**
     * A LeekWallet account is Keystone-shaped: the same proposal and PCZT, but the board signs it
     * (its transparent inputs included) on the LeekWallet sign screen instead of Keystone's QR one.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun createKeystoneShieldProposal(isLeekWallet: Boolean) {
        try {
            keystoneProposalRepository.createShieldProposal()
            keystoneProposalRepository.createPCZTFromProposal()
            if (isLeekWallet) {
                leekWalletNavigator.forwardToSign()
            } else {
                navigationRouter.forward(SignKeystoneTransactionArgs)
            }
        } catch (e: Exception) {
            keystoneProposalRepository.clear()
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        }
    }

    /**
     * A cancelled shield drops its half-made proposal, as nothing but the sign sheet would hold it,
     * and propagates the cancellation instead of reporting it as a shielding error.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun createLedgerShieldProposal() {
        try {
            ledgerProposalPipeline.createShieldProposal()
            ledgerProposalPipeline.createPCZTFromProposal()
            ledgerNavigator.forwardToSign()
        } catch (e: CancellationException) {
            ledgerProposalPipeline.clear()
            throw e
        } catch (e: Exception) {
            ledgerProposalPipeline.clear()
            navigateToError(ErrorArgs.ShieldingGeneralError(e))
        }
    }
}
