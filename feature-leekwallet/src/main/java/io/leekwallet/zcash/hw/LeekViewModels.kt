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
                transports = LeekTransport.entries,
                onScanned = ::onScanned,
                onScanSignature = ::onScanSignature,
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
        mutableState.update { it.copy(error = null, idle = false, scanning = false, qrPart = null) }
        job =
            viewModelScope.launch {
                runCatching {
                    val pczt = keystoneProposalRepository.createSignerPczt()
                    if (device.transport == LeekTransport.QR) {
                        showOverQr(pczt.toByteArray())
                        return@launch
                    }
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

    // ---- QR air gap: ur:leek-pczt out, ur:leek-zsig back (FORMATS.md) ----

    private var qrPczt: ByteArray? = null
    private var decoder = com.sparrowwallet.hummingbird.URDecoder()

    /** Animate the PCZT as ur:leek-pczt until the user moves on to scanning. */
    private suspend fun showOverQr(pczt: ByteArray) {
        qrPczt = pczt
        val ur = com.sparrowwallet.hummingbird.UR.fromBytes(UR_PCZT, pczt)
        val encoder = com.sparrowwallet.hummingbird.UREncoder(ur, QR_FRAGMENT, QR_MIN_FRAGMENT, 0)
        while (!mutableState.value.scanning) {
            // Upper case: QR alphanumeric mode, a third smaller; UR decoders, the device's
            // included, read it case-insensitively.
            mutableState.update { it.copy(qrPart = encoder.nextPart().uppercase()) }
            kotlinx.coroutines.delay(QR_FRAME_MS)
        }
    }

    private fun onScanSignature() {
        decoder = com.sparrowwallet.hummingbird.URDecoder()
        mutableState.update { it.copy(scanning = true) }
    }

    private fun onScanned(part: String) {
        val state = mutableState.value
        if (!state.scanning) return
        runCatching {
            decoder.receivePart(part)
            val result = decoder.result ?: return
            check(result.type == com.sparrowwallet.hummingbird.ResultType.SUCCESS) { "The QR could not be read" }
            check(result.ur.type == UR_ZSIG) { "Not a LeekWallet signature (ur:${result.ur.type})" }
            val pczt = checkNotNull(qrPczt) { "No transaction is waiting for a signature" }
            val signed = PcztSignatures.apply(pczt, result.ur.toBytes())
            mutableState.update { it.copy(scanning = false, qrPart = null) }
            job =
                viewModelScope.launch {
                    runCatching {
                        keystoneProposalRepository.setPcztWithSignatures(Pczt(signed))
                        submitKSProposal()
                    }.onFailure { e -> mutableState.update { it.copy(error = e.message ?: e.toString()) } }
                }
        }.onFailure { e ->
            decoder = com.sparrowwallet.hummingbird.URDecoder()
            mutableState.update { it.copy(scanning = false, error = e.message ?: e.toString()) }
        }
    }

    private companion object {
        const val UR_PCZT = "leek-pczt"
        const val UR_ZSIG = "leek-zsig"
        const val QR_FRAGMENT = 90
        const val QR_MIN_FRAGMENT = 10
        const val QR_FRAME_MS = 500L
    }
}
