package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.repository.VotingProofPrecomputeRepository
import co.electriccoin.zcash.ui.common.voting.VotingSettingsEntry
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A LeekWallet account is Keystone-shaped, but the board does not sign governance ballots: voting
 * would enter Keystone's QR flow and never finish. Opening voting shows the LEEKWALLET error,
 * and preparing a round or submitting votes refuses it before any voting work starts.
 */
class LeekWalletVotingGuardTest {
    private val leekAccount = mockk<KeystoneAccount> { every { isLeekWallet } returns true }

    private val getSelectedWalletAccount =
        mockk<GetSelectedWalletAccountUseCase> {
            coEvery { this@mockk() } returns leekAccount
        }

    @Test
    fun openingVotingShowsTheLeekWalletError() =
        runTest {
            val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)
            val votingSettingsEntry = mockk<VotingSettingsEntry>(relaxed = true)
            val args = slot<ErrorArgs>()
            every { navigateToError(capture(args), any()) } returns Unit

            NavigateToVotingUseCase(
                getSelectedWalletAccount = getSelectedWalletAccount,
                hasSeenHowToVote = mockk(relaxed = true),
                hasSeenHowToVoteKeystone = mockk(relaxed = true),
                votingSettingsEntry = votingSettingsEntry,
                navigateToError = navigateToError,
            )()

            val error = (args.captured as ErrorArgs.General).exception as AccountOperationUnsupportedException
            assertEquals(AccountOperationUnsupportedException.Kind.LEEKWALLET, error.kind)
            verify(exactly = 0) { votingSettingsEntry.navigateToCoinholderPolling() }
            verify(exactly = 0) { votingSettingsEntry.navigateToHowToVote() }
        }

    @Test
    fun preparingARoundIsRefused() =
        runTest {
            val votingProofPrecomputeRepository = mockk<VotingProofPrecomputeRepository>(relaxed = true)
            val useCase =
                PrepareVotingRoundUseCase(
                    resolveVotingRoundSession = mockk(relaxed = true),
                    votingRecoveryRepository = mockk(relaxed = true),
                    votingSessionStore = mockk(relaxed = true),
                    votingCryptoClient = mockk(relaxed = true),
                    votingHotkeySeedProvider = mockk(relaxed = true),
                    votingProofPrecomputeRepository = votingProofPrecomputeRepository,
                    synchronizerProvider = mockk(relaxed = true),
                    getSelectedWalletAccount = getSelectedWalletAccount,
                )

            val thrown = assertFailsWith<AccountOperationUnsupportedException> { useCase("round") }

            assertEquals(AccountOperationUnsupportedException.Kind.LEEKWALLET, thrown.kind)
            coVerify(exactly = 0) { votingProofPrecomputeRepository.warmProvingCaches() }
        }

    @Test
    fun submittingVotesIsRefused() =
        runTest {
            val prepareVotingRound = mockk<PrepareVotingRoundUseCase>(relaxed = true)
            val votingProofPrecomputeRepository = mockk<VotingProofPrecomputeRepository>(relaxed = true)
            val useCase =
                SubmitVotesUseCase(
                    resolveVotingRoundSession = mockk(relaxed = true),
                    votingCryptoClient = mockk(relaxed = true),
                    votingHotkeySeedProvider = mockk(relaxed = true),
                    synchronizerProvider = mockk(relaxed = true),
                    getSelectedWalletAccount = getSelectedWalletAccount,
                    getWalletSeedBytes = mockk(relaxed = true),
                    prepareVotingRound = prepareVotingRound,
                    votingShareTrackingScheduler = mockk(relaxed = true),
                    votingRecoveryRepository = mockk(relaxed = true),
                    votingKeystoneSessionHolder = mockk(relaxed = true),
                    votingProofPrecomputeRepository = votingProofPrecomputeRepository,
                )

            val thrown =
                assertFailsWith<AccountOperationUnsupportedException> {
                    useCase(roundId = "round", choices = mapOf(0 to 1))
                }

            assertEquals(AccountOperationUnsupportedException.Kind.LEEKWALLET, thrown.kind)
            coVerify(exactly = 0) { prepareVotingRound(any()) }
            coVerify(exactly = 0) { votingProofPrecomputeRepository.cancelAndAwaitPrecompute(any(), any()) }
        }
}
