@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.riverark.ferret

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.riverark.ferret.core.backup.*
import io.riverark.ferret.core.cardano.IosCardanoTransactionEngine
import io.riverark.ferret.core.cardano.IosProtocolCrypto
import io.riverark.ferret.core.cardano.IosProtocolSigner
import io.riverark.ferret.core.channel.*
import io.riverark.ferret.core.model.*
import io.riverark.ferret.core.network.*
import io.riverark.ferret.core.security.*
import io.riverark.ferret.feature.payment.IosQrScanner
import io.riverark.ferret.feature.payment.QrPaymentScannerScreen
import platform.Foundation.NSBundle
import io.riverark.ferret.feature.wallet.*
import kotlinx.coroutines.*
import kotlin.time.Clock
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Network.*
import platform.UIKit.UIApplication
import platform.darwin.*

/** One host-owned session; encrypted wallet data remains solely in SecureVault. */
class IosWalletRuntime(
    crypto: IosCrypto,
    google: IosGoogleSignIn,
    private val sensitiveContentChanged: (Boolean) -> Unit,
    scannerFactory: () -> IosQrScanner,
    qrEncoder: IosQrEncoder,
    continuousMillis: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clock = ForegroundLockPolicy(continuousMillis)
    private val diagnostics = RuntimeDiagnostics()
    private val wallets = WalletRepository()
    private val sensitiveContent = SensitiveContentCounter()
    private val protocolCrypto = IosProtocolCrypto(crypto)
    private val catalog = runBlocking { loadEmbeddedAssetCatalog(protocolCrypto) }
    private val http = iosFerretHttpClient(crypto)
    private val connectors = CardanoNetwork.entries.associateWith { ConnectorClient(http, deployment(it)) }
    private val adaptors = CardanoNetwork.entries.associateWith { AdaptorClient(http, deployment(it), protocolCrypto) }
    private val coordinators = CardanoNetwork.entries.associateWith {
        RefreshCoordinator(deployment(it), catalog, connectors.getValue(it), adaptors.getValue(it))
    }
    private val vault = IosSecureVault(IosBackupCrypto(crypto), IosSecureRandomSource())
    private val authenticator = IosUserAuthenticator()
    private val preferences = NSUserDefaults.standardUserDefaults
    private val engine = IosCardanoTransactionEngine(catalog) { network, cbor ->
        connectors.getValue(network).evaluate(cbor.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }
    private val walletManager = WalletManager(vault, IosSecureRandomSource(), IosRecoveryPhraseCodec(crypto),
        engine::deriveWallet, wallets,
        { preferences.stringForKey("active-wallet-id")?.let(::WalletId) },
        { id -> if (id == null) preferences.removeObjectForKey("active-wallet-id") else preferences.setObject(id.value, "active-wallet-id") },
    )
    private val tokens = IosGoogleOAuthTokenProvider(google)
    private val identity = IosDeviceIdentity()
    private val backupCrypto = IosBackupCrypto(crypto)
    private val journal = VaultChannelJournal(vault, catalog)
    private val backup = WalletBackupCoordinator(
        vault, DriveBackupRepository(GoogleDriveAppDataClient(http, tokens), backupCrypto), backupCrypto,
        ::wallClockMillis, journal::normalizeBackup,
        { id, bytes, checkpoint -> journal.installBackup(id, bytes, checkpoint) },
    )
    private val paymentStore = VaultPaymentStore(journal)
    private val leases = mutableMapOf<WalletId, SessionLeaseRepository>()
    private val activeOperations = mutableSetOf<Job>()
    private val l1 = DefaultL1WalletRepository(wallets, vault, catalog,
        { profile -> connectors.getValue(profile.network) }, engine,
        ::operationId, ::wallClockMillis,
        { walletId -> journal.load(walletId).channels.values.any { it.pending != null } },
    )
    private val guardedL1 = object : L1WalletRepository by l1 {
        override suspend fun previewTransfer(walletId: WalletId, destination: TransferDestination, amount: AssetAmount): TransferPreview {
            requireFinancialSession(walletId)
            return activeOperation { l1.previewTransfer(walletId, destination, amount) }
        }
        override suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview): String {
            requireFinancialSession(walletId)
            return activeOperation { l1.submitTransfer(walletId, preview) }
        }
        override suspend fun previewSweep(walletId: WalletId, destinationAddress: String): io.riverark.ferret.core.cardano.SweepPreview {
            requireFinancialSession(walletId)
            return activeOperation { l1.previewSweep(walletId, destinationAddress) }
        }
        override suspend fun submitSweep(walletId: WalletId, preview: io.riverark.ferret.core.cardano.SweepPreview): String {
            requireFinancialSession(walletId)
            return activeOperation { l1.submitSweep(walletId, preview) }
        }
    }
    private val random = IosSecureRandomSource()
    private val channelTransactions = ChannelTransactions(vault, engine, catalog,
        loadLedger = { profile ->
            val connector = connectors.getValue(profile.network)
            val ledger = connector.ledger(profile.paymentAddress, profile.network)
            val outputs = ledger.utxos +
                connector.utxos(deployment(profile.network).scriptDeploymentAddress).map { it.ledger() } +
                connector.utxos(deployment(profile.network).validatorAddress).map { it.ledger() }
            require(outputs.map { it.transactionId to it.index }.distinct().size == outputs.size)
            ledger.copy(utxos = outputs)
        },
        loadInfo = { profile -> adaptors.getValue(profile.network).info() },
        verificationKey = { profile -> IosProtocolSigner(vault, profile.id, profile.network).verificationKeyHex() },
        availability = ::requireChannelFundingAvailable,
        newTag = { random.bytes(32) },
        nowEpochMillis = ::wallClockMillis,
    )
    private val channels = ChannelRepository(wallets, journal,
        DriveChannelBackupProtocol(backup, ::claimWriter, journal),
        AdaptorChannelRemote({ id ->
            val profile = vault.profiles().single { it.id == id }
            adaptors.getValue(profile.network)
        }, protocolCrypto), ::operationId, channelTransactions,
    )
    private val removal = WalletRemovalManager(
        DefaultWalletRemovalRepository(
            loadReadiness = { id ->
                requireFinancialSession(id)
                val profile = vault.profiles().single { it.id == id }
                coordinators.getValue(profile.network).refresh {
                    val encrypted = vault.walletState(id)
                    try {
                        val driveResolved = if (encrypted.backupGeneration == 0L) true else try {
                            val checkpoint = backup.verify(id)
                            checkpoint.ciphertextHash.fill(0)
                            checkpoint.channelSnapshot.fill(0)
                            true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                        val operations = l1.operations(id)
                        val collection = journal.load(id)
                        val balance = l1.balance(id)
                        val connector = connectors.getValue(profile.network)
                        val transactions = (connector.transactions(profile.paymentAddress, catalog).map { it.id } +
                            operations.mapNotNull { it.expectedTransactionId } +
                            collection.channels.values.flatMap { entry -> entry.history.mapNotNull { it.transactionId } }).distinct()
                        RemovalReadiness(profile, balance.assets.single { it.total.asset == catalog.ada }.spendable,
                            balance.assets.map { it.total }, balance.unsupportedAssets, collection,
                            operations.any { it.state in setOf(L1OperationState.PREPARED, L1OperationState.SUBMITTING, L1OperationState.PENDING) },
                            driveResolved, transactions.map { connector.transaction(it)?.depth ?: 0 })
                    } finally {
                        encrypted.channelRecovery.fill(0)
                        encrypted.operationJournal.fill(0)
                    }
                }
            },
            previewer = guardedL1::previewSweep,
            submitter = guardedL1::submitSweep,
            deleteBackup = backup::delete,
        ), vault, wallets,
    )
    private var foreground = false
    private var reachable = false
    private var closed = false
    private var unlocking = false
    private var work: Job? = null
    private var backgroundLock: Job? = null
    private val pathMonitor = nw_path_monitor_create()
    var unlockError by mutableStateOf<String?>(null)
        private set

    init {
        nw_path_monitor_set_update_handler(pathMonitor) { path ->
            reachable = nw_path_get_status(path) == nw_path_status_satisfied
            if (!reachable) networkUnavailable()
            else if (foreground && vault.isUnlocked && wallets.state.value == AppState.Offline) startOnlineSession(false)
        }
        nw_path_monitor_set_queue(pathMonitor, dispatch_get_main_queue())
        nw_path_monitor_start(pathMonitor)
    }

    val dependencies = FerretDependencies(
        wallets, walletManager, catalog,
        loadBalance = { profile -> onlineRefresh(profile) {
            l1.reconcilePending(profile.id); channels.reconcileAll(profile.id); channels.load(profile.id); l1.balance(profile.id)
        } },
        loadHistory = { profile -> onlineRefresh(profile) {
            l1.reconcilePending(profile.id)
            (l1.history(profile.id) + paymentStore.history(profile.id)).sortedWith(
                compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
        } },
        encodeQr = { address -> addressQrCode(address, qrEncoder) },
        copyAddress = ::copyIosAddress,
        l1WalletRepository = guardedL1,
        loadChannels = { id -> requireFinancialSession(id); activeOperation { channels.reconcileAll(id); channels.load(id); channels.snapshots.value.getValue(id) } },
        cleanupInactiveChannels = { id -> requireFinancialSession(id); activeOperation { channels.cleanupInactive(id) } },
        paymentViewModelFactory = { id ->
            requireFinancialSession(id)
            val profile = currentProfile(id)
            PaymentViewModel(id, channels, DefaultPaymentGateway(
                adaptor = { id -> requireFinancialSession(id); adaptors.getValue(profile.network) },
                selected = { walletId, tag -> requireFinancialSession(walletId); channels.snapshots.value.getValue(walletId).channels.getValue(tag.value) },
                writer = { verifiedWriter(it) },
                signer = { walletId -> requireFinancialSession(walletId); sessionSigner(walletId, profile.network) },
                network = { walletId -> requireFinancialSession(walletId); profile.network },
                chain = if (profile.network == CardanoNetwork.MAINNET) "mainnet" else "testnet",
                crypto = protocolCrypto, assets = catalog,
            ), ::wallClockMillis, catalog)
        },
        loadPaymentReceipt = paymentStore::receipt,
        previewOpenChannel = { id, amount -> requireFinancialSession(id); onlineRefresh(currentProfile(id)) { channels.previewOpen(id, amount) } },
        previewAddChannelFunds = { id, keytag, amount -> requireFinancialSession(id); onlineRefresh(currentProfile(id)) { channels.previewAdd(id, keytag, amount) } },
        submitChannel = { id, preview -> requireFinancialSession(id); onlineRefresh(currentProfile(id)) { channels.submit(id, preview) } },
        invoiceScanner = { onInvoice, onError -> QrPaymentScannerScreen(scannerFactory, onInvoice, onError) },
        nowEpochMillis = ::wallClockMillis,
        loadSettings = { profile ->
            requireFinancialSession(profile.id)
            val encrypted = vault.walletState(profile.id)
            try {
                val checkpoint = backup.checkpoint(profile.id)
                try {
                    WalletSettings(profile, profile.id.value.substringAfter('-'), profile.stakeAddress,
                        l1.balance(profile.id), journal.load(profile.id), "validated", tokens.accountName,
                        checkpoint?.generation, checkpoint?.sequence, "unlocked",
                        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "Unavailable",
                        "Unavailable", diagnostics.code.value?.value)
                } finally { checkpoint?.ciphertextHash?.fill(0); checkpoint?.channelSnapshot?.fill(0) }
            } finally { encrypted.channelRecovery.fill(0); encrypted.operationJournal.fill(0) }
        },
        connectDrive = { checkForeground(); activeOperation { tokens.connect() } },
        verifyBackup = { id -> withSnapshot(id) { snapshot -> backup.initializeOrVerify(id, snapshot).consume { it.sequence } } },
        replaceMissingBackup = { id -> withSnapshot(id) { snapshot -> backup.replaceMissing(id, snapshot).consume { it.sequence } } },
        takeoverBackup = { id -> activeOperation {
            requireFinancialSession(id)
            val checkpoint = backup.takeover(id)
            try { requireFinancialSession(id); claimWriter(id, checkpoint); walletManager.load(id); checkpoint.generation }
            finally { checkpoint.ciphertextHash.fill(0); checkpoint.channelSnapshot.fill(0) }
        } },
        restoreBackup = { id -> activeOperation {
            requireFinancialSession(id)
            val checkpoint = backup.restore(id)
            try { requireFinancialSession(id); walletManager.load(id); checkpoint.sequence }
            finally { checkpoint.ciphertextHash.fill(0); checkpoint.channelSnapshot.fill(0) }
        } },
        walletRemovalManager = removal,
    )

    fun setSensitiveContent(value: Boolean) {
        sensitiveContentChanged(sensitiveContent.update(value))
    }

    fun unlock() = startOnlineSession(!vault.isUnlocked)

    fun onForeground() {
        if (closed) return
        if (!UIApplication.sharedApplication.protectedDataAvailable) { lockSession(); return }
        foreground = true
        backgroundLock?.cancel()
        backgroundLock = null
        if (clock.foregrounded()) lockSession()
        else if (vault.isUnlocked) startOnlineSession(false)
    }

    fun onBackground() {
        if (closed || !foreground) return
        foreground = false
        authenticator.cancel()
        if (vault.isUnlocked) {
            clock.backgrounded()
            cancelActiveWork()
            wallets.checkingConnectivity()
            backgroundLock = scope.launch {
                delay(checkNotNull(clock.millisUntilLock()))
                if (clock.shouldLock()) lockSession()
            }
        } else cancelActiveWork()
    }

    fun onProtectedDataUnavailable() { if (!closed) lockSession() }

    fun close() {
        if (closed) return
        closed = true
        nw_path_monitor_cancel(pathMonitor)
        authenticator.cancel()
        backgroundLock?.cancel()
        lockSession()
        http.close()
        scope.cancel()
    }

    private fun startOnlineSession(authenticate: Boolean) {
        if (closed || !foreground || work?.isActive == true || (authenticate && unlocking)) return
        if (authenticate) { unlockError = null; unlocking = true }
        work = scope.launch {
            try {
                if (authenticate) {
                    val key = authenticator.authenticate("Unlock your Ferret wallets.")
                    try {
                        if (!foreground || closed) throw CancellationException("Authentication cancelled.")
                        vault.unlock(key)
                    } finally { key.fill(0) }
                }
                if (!foreground || clock.shouldLock()) { lockSession(); return@launch }
                val profiles = vault.profiles()
                if (profiles.isEmpty()) {
                    diagnostics.clear()
                    wallets.publish(null, profiles)
                    return@launch
                }
                wallets.checkingConnectivity()
                if (!reachable) {
                    diagnostics.record(DiagnosticCode.CONNECTIVITY)
                    error("network unavailable")
                }
                val selected = requireNotNull(walletManager.selectedProfile(profiles))
                coordinators.getValue(selected.network).validate(selected)
                if (!foreground || clock.shouldLock()) { lockSession(); return@launch }
                channels.load(selected.id)
                l1.reconcilePending(selected.id)
                channels.reconcileAll(selected.id)
                walletManager.load(selected.id)
                diagnostics.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authenticate && !vault.isUnlocked) {
                    diagnostics.record(DiagnosticCode.AUTHENTICATION)
                    unlockError = error.message?.takeIf { it.contains("Set up biometrics") || it.contains("recovery required") }
                    lockSession()
                } else {
                    if (diagnostics.code.value != DiagnosticCode.CONNECTIVITY) diagnostics.record(DiagnosticCode.DEPLOYMENT)
                    if (vault.isUnlocked) wallets.offline() else wallets.lock()
                }
            } finally { if (authenticate) unlocking = false }
        }
    }

    private fun networkUnavailable() {
        if (closed || !vault.isUnlocked) return
        diagnostics.record(DiagnosticCode.CONNECTIVITY)
        cancelActiveWork()
        wallets.offline()
    }

    private fun cancelActiveWork() {
        work?.cancel()
        work = null
        coordinators.values.forEach(RefreshCoordinator::cancelActiveWork)
        activeOperations.toList().forEach(Job::cancel)
        leases.values.forEach(SessionLeaseRepository::clear)
    }

    private fun lockSession() {
        cancelActiveWork()
        authenticator.cancel()
        vault.lock()
        wallets.lock()
    }

    private fun checkForeground() {
        if (clock.shouldLock() || !UIApplication.sharedApplication.protectedDataAvailable) lockSession()
        require(!closed && foreground && vault.isUnlocked) { "wallet session unavailable" }
    }

    private fun requireFinancialSession(id: WalletId) {
        checkForeground()
        require(reachable) { "network unavailable" }
        val ready = wallets.state.value as? AppState.Ready ?: error("wallet session unavailable")
        require(ready.activeWalletId == id) { "wallet is not selected" }
        require(ready.wallets.single { it.id == id }.network == CardanoNetwork.MAINNET) { "Only Mainnet is supported." }
    }

    private suspend fun requireChannelFundingAvailable(id: WalletId) {
        requireFinancialSession(id)
        require(l1.operations(id).none {
            it.state in setOf(L1OperationState.PREPARED, L1OperationState.SUBMITTING, L1OperationState.PENDING)
        }) { "L1 operation is unresolved" }
    }

    private fun currentProfile(id: WalletId) =
        (wallets.state.value as? AppState.Ready)?.wallets?.singleOrNull { it.id == id }
            ?: error("wallet profile unavailable")

    private fun sessionSigner(id: WalletId, network: CardanoNetwork): ProtocolSigner {
        val native = IosProtocolSigner(vault, id, network)
        return object : ProtocolSigner {
            override suspend fun verificationKeyHex(): String {
                requireFinancialSession(id)
                return native.verificationKeyHex()
            }
            override suspend fun sign(message: ByteArray): ByteArray {
                requireFinancialSession(id)
                return native.sign(message)
            }
        }
    }

    private suspend fun claimWriter(id: WalletId, checkpoint: BackupCheckpointV1): WriterLease {
        requireFinancialSession(id)
        val profile = vault.profiles().single { it.id == id }
        return leases.getOrPut(id) {
            SessionLeaseRepository(adaptors.getValue(profile.network)::claim,
                deployment(profile.network).adaptorIdentityHex,
                IosProtocolSigner(vault, id, profile.network), identity.publicKeyHex(), ::wallClockMillis)
        }.claim(checkpoint)
    }

    private suspend fun verifiedWriter(id: WalletId): WriterLease {
        requireFinancialSession(id)
        val checkpoint = backup.verify(id)
        return try { claimWriter(id, checkpoint) }
        finally { checkpoint.ciphertextHash.fill(0); checkpoint.channelSnapshot.fill(0) }
    }

    private suspend fun <T> activeOperation(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        activeOperations += job
        try { block() } finally { activeOperations -= job }
    }

    private suspend fun <T> onlineRefresh(profile: WalletProfile, block: suspend () -> T): T {
        requireFinancialSession(profile.id)
        return coordinators.getValue(profile.network).refresh(block)
    }

    private suspend fun <T> withSnapshot(id: WalletId, action: suspend (ByteArray) -> T): T {
        requireFinancialSession(id)
        val snapshot = journal.backupSnapshot(id)
        try { return activeOperation { action(snapshot) } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { diagnostics.record(DiagnosticCode.BACKUP); throw error }
        finally { snapshot.fill(0) }
    }

    private inline fun <T> BackupCheckpointV1.consume(block: (BackupCheckpointV1) -> T): T =
        try { block(this) } finally { ciphertextHash.fill(0); channelSnapshot.fill(0) }
}

private fun wallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()
private fun operationId(): String = NSUUID().UUIDString.lowercase()
