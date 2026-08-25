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
import io.riverark.ferret.core.cardano.deriveAndroidWallet
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.WalletRepository
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
import io.riverark.ferret.feature.wallet.DefaultL1WalletRepository
import io.riverark.ferret.core.channel.VaultPaymentStore
import io.riverark.ferret.feature.payment.QrPaymentScannerScreen
import io.riverark.ferret.feature.wallet.addressQrCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.UUID
import java.security.GeneralSecurityException

class MainActivity : FragmentActivity() {
    private val http: HttpClient = ferretHttpClient(Android.create())
    private val connectors = CardanoNetwork.entries.associateWith { ConnectorClient(http, deployment(it)) }
    private val adaptors = CardanoNetwork.entries.associateWith { AdaptorClient(http, deployment(it)) }
    private val coordinators = CardanoNetwork.entries.associateWith {
        RefreshCoordinator(deployment(it), connectors.getValue(it), adaptors.getValue(it))
    }
    private val wallets = WalletRepository()
    private val lockPolicy = ForegroundLockPolicy(SystemClock::elapsedRealtime)
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private lateinit var connectivity: ConnectivityManager
    private lateinit var vault: AndroidSecureVault
    private lateinit var walletManager: WalletManager
    private lateinit var authenticator: AndroidUserAuthenticator
    private lateinit var paymentStore: VaultPaymentStore
    private lateinit var l1WalletRepository: DefaultL1WalletRepository
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
        l1WalletRepository = DefaultL1WalletRepository(
            wallets,
            vault,
            { profile -> connectors.getValue(profile.network) },
            androidCardanoTransactionEngine(),
            { UUID.randomUUID().toString() },
            System::currentTimeMillis,
        )
        paymentStore = VaultPaymentStore(vault)
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
                    wallets.publish(null, profiles)
                    return@launch
                }
                wallets.checkingConnectivity()
                check(hasValidatedNetwork())
                val selected = profiles.firstOrNull { it.id == wallets.selectedWalletId() } ?: profiles.first()
                coordinators.getValue(selected.network).validate(selected)
                l1WalletRepository.reconcilePending(selected.id)
                wallets.publish(selected.id, profiles)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                lockSession()
            } catch (_: GeneralSecurityException) {
                lockSession()
            } catch (_: Exception) {
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
        lifecycleScope.launch {
            cancelActiveWork()
            wallets.offline()
        }
    }

    private fun cancelActiveWork() {
        activeWork?.cancel()
        activeWork = null
        coordinators.values.forEach(RefreshCoordinator::cancelActiveWork)
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
