package io.leekwallet.zcash.hw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.SubmitKSProposalUseCase
import io.leekwallet.zcash.sdk.AddressCheck
import io.leekwallet.zcash.sdk.QrEncoder
import io.leekwallet.zcash.sdk.QrPartState
import io.leekwallet.zcash.sdk.QrScanKind
import io.leekwallet.zcash.sdk.QrScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports a board account watch-only (the board signs), selects it and restarts sync from its
 * birthday: the same for the USB / Bluetooth import and the View key QR.
 */
class LeekAccountImporter(
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
) {
    suspend fun import(info: LeekAccountInfo) {
        val account =
            accountDataSource.importLeekWalletAccount(
                ufvk = info.ufvk,
                seedFingerprint = info.seedFingerprintHex,
                index = info.index,
                birthday = usableBirthday(info.birthday),
            )
        accountDataSource.selectAccount(account)
        synchronizerProvider.resetSynchronizer()
    }

    /**
     * The board keeps one birthday per account, whichever network it was recorded on. A height
     * past this network's tip (a testnet birthday seen by the mainnet app: testnet is the
     * longer chain) would hide every note, so it is dropped and the account scans from the start.
     */
    private suspend fun usableBirthday(height: Long?): BlockHeight? {
        if (height == null) return null
        val tip = synchronizerProvider.getSynchronizer().networkHeight.value
        return if (tip != null && height > tip.value) null else BlockHeight.new(height)
    }
}

/** The selected LeekWallet account as the SDK needs it to check and sign for it. */
internal suspend fun AccountDataSource.selectedLeekSigner(): LeekSigner {
    val account = getSelectedAccount()
    check(account is KeystoneAccount && account.isLeekWallet) { "The selected account is not a LeekWallet account." }
    val sdk = account.sdkAccount
    val again = "import it again."
    return LeekSigner(
        seedFingerprint = checkNotNull(sdk.seedFingerprint) { "This account has no seed fingerprint: $again" },
        index = checkNotNull(sdk.hdAccountIndex) { "This account has no account index: $again" }.index.toUInt(),
        ufvk = checkNotNull(sdk.ufvk) { "This account has no viewing key: $again" },
    )
}

/**
 * Connect: pair with the device, read its account 0, import it watch-only (the device signs),
 * select it and restart sync from the device's birthday. Or, with no link at all, import it from
 * the board's View key QR ([LeekViewKeyImportVM]).
 */
class LeekConnectVM(
    private val device: LeekWalletDevice,
    private val importer: LeekAccountImporter,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            LeekScreenState(
                title = "Connect LeekWallet",
                status = LeekStatus.Idle,
                transport = device.transport.takeIf { it != LeekTransport.QR } ?: LeekTransport.USB,
                onTransport = ::setTransport,
                onStart = ::start,
                onRetry = ::start,
                onViewKeyQr = ::openViewKeyImport,
                onClose = ::close
            )
        )
    val state: StateFlow<LeekScreenState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { device.status.collect { s -> mutableState.update { it.copy(status = s) } } }
        viewModelScope.launch { device.boardUpdate.collect { b -> mutableState.update { it.copy(boardUpdate = b) } } }
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
                    importer.import(info)
                    info
                }.onSuccess { info ->
                    mutableState.update {
                        it.copy(done = "LeekWallet connected.\n\n${info.ua}\n\nThe app watches this account; the device signs.")
                    }
                }.onFailure { e ->
                    mutableState.update { it.copy(error = e.leekMessage()) }
                }
            }
    }

    private fun openViewKeyImport() {
        device.cancel()
        job?.cancel()
        device.reset()
        navigationRouter.forward(LeekViewKeyImportArgs)
    }

    private fun close() {
        device.cancel()
        job?.cancel()
        device.reset()
        if (mutableState.value.done != null) navigationRouter.backToRoot() else navigationRouter.back()
    }
}

/**
 * View key import: the camera reads the board's animated ur:leek-zcash-accounts (Main menu >
 * View key on the board), and the account is imported as the USB import does it: its UFVK, the
 * seed fingerprint, the account index and the birthday when the board knows it. A one-way
 * export, not pairing: no link is opened and nothing goes to the board.
 */
class LeekViewKeyImportVM(
    private val device: LeekWalletDevice,
    private val importer: LeekAccountImporter,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            LeekScreenState(
                title = "Import from View key",
                status = LeekStatus.Idle,
                transports = emptyList(),
                idleText =
                    "On the board: Main menu > View key. Then press Start and point this phone's " +
                        "camera at the board's screen.",
                scanText = "Point the camera at the View key QR on the board.",
                onScanned = ::onScanned,
                onStart = ::start,
                onRetry = ::start,
                onClose = ::close
            )
        )
    val state: StateFlow<LeekScreenState> = mutableState.asStateFlow()
    private var scanner: QrScanner? = null
    private var job: Job? = null

    private fun start() {
        job?.cancel()
        scanner?.close()
        scanner = QrScanner(QrScanKind.ACCOUNT_RECORD, device.network)
        mutableState.update { it.copy(error = null, done = null, idle = false, scanning = true, qrProgress = null) }
    }

    private fun onScanned(text: String) {
        val s = scanner?.takeIf { mutableState.value.scanning } ?: return
        val p = s.feed(text)
        when (p.state) {
            QrPartState.COMPLETE -> {
                val record = s.accountRecord() ?: return
                mutableState.update { it.copy(scanning = false, qrProgress = null) }
                val entry = record.accounts.firstOrNull { it.index == 0u } ?: record.accounts.first()
                val info =
                    LeekAccountInfo(
                        ufvk = entry.ufvk,
                        ua = null,
                        seedFingerprintHex = record.seedFingerprint.toHex(),
                        index = entry.index.toLong(),
                        birthday = entry.birthday?.toLong(),
                    )
                job =
                    viewModelScope.launch {
                        mutableState.update { it.copy(status = LeekStatus.Working("Importing the account")) }
                        runCatching { importer.import(info) }
                            .onSuccess {
                                val more =
                                    if (record.accounts.size > 1) {
                                        "\n\nThe board shared ${record.accounts.size} accounts; account " +
                                            "${entry.index} was imported."
                                    } else {
                                        ""
                                    }
                                mutableState.update {
                                    it.copy(
                                        done =
                                            "LeekWallet account ${entry.index} imported from its View key. " +
                                                "The app watches it; the board signs.$more\n\nCheck the " +
                                                "address on the board: Receive > Verify on device.",
                                    )
                                }
                            }.onFailure { e -> mutableState.update { it.copy(error = e.leekMessage()) } }
                    }
            }

            QrPartState.REFUSED -> {
                mutableState.update {
                    it.copy(scanning = false, qrProgress = null, error = p.error ?: "This View key cannot be imported.")
                }
            }

            else -> {
                mutableState.update { it.copy(qrProgress = p.label() ?: it.qrProgress) }
            }
        }
    }

    override fun onCleared() {
        scanner?.close()
        scanner = null
    }

    private fun close() {
        job?.cancel()
        if (mutableState.value.done != null) navigationRouter.backToRoot() else navigationRouter.back()
    }
}

/**
 * Sign: the signer view of the pending proposal goes to the device, the user reviews it there,
 * the device's signatures are verified and applied, and the Keystone pipeline submits it (proofs
 * were started when the PCZT was created). Over USB / Bluetooth the SDK's LeekDevice does it in
 * one call; over QR the request is an animated ur:leek-pczt and the reply a ur:leek-zsig.
 */
class LeekSignVM(
    private val device: LeekWalletDevice,
    private val accountDataSource: AccountDataSource,
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
                scanText = "Point the camera at the signature QR on the device.",
                onScanned = ::onScanned,
                onScanSignature = ::onScanSignature,
                qrPreset = device.qrPreset,
                onQrPreset = ::setQrPreset,
                onTransport = ::setTransport,
                compactPczt = device.compactPczt,
                onCompactPczt = ::toggleCompact,
                onStart = ::start,
                onRetry = ::start,
                onClose = ::close
            )
        )
    val state: StateFlow<LeekScreenState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { device.status.collect { s -> mutableState.update { it.copy(status = s) } } }
        viewModelScope.launch { device.boardUpdate.collect { b -> mutableState.update { it.copy(boardUpdate = b) } } }
    }

    private fun setTransport(t: LeekTransport) {
        device.transport = t
        mutableState.update { it.copy(transport = t) }
    }

    private fun toggleCompact() {
        device.compactPczt = !device.compactPczt
        mutableState.update { it.copy(compactPczt = device.compactPczt) }
    }

    private fun setQrPreset(p: LeekQrPreset) {
        device.qrPreset = p
        mutableState.update { it.copy(qrPreset = p) }
    }

    private fun start() {
        device.cancel()
        job?.cancel()
        mutableState.update { it.copy(error = null, idle = false, scanning = false, qrPart = null, qrProgress = null) }
        job =
            viewModelScope.launch {
                runCatching {
                    val pczt = keystoneProposalRepository.createSignerPczt().toByteArray()
                    val signer = accountDataSource.selectedLeekSigner()
                    if (device.transport == LeekTransport.QR) {
                        val request = withContext(Dispatchers.Default) { device.qrRequest(pczt, signer) }
                        qrRequest = request
                        showOverQr(request.sent)
                        return@launch
                    }
                    val signed = device.sign(pczt, signer)
                    keystoneProposalRepository.setPcztWithSignatures(Pczt(signed))
                    device.reset()
                    submitKSProposal()
                }.onFailure { e ->
                    mutableState.update { it.copy(error = e.leekMessage(), qrPart = null) }
                }
            }
    }

    /**
     * Back to where the sign screen was opened from. A send goes back to its review screen, which
     * still needs the proposal; a shield came from a prompt that builds a new one each time, so
     * nothing would ever drop this one: drop it here (as Keystone's reject does).
     */
    private fun close() {
        device.cancel()
        job?.cancel()
        device.reset()
        if (keystoneProposalRepository.transactionProposal.value is ShieldTransactionProposal) {
            keystoneProposalRepository.clear()
        }
        navigationRouter.back()
    }

    // ---- QR air gap: ur:leek-pczt out, ur:leek-zsig back (FORMATS.md) ----

    private var qrRequest: LeekQrRequest? = null
    private var scanner: QrScanner? = null

    /**
     * Animates [sent] as ur:leek-pczt until the user switches to scanning the reply. A new preset
     * is a new encoder: the board's decoder starts over on it. Every frame is uppercase (the QR
     * alphanumeric mode) and at most longestFrame() characters, so the code keeps one size.
     */
    private suspend fun showOverQr(sent: ByteArray) {
        var preset: LeekQrPreset? = null
        var encoder: QrEncoder? = null
        var index = 0uL
        try {
            while (!mutableState.value.scanning) {
                val wanted = mutableState.value.qrPreset
                if (encoder == null || wanted != preset) {
                    encoder?.close()
                    preset = wanted
                    encoder = QrEncoder.pczt(sent, wanted.fragmentLen)
                    index = 0uL
                }
                val frame = encoder.frame(index++)
                val longest = encoder.longestFrame().toInt()
                mutableState.update { it.copy(qrPart = frame, qrLongest = longest) }
                delay(wanted.frameMs)
            }
        } finally {
            encoder?.close()
        }
    }

    private fun onScanSignature() {
        scanner?.close()
        scanner = QrScanner(QrScanKind.ZSIG, null)
        mutableState.update { it.copy(scanning = true, qrProgress = null) }
    }

    private fun onScanned(text: String) {
        val s = scanner?.takeIf { mutableState.value.scanning } ?: return
        val p = s.feed(text)
        when (p.state) {
            QrPartState.COMPLETE -> {
                val zsig = s.zsig() ?: return
                mutableState.update { it.copy(scanning = false, qrPart = null, qrProgress = null) }
                job =
                    viewModelScope.launch {
                        runCatching {
                            val request = checkNotNull(qrRequest) { "No transaction is waiting for a signature" }
                            val signed = withContext(Dispatchers.Default) { device.applyQrSignature(request, zsig) }
                            keystoneProposalRepository.setPcztWithSignatures(Pczt(signed))
                            submitKSProposal()
                        }.onFailure { e -> mutableState.update { it.copy(error = e.leekMessage()) } }
                    }
            }

            QrPartState.REFUSED -> {
                mutableState.update {
                    it.copy(scanning = false, qrPart = null, error = p.error ?: "The signature QR could not be used.")
                }
            }

            else -> {
                mutableState.update { it.copy(qrProgress = p.label() ?: it.qrProgress) }
            }
        }
    }

    override fun onCleared() {
        scanner?.close()
        scanner = null
    }
}

/** Receive: the device shows its address, the user compares it with the app's ([ua]). */
class LeekVerifyVM(
    private val ua: String,
    private val device: LeekWalletDevice,
    private val accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            LeekScreenState(
                title = "Verify address on LeekWallet",
                status = LeekStatus.Idle,
                transport = device.transport.takeIf { it != LeekTransport.QR } ?: LeekTransport.USB,
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
        viewModelScope.launch { device.boardUpdate.collect { b -> mutableState.update { it.copy(boardUpdate = b) } } }
    }

    private fun setTransport(t: LeekTransport) {
        device.transport = t
        mutableState.update { it.copy(transport = t) }
    }

    private fun start() {
        device.cancel()
        job?.cancel()
        mutableState.update { it.copy(error = null, done = null, idle = false) }
        job =
            viewModelScope.launch {
                runCatching { device.verifyAddress(ua, accountDataSource.selectedLeekSigner().index) }
                    .onSuccess { check ->
                        device.reset()
                        mutableState.update {
                            when (check) {
                                AddressCheck.VERIFIED -> {
                                    it.copy(done = "The device confirmed this address. It is your LeekWallet's.")
                                }

                                AddressCheck.REJECTED -> {
                                    it.copy(
                                        error =
                                            "You answered on the device that the addresses differ. Do not use " +
                                                "this address until they match."
                                    )
                                }

                                AddressCheck.NOT_OURS -> {
                                    it.copy(
                                        error =
                                            "The device says this address is not one of its own. Do not use it: " +
                                                "this account may not belong to the board you connected."
                                    )
                                }

                                AddressCheck.UNSUPPORTED -> {
                                    it.copy(
                                        error =
                                            "The board's firmware cannot show this address. Update the board " +
                                                "to check it: zcash.leekwallet.xyz"
                                    )
                                }
                            }
                        }
                    }.onFailure { e ->
                        device.reset()
                        mutableState.update { it.copy(error = e.leekMessage()) }
                    }
            }
    }

    private fun close() {
        device.cancel()
        job?.cancel()
        device.reset()
        navigationRouter.back()
    }
}
