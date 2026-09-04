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
import io.riverark.ferret.core.model.DiagnosticCode
import io.riverark.ferret.core.model.RuntimeDiagnostics
import io.riverark.ferret.core.model.WalletRepository
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
import java.util.UUID
import java.security.GeneralSecurityException

class MainActivity : FragmentActivity() {
    private val http: HttpClient = ferretHttpClient(Android.create())
    private val connectors = CardanoNetwork.entries.associateWith { ConnectorClient(http, deployment(it)) }
    private val adaptors = CardanoNetwork.entries.associateWith { AdaptorClient(http, deployment(it), AndroidProtocolCrypto) }
    private val coordinators = CardanoNetwork.entries.associateWith {
        RefreshCoordinator(deployment(it), connectors.getValue(it), adaptors.getValue(it))
    }
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
        vault = AndroidSecureVault(this)
        authenticator = AndroidUserAuthenticator(this)
        walletManager = WalletManager(
            vault,
            AndroidSecureRandomSource(),
            AndroidRecoveryPhraseCodec(),
            ::deriveAndroidWallet,
            wallets,
        )
        driveTokens = AndroidGoogleOAuthTokenProvider(this)
        deviceIdentity = AndroidDeviceIdentity(this)
        val backupCrypto = AndroidBackupCrypto()
        backupCoordinator = WalletBackupCoordinator(
            vault,
            DriveBackupRepository(GoogleDriveAppDataClient(http, driveTokens), backupCrypto),
            backupCrypto,
            System::currentTimeMillis,
        )
        l1WalletRepository = DefaultL1WalletRepository(
            wallets,
            vault,
            { profile -> connectors.getValue(profile.network) },
            androidCardanoTransactionEngine(),
            { UUID.randomUUID().toString() },
            System::currentTimeMillis,
        )
        paymentStore = VaultPaymentStore(vault)
        val channelJournal = VaultChannelJournal(vault)
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
                            val l1Operation = l1WalletRepository.operation(walletId)
                            val channel = channelJournal.load(walletId)
                            val transactions = connectors.getValue(profile.network).transactions(profile.paymentAddress)
                            RemovalReadiness(
                                profile,
                                connectors.getValue(profile.network).balance(profile.paymentAddress),
                                l1Operation?.state in setOf(L1OperationState.PREPARED, L1OperationState.SUBMITTING, L1OperationState.PENDING) ||
                                    channel.pending != null || paymentStore.pending(walletId) != null,
                                driveResolved,
                                if (transactions.none { it.state != TransactionState.SETTLED }) 2_160 else 0,
                            )
                        } finally {
                            encrypted.channelRecovery.fill(0)
                            encrypted.operationJournal.fill(0)
                        }
                    }
                },
                sweepWallet = { _, _ -> error("L1 sweep deployment is unavailable") },
                deleteBackup = backupCoordinator::delete,
            ),
            vault,
        )
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(networkCallback)
        setContent {
            FerretApp(
                FerretDependencies(
                    wallets,
                    walletManager,
                    loadBalance = { profile ->
                        try {
                            coordinators.getValue(profile.network).refresh {
                                connectors.getValue(profile.network).balance(profile.paymentAddress)
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
                    l1MutationsAvailable = false,
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
                            claimWriter(walletId, checkpoint)
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
                            val snapshot = channelJournal.restoreFromBackup(walletId, checkpoint.channelSnapshot)
                            val profile = vault.profiles().single { it.id == walletId }
                            vault.updateProfile(profile.copy(channelState = snapshot.state))
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
                            val snapshot = channelJournal.restoreFromBackup(walletId, checkpoint.channelSnapshot)
                            val profile = vault.profiles().single { it.id == walletId }
                            vault.updateProfile(profile.copy(channelState = snapshot.state))
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
                    paymentActionsAvailable = false,
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
                val selected = profiles.firstOrNull { it.id == wallets.selectedWalletId() } ?: profiles.first()
                coordinators.getValue(selected.network).validate(selected)
                l1WalletRepository.reconcilePending(selected.id)
                wallets.publish(selected.id, profiles)
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
        coordinators.values.forEach(RefreshCoordinator::cancelActiveWork)
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

    private fun lockSession() {
        cancelActiveWork()
        vault.lock()
        wallets.lock()
    }

    private companion object {
        const val ADDRESS_CLIPBOARD_MILLIS = 60_000L
    }
}
