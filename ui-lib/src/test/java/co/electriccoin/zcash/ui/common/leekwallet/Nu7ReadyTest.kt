package co.electriccoin.zcash.ui.common.leekwallet

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.BlockSpacing
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NU7 (ZIP 259) activates on mainnet at height 3,543,405. An SDK that does not know this height
 * builds transactions for the previous consensus branch, which no block accepts after it. This
 * keeps a dependency change from taking the fork back to such an SDK. It reads the SDK's Kotlin
 * side only: the Rust backend's height was checked on the published library, not here.
 */
class Nu7ReadyTest {
    @Test
    fun theSdkKnowsTheMainnetActivationHeight() {
        assertEquals(
            BlockHeight.new(MAINNET_NU7_HEIGHT),
            BlockSpacing.nu7ActivationHeight(ZcashNetwork.Mainnet)
        )
    }

    @Test
    fun blocksTargetTwentyFiveSecondsFromTheActivation() {
        assertEquals(
            BlockSpacing.POST_BLOSSOM_SECONDS,
            BlockSpacing.targetSeconds(ZcashNetwork.Mainnet, BlockHeight.new(MAINNET_NU7_HEIGHT - 1))
        )
        assertEquals(
            BlockSpacing.POST_NU7_SECONDS,
            BlockSpacing.targetSeconds(ZcashNetwork.Mainnet, BlockHeight.new(MAINNET_NU7_HEIGHT))
        )
    }

    private companion object {
        const val MAINNET_NU7_HEIGHT = 3_543_405L
    }
}
