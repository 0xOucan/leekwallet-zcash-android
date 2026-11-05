package co.electriccoin.zcash.ui.screen.transactionprogress

import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionProgressWrongBranchTest {
    private fun failure(code: Int) = SubmitResult.Failure(txIds = listOf("a"), code = code, description = "x")

    @Test
    fun theLocalRefusalIsRecognized() {
        assertTrue(failure(-9101).isWrongConsensusBranch())
        assertTrue(failure(TransactionSubmitResult.Failure.CODE_WRONG_CONSENSUS_BRANCH).isWrongConsensusBranch())
    }

    @Test
    fun otherFailuresAreNot() {
        assertFalse(failure(TransactionSubmitResult.Failure.CODE_EXPIRED).isWrongConsensusBranch())
        assertFalse(failure(18).isWrongConsensusBranch())
        assertFalse(SubmitResult.Error(IllegalStateException("x")).isWrongConsensusBranch())
    }
}
