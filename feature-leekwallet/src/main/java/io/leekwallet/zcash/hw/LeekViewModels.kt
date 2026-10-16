package io.leekwallet.zcash.hw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.SubmitKSProposalUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connect: pair with the device, read its account 0, import it watch-only (the device signs),
 * select it and restart sync from the device's birthday.
 */
class LeekConnectVM(
    private val device: LeekWalletDevice,
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val navigationRouter: NavigationRouter,
    private val context: android.content.Context,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            LeekScreenState(
                title = "Connect LeekWallet",
                status = LeekStatus.Idle,
                transport = device.transport,
                onTransport = ::setTransport,
                onStart = ::start,
                onRetry = ::start,
                onClose = ::close
            )
        )
    val state: StateFlow<LeekScreenState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { device.status.collect { s -> mutableState.update { it.copy(status = s) } } }
    }

    private fun setTransport(t: LeekTransport) {
        device.transport = t
        mutableState.update { it.copy(transport = t) }
    }

    private fun start() {
        job?.cancel()
        mutableState.update { it.copy(error = null, done = null, idle = false) }
        job =
            viewModelScope.launch {
                runCatching {
                    val info = device.readAccount()
                    val birthday =
                        info.birthday?.let { BlockHeight.new(it) }
                    val account =
                        accountDataSource.importLeekWalletAccount(
                            ufvk = info.ufvk,
                            seedFingerprint = info.seedFingerprintHex,
                            index = 0,
                            birthday = birthday,
                        )
                    accountDataSource.selectAccount(account)
                    synchronizerProvider.resetSynchronizer()
                    info
                }.onSuccess { info ->
                    mutableState.update {
                        it.copy(done = "LeekWallet connected.\n\n${info.ua}\n\nThe app watches this account; the device signs.")
                    }
                }.onFailure { e ->
                    mutableState.update { it.copy(error = e.message ?: e.toString()) }
                }
            }
    }

    private fun close() {
        job?.cancel()
        device.reset()
        if (mutableState.value.done != null) navigationRouter.backToRoot() else navigationRouter.back()
    }
}

/**
 * Sign: the signer view of the pending proposal goes to the device, the user reviews it there,
 * the device's signatures are applied, and the Keystone pipeline submits it (proofs were started
 * when the PCZT was created).
 */
class LeekSignVM(
    private val device: LeekWalletDevice,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val submitKSProposal: SubmitKSProposalUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            LeekScreenState(
                title = "Sign with LeekWallet",
                status = LeekStatus.Idle,
                transport = device.transport,
                onTransport = ::setTransport,
                onStart = ::start,
                onRetry = ::start,
                onClose = ::close
            )
        )
    val state: StateFlow<LeekScreenState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { device.status.collect { s -> mutableState.update { it.copy(status = s) } } }
    }

    private fun setTransport(t: LeekTransport) {
        device.transport = t
        mutableState.update { it.copy(transport = t) }
    }

    private fun start() {
        job?.cancel()
        mutableState.update { it.copy(error = null, idle = false) }
        job =
            viewModelScope.launch {
                runCatching {
                    val pczt = keystoneProposalRepository.createSignerPczt()
                    val signed = device.sign(pczt.toByteArray())
                    keystoneProposalRepository.setPcztWithSignatures(Pczt(signed))
                    device.reset()
                    submitKSProposal()
                }.onFailure { e ->
                    mutableState.update { it.copy(error = e.message ?: e.toString()) }
                }
            }
    }

    private fun close() {
        job?.cancel()
        device.reset()
        navigationRouter.back()
    }
}
