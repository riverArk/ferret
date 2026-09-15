package io.riverark.ferret

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.riverark.ferret.core.cardano.androidCardanoTransactionEngine
import io.riverark.ferret.core.cardano.AndroidProtocolCrypto
import io.riverark.ferret.core.cardano.AndroidProtocolSigner
import io.riverark.ferret.core.backup.AndroidGoogleOAuthTokenProvider
import io.riverark.ferret.core.backup.DriveBackupRepository
import io.riverark.ferret.core.backup.GoogleDriveAppDataClient
import io.riverark.ferret.core.backup.WalletBackupCoordinator
import io.riverark.ferret.core.backup.BackupCheckpointV1
import io.riverark.ferret.core.cardano.deriveAndroidWallet
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.AppState
import io.riverark.ferret.core.model.DiagnosticCode
import io.riverark.ferret.core.model.RuntimeDiagnostics
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.loadEmbeddedAssetCatalog
import io.riverark.ferret.core.model.DefaultWalletRemovalRepository
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.RemovalReadiness
import io.riverark.ferret.core.model.TransactionState
import io.riverark.ferret.core.model.WalletRemovalManager
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.network.ConnectorClient
import io.riverark.ferret.core.network.AdaptorClient
import io.riverark.ferret.core.network.deployment
import io.riverark.ferret.core.network.ferretHttpClient
import io.riverark.ferret.core.network.RefreshCoordinator
import io.riverark.ferret.core.security.AndroidRecoveryPhraseCodec
import io.riverark.ferret.core.security.AndroidSecureRandomSource
import io.riverark.ferret.core.security.AndroidSecureVault
import io.riverark.ferret.core.security.ForegroundLockPolicy
import io.riverark.ferret.core.security.SensitiveContentCounter
import io.riverark.ferret.core.security.AndroidUserAuthenticator
import io.riverark.ferret.core.security.AndroidBackupCrypto
import io.riverark.ferret.core.channel.VaultChannelJournal
import io.riverark.ferret.core.channel.AdaptorChannelRemote
import io.riverark.ferret.core.channel.ChannelRepository
import io.riverark.ferret.core.channel.DriveChannelBackupProtocol
import io.riverark.ferret.core.channel.OpenChannelTransactions
import io.riverark.ferret.core.channel.DefaultPaymentGateway
import io.riverark.ferret.core.channel.PaymentViewModel
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.security.AndroidDeviceIdentity
import io.riverark.ferret.core.channel.SessionLeaseRepository
import io.riverark.ferret.core.channel.WriterLease
import io.riverark.ferret.feature.wallet.L1OperationState
import io.riverark.ferret.feature.wallet.DefaultL1WalletRepository
import io.riverark.ferret.core.channel.VaultPaymentStore
import io.riverark.ferret.feature.payment.QrPaymentScannerScreen
import io.riverark.ferret.feature.wallet.addressQrCode
import io.riverark.ferret.feature.wallet.WalletSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.security.GeneralSecurityException

class MainActivity : FragmentActivity() {
    private val http: HttpClient = ferretHttpClient(Android.create())
    private val connectors = CardanoNetwork.entries.associateWith { ConnectorClient(http, deployment(it)) }
    private val adaptors = CardanoNetwork.entries.associateWith { AdaptorClient(http, deployment(it), AndroidProtocolCrypto) }
    private lateinit var assetCatalog: AssetCatalog
    private lateinit var coordinators: Map<CardanoNetwork, RefreshCoordinator>
    private val wallets = WalletRepository()
    private val lockPolicy = ForegroundLockPolicy(SystemClock::elapsedRealtime)
    private val diagnostics = RuntimeDiagnostics()
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private lateinit var connectivity: ConnectivityManager
    private lateinit var vault: AndroidSecureVault
    private lateinit var walletManager: WalletManager
    private lateinit var authenticator: AndroidUserAuthenticator
    private lateinit var paymentStore: VaultPaymentStore
    private lateinit var l1WalletRepository: DefaultL1WalletRepository
    private lateinit var channelRepository: ChannelRepository
    private lateinit var walletRemovalManager: WalletRemovalManager
    private lateinit var driveTokens: AndroidGoogleOAuthTokenProvider
    private lateinit var backupCoordinator: WalletBackupCoordinator
    private lateinit var deviceIdentity: AndroidDeviceIdentity
    private val sessionLeases = mutableMapOf<WalletId, SessionLeaseRepository>()
    private var activeWork: Job? = null
    private var backgroundLock: Job? = null
    private var unlocking = false
    private val sensitiveContent = SensitiveContentCounter()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = networkUnavailable()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                networkUnavailable()
            } else if (
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                ::vault.isInitialized &&
                vault.isUnlocked &&
                wallets.state.value == io.riverark.ferret.core.model.AppState.Offline
            ) {
                startOnlineSession(authenticate = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        assetCatalog = try {
            runBlocking { loadEmbeddedAssetCatalog(AndroidProtocolCrypto) }
        } catch (_: Exception) {
            setContentView(android.widget.TextView(this).apply {
                text = "Bundled asset catalog is invalid."
            })
            return
        }
        coordinators = CardanoNetwork.entries.associateWith {
            RefreshCoordinator(deployment(it), assetCatalog, connectors.getValue(it), adaptors.getValue(it))
        }
        vault = AndroidSecureVault(this, assetCatalog)
        authenticator = AndroidUserAuthenticator(this)
        val walletSelection = getSharedPreferences("wallet-selection", Context.MODE_PRIVATE)
        walletManager = WalletManager(
            vault,
            AndroidSecureRandomSource(),
            AndroidRecoveryPhraseCodec(),
            ::deriveAndroidWallet,
            wallets,
            { walletSelection.getString("active-wallet-id", null)?.let(::WalletId) },
            { walletId ->
                walletSelection.edit().putString("active-wallet-id", walletId?.value).apply()
            },
        )
        driveTokens = AndroidGoogleOAuthTokenProvider(this)
        deviceIdentity = AndroidDeviceIdentity(this)
        val backupCrypto = AndroidBackupCrypto()
        val channelJournal = VaultChannelJournal(vault, assetCatalog)
        backupCoordinator = WalletBackupCoordinator(
            vault,
            DriveBackupRepository(GoogleDriveAppDataClient(http, driveTokens), backupCrypto),
            backupCrypto,
            System::currentTimeMillis,
            channelJournal::normalizeBackup,
            { id, bytes, checkpoint -> channelJournal.installBackup(id, bytes, checkpoint) },
        )
        paymentStore = VaultPaymentStore(channelJournal)
        val cardanoEngine = androidCardanoTransactionEngine { network, cbor ->
            connectors.getValue(network).evaluate(cbor.joinToString("") { byte ->
                byte.toUByte().toString(16).padStart(2, '0')
            })
        }
        l1WalletRepository = DefaultL1WalletRepository(
            wallets,
            vault,
            assetCatalog,
            { profile -> connectors.getValue(profile.network) },
            cardanoEngine,
            { UUID.randomUUID().toString() },
            System::currentTimeMillis,
            { walletId -> channelJournal.load(walletId).channels.values.any { it.pending != null } },
        )
        val random = AndroidSecureRandomSource()
        val openTransactions = OpenChannelTransactions(
            vault,
            cardanoEngine,
            assetCatalog,
            loadLedger = { profile ->
                val connector = connectors.getValue(profile.network)
                val walletLedger = connector.ledger(profile.paymentAddress, profile.network)
                val outputs = walletLedger.utxos +
                    connector.utxos(deployment(profile.network).scriptDeploymentAddress).map { it.ledger() } +
                    connector.utxos(deployment(profile.network).validatorAddress).map { it.ledger() }
                require(outputs.map { it.transactionId to it.index }.distinct().size == outputs.size)
                walletLedger.copy(utxos = outputs)
            },
            loadInfo = { profile -> adaptors.getValue(profile.network).info() },
            verificationKey = { profile ->
                AndroidProtocolSigner(vault, profile.id, profile.network).verificationKeyHex()
            },
            availability = ::requireOpenAvailable,
            newTag = { random.bytes(32) },
            nowEpochMillis = System::currentTimeMillis,
        )
        channelRepository = ChannelRepository(
            wallets,
            channelJournal,
            DriveChannelBackupProtocol(backupCoordinator, ::claimWriter, channelJournal),
            AdaptorChannelRemote(
                { walletId ->
                    val profile = vault.profiles().single { it.id == walletId }
                    adaptors.getValue(profile.network)
                },
                AndroidProtocolCrypto,
            ),
            { UUID.randomUUID().toString() },
            openTransactions,
        )
        walletRemovalManager = WalletRemovalManager(
            DefaultWalletRemovalRepository(
                loadReadiness = { walletId ->
                    val profile = vault.profiles().single { it.id == walletId }
                    coordinators.getValue(profile.network).refresh {
                        val encrypted = vault.walletState(walletId)
                        try {
                            val driveResolved = if (encrypted.backupGeneration == 0L) {
                                true
                            } else {
                                try {
                                    val checkpoint = backupCoordinator.verify(walletId)
                                    checkpoint.ciphertextHash.fill(0)
                                    checkpoint.channelSnapshot.fill(0)
                                    true
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    false
                                }
                            }
                            val l1Operations = l1WalletRepository.operations(walletId)
                            val channels = channelJournal.load(walletId)
                            val balance = l1WalletRepository.balance(walletId)
                            val adaBalance = balance.assets.single { it.total.asset == assetCatalog.ada }
                            val connector = connectors.getValue(profile.network)
                            val transactionIds = (
                                connector.transactions(profile.paymentAddress, assetCatalog).map { it.id } +
                                    l1Operations.mapNotNull { it.expectedTransactionId } +
                                    channels.channels.values.flatMap { entry -> entry.history.mapNotNull { it.transactionId } }
                                ).distinct()
                            RemovalReadiness(
                                profile = profile,
                                spendable = adaBalance.spendable,
                                l1Assets = balance.assets.map { it.total },
                                unsupportedAssets = balance.unsupportedAssets,
                                channels = channels,
                                pendingL1Operation = l1Operations.any {
                                    it.state in setOf(L1OperationState.PREPARED, L1OperationState.SUBMITTING, L1OperationState.PENDING)
                                },
                                driveResolved = driveResolved,
                                mutationDepths = transactionIds.map { connector.transaction(it)?.depth ?: 0 },
                            )
                        } finally {
                            encrypted.channelRecovery.fill(0)
                            encrypted.operationJournal.fill(0)
                        }
                    }
                },
                previewer = l1WalletRepository::previewSweep,
                submitter = l1WalletRepository::submitSweep,
                deleteBackup = backupCoordinator::delete,
            ),
            vault,
            wallets,
        )
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(networkCallback)
        setContent {
            FerretApp(
                FerretDependencies(
                    wallets,
                    walletManager,
                    assetCatalog,
                    loadBalance = { profile ->
                        try {
                            coordinators.getValue(profile.network).refresh {
                                l1WalletRepository.reconcilePending(profile.id)
                                channelRepository.reconcileAll(profile.id)
                                channelRepository.load(profile.id)
                                l1WalletRepository.balance(profile.id)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            networkUnavailable()
                            throw CancellationException("wallet session offline")
                        }
                    },
                    loadHistory = { profile ->
                        try {
                            coordinators.getValue(profile.network).refresh {
                                l1WalletRepository.reconcilePending(profile.id)
                                (l1WalletRepository.history(profile.id) + paymentStore.history(profile.id))
                                    .sortedWith(compareByDescending<io.riverark.ferret.core.model.TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            networkUnavailable()
                            throw CancellationException("wallet session offline")
                        }
                    },
                    encodeQr = ::addressQrCode,
                    copyAddress = ::copyAddress,
                    l1WalletRepository = l1WalletRepository,
                    loadChannels = { walletId ->
                        channelRepository.reconcileAll(walletId)
                        channelRepository.load(walletId)
                        channelRepository.snapshots.value.getValue(walletId)
                    },
                    cleanupInactiveChannels = channelRepository::cleanupInactive,
                    paymentViewModelFactory = { walletId ->
                        val profile = currentProfile(walletId)
                        PaymentViewModel(
                            walletId,
                            channelRepository,
                            DefaultPaymentGateway(
                                adaptor = { adaptors.getValue(profile.network) },
                                selected = { id, keytag ->
                                    channelRepository.load(id)
                                    channelRepository.snapshots.value.getValue(id).channels.getValue(keytag.value)
                                },
                                writer = { verifiedWriter(it) },
                                signer = { AndroidProtocolSigner(vault, it, profile.network) },
                                network = { profile.network },
                                chain = if (profile.network == CardanoNetwork.MAINNET) "mainnet" else "testnet",
                                crypto = AndroidProtocolCrypto,
                                assets = assetCatalog,
                            ),
                            System::currentTimeMillis,
                        )
                    },
                    loadPaymentReceipt = paymentStore::receipt,
                    previewOpenChannel = { walletId, amount ->
                        val profile = currentProfile(walletId)
                        coordinators.getValue(profile.network).refresh {
                            channelRepository.previewOpen(walletId, amount)
                        }
                    },
                    submitOpenChannel = { walletId, preview ->
                        val profile = currentProfile(walletId)
                        coordinators.getValue(profile.network).refresh {
                            channelRepository.submit(walletId, preview)
                        }
                    },
                    invoiceScanner = { onInvoice, onError -> QrPaymentScannerScreen(onInvoice, onError) },
                    nowEpochMillis = System::currentTimeMillis,
                    loadSettings = { profile ->
                        val encrypted = vault.walletState(profile.id)
                        try {
                            val checkpoint = backupCoordinator.checkpoint(profile.id)
                            try {
                                WalletSettings(
                                    profile = profile,
                                    paymentCredential = profile.id.value.substringAfter('-'),
                                    stakingCredential = profile.stakeAddress,
                                    balance = l1WalletRepository.balance(profile.id),
                                    channels = channelJournal.load(profile.id),
                                    adaptorStatus = "validated",
                                    driveAccount = driveTokens.accountName,
                                    driveGeneration = checkpoint?.generation,
                                    driveSequence = checkpoint?.sequence,
                                    lockStatus = "unlocked",
                                    version = BuildConfig.VERSION_NAME,
                                    buildCommit = BuildConfig.BUILD_COMMIT,
                                    diagnosticCode = diagnostics.code.value?.value,
                                )
                            } finally {
                                checkpoint?.ciphertextHash?.fill(0)
                                checkpoint?.channelSnapshot?.fill(0)
                            }
                        } finally {
                            encrypted.channelRecovery.fill(0)
                            encrypted.operationJournal.fill(0)
                        }
                    },
                    connectDrive = driveTokens::connect,
                    verifyBackup = { walletId ->
                        val snapshot = channelJournal.backupSnapshot(walletId)
                        try {
                            val checkpoint = backupCoordinator.initializeOrVerify(walletId, snapshot)
                            try {
                                checkpoint.sequence
                            } finally {
                                checkpoint.ciphertextHash.fill(0)
                                checkpoint.channelSnapshot.fill(0)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            diagnostics.record(DiagnosticCode.BACKUP)
                            throw error
                        } finally {
                            snapshot.fill(0)
                        }
                    },
                    replaceMissingBackup = { walletId ->
                        val snapshot = channelJournal.backupSnapshot(walletId)
                        try {
                            val checkpoint = backupCoordinator.replaceMissing(walletId, snapshot)
                            try {
                                checkpoint.sequence
                            } finally {
                                checkpoint.ciphertextHash.fill(0)
                                checkpoint.channelSnapshot.fill(0)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            diagnostics.record(DiagnosticCode.BACKUP)
                            throw error
                        } finally {
                            snapshot.fill(0)
                        }
                    },
                    takeoverBackup = { walletId ->
                        val checkpoint = backupCoordinator.takeover(walletId)
                        try {
                            claimWriter(walletId, checkpoint)
                            walletManager.load(walletId)
                            checkpoint.generation
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            diagnostics.record(DiagnosticCode.BACKUP)
                            throw error
                        } finally {
                            checkpoint.ciphertextHash.fill(0)
                            checkpoint.channelSnapshot.fill(0)
                        }
                    },
                    restoreBackup = { walletId ->
                        val checkpoint = backupCoordinator.restore(walletId)
                        try {
                            walletManager.load(walletId)
                            checkpoint.sequence
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            diagnostics.record(DiagnosticCode.BACKUP)
                            throw error
                        } finally {
                            checkpoint.ciphertextHash.fill(0)
                            checkpoint.channelSnapshot.fill(0)
                        }
                    },
                    walletRemovalManager = walletRemovalManager,
                ),
                ::unlock,
                ::setSensitiveContent,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        backgroundLock?.cancel()
        if (lockPolicy.foregrounded()) {
            lockSession()
        } else if (::vault.isInitialized && vault.isUnlocked) {
            startOnlineSession(authenticate = false)
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && ::vault.isInitialized && vault.isUnlocked) {
            lockPolicy.backgrounded()
            cancelActiveWork()
            wallets.checkingConnectivity()
            backgroundLock = lifecycleScope.launch {
                delay(checkNotNull(lockPolicy.millisUntilLock()))
                if (lockPolicy.shouldLock()) lockSession()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelActiveWork()
        backgroundLock?.cancel()
        if (::connectivity.isInitialized) connectivity.unregisterNetworkCallback(networkCallback)
        if (::vault.isInitialized) vault.lock()
        http.close()
        super.onDestroy()
    }

    private fun setSensitiveContent(sensitive: Boolean) {
        if (sensitiveContent.update(sensitive)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun copyAddress(address: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = "Ferret address ${UUID.randomUUID()}"
        val clip = ClipData.newPlainText(label, address).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        clipboardHandler.postDelayed({
            val current = clipboard.primaryClip
            if (
                current?.description?.label?.toString() == label &&
                current.getItemAt(0).text?.toString() == address
            ) {
                clipboard.clearPrimaryClip()
            }
        }, ADDRESS_CLIPBOARD_MILLIS)
    }


    private fun unlock() = startOnlineSession(authenticate = !vault.isUnlocked)

    private fun startOnlineSession(authenticate: Boolean) {
        if (activeWork?.isActive == true || (authenticate && unlocking)) return
        if (authenticate) unlocking = true
        activeWork = lifecycleScope.launch {
            try {
                if (authenticate) {
                    val vaultKey = authenticator.authenticate("Authenticate to access your wallets")
                    try {
                        vault.unlock(vaultKey)
                    } finally {
                        vaultKey.fill(0)
                    }
                }
                val profiles = vault.profiles()
                if (profiles.isEmpty()) {
                    diagnostics.clear()
                    wallets.publish(null, profiles)
                    return@launch
                }
                wallets.checkingConnectivity()
                if (!hasValidatedNetwork()) {
                    diagnostics.record(DiagnosticCode.CONNECTIVITY)
                    error("validated network unavailable")
                }
                val selected = requireNotNull(walletManager.selectedProfile(profiles))
                coordinators.getValue(selected.network).validate(selected)
                channelRepository.load(selected.id)
                l1WalletRepository.reconcilePending(selected.id)
                channelRepository.reconcileAll(selected.id)
                walletManager.load(selected.id)
                diagnostics.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                diagnostics.record(DiagnosticCode.AUTHENTICATION)
                lockSession()
            } catch (_: GeneralSecurityException) {
                diagnostics.record(DiagnosticCode.KEYSTORE)
                lockSession()
            } catch (_: Exception) {
                if (diagnostics.code.value != DiagnosticCode.CONNECTIVITY) diagnostics.record(DiagnosticCode.DEPLOYMENT)
                if (vault.isUnlocked) wallets.offline() else wallets.lock()
            } finally {
                if (authenticate) unlocking = false
            }
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun networkUnavailable() {
        if (!::vault.isInitialized || !vault.isUnlocked) return
        diagnostics.record(DiagnosticCode.CONNECTIVITY)
        lifecycleScope.launch {
            cancelActiveWork()
            wallets.offline()
        }
    }

    private fun cancelActiveWork() {
        activeWork?.cancel()
        activeWork = null
        if (::coordinators.isInitialized) coordinators.values.forEach(RefreshCoordinator::cancelActiveWork)
        sessionLeases.values.forEach(SessionLeaseRepository::clear)
    }

    private suspend fun claimWriter(walletId: WalletId, checkpoint: BackupCheckpointV1): WriterLease {
        val profile = vault.profiles().single { it.id == walletId }
        return sessionLeases.getOrPut(walletId) {
            SessionLeaseRepository(
                adaptors.getValue(profile.network)::claim,
                deployment(profile.network).adaptorIdentityHex,
                AndroidProtocolSigner(vault, walletId, profile.network),
                deviceIdentity.publicKeyHex(),
                System::currentTimeMillis,
            )
        }.claim(checkpoint)
    }

    private suspend fun requireOpenAvailable(walletId: WalletId) {
        require(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "host is backgrounded" }
        require(hasValidatedNetwork()) { "validated network unavailable" }
        val ready = wallets.state.value as? AppState.Ready ?: error("wallet session unavailable")
        require(ready.activeWalletId == walletId) { "wallet is not selected" }
        require(ready.wallets.single { it.id == walletId }.network == CardanoNetwork.MAINNET)
        require(l1WalletRepository.operations(walletId).none {
            it.state in setOf(L1OperationState.PREPARED, L1OperationState.SUBMITTING, L1OperationState.PENDING)
        }) { "L1 operation is unresolved" }
    }

    private fun currentProfile(walletId: WalletId): WalletProfile =
        ((wallets.state.value as? AppState.Ready)?.wallets?.singleOrNull { it.id == walletId })
            ?: error("wallet profile unavailable")


    private suspend fun verifiedWriter(walletId: WalletId): WriterLease {
        val checkpoint = backupCoordinator.verify(walletId)
        return try {
            claimWriter(walletId, checkpoint)
        } finally {
            checkpoint.ciphertextHash.fill(0)
            checkpoint.channelSnapshot.fill(0)
        }
    }

    private fun lockSession() {
        cancelActiveWork()
        vault.lock()
        wallets.lock()
    }

    private companion object {
        const val ADDRESS_CLIPBOARD_MILLIS = 60_000L
    }
}
