package io.riverark.ferret

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ferret.shared.generated.resources.Res
import ferret.shared.generated.resources.splash_ferret
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.channel.PaymentUiState
import io.riverark.ferret.core.channel.PaymentViewModel
import io.riverark.ferret.core.model.AppState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.WalletRemovalManager
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.feature.payment.ConfirmPaymentScreen
import io.riverark.ferret.feature.payment.PaymentReceiptScreen
import io.riverark.ferret.feature.wallet.CreateWalletScreen
import io.riverark.ferret.feature.wallet.HomeScreen
import io.riverark.ferret.feature.wallet.QrCode
import io.riverark.ferret.feature.wallet.HomeViewModel
import io.riverark.ferret.feature.wallet.HistoryScreen
import io.riverark.ferret.feature.wallet.L1WalletRepository
import io.riverark.ferret.feature.wallet.HistoryViewModel
import io.riverark.ferret.feature.wallet.RecoveryPhraseScreen
import io.riverark.ferret.feature.wallet.SettingsScreen
import io.riverark.ferret.feature.wallet.WalletSettings
import io.riverark.ferret.feature.wallet.RestoreWalletScreen
import io.riverark.ferret.feature.wallet.RestoreBackupScreen
import io.riverark.ferret.feature.wallet.VerifyRecoveryScreen
import io.riverark.ferret.feature.wallet.TopUpScreen
import io.riverark.ferret.feature.wallet.TransferScreen
import io.riverark.ferret.feature.wallet.TransferViewModel
import io.riverark.ferret.feature.wallet.WalletPickerScreen
import io.riverark.ferret.feature.wallet.WalletPickerViewModel
import io.riverark.ferret.navigation.Route
import io.riverark.ferret.feature.wallet.WalletRemovalScreen
import io.riverark.ferret.feature.wallet.WalletRemovalViewModel
import io.riverark.ferret.ui.FerretEmptyState
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretLoadingState
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretTheme
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class FerretDependencies(
    val wallets: WalletRepository,
    val walletManager: WalletManager?,
    val loadBalance: (suspend (WalletProfile) -> Lovelace)? = null,
    val loadHistory: (suspend (WalletProfile) -> List<TransactionRecord>)? = null,
    val encodeQr: ((String) -> QrCode)? = null,
    val copyAddress: ((String) -> Unit)? = null,
    val l1WalletRepository: L1WalletRepository? = null,
    val l1MutationsAvailable: Boolean = false,
    val paymentViewModelFactory: ((WalletId) -> PaymentViewModel)? = null,
    val invoiceScanner: (@Composable ((String) -> Unit, () -> Unit) -> Unit)? = null,
    val paymentActionsAvailable: Boolean = false,
    val nowEpochMillis: (() -> Long)? = null,
    val newOperationId: (() -> String)? = null,
    val paymentIntentHash: ((PaymentQuote) -> String)? = null,
    val loadSettings: (suspend (WalletProfile) -> WalletSettings)? = null,
    val connectDrive: (suspend () -> String)? = null,
    val verifyBackup: (suspend (WalletId) -> Long)? = null,
    val restoreBackup: (suspend (WalletId) -> Long)? = null,
    val walletRemovalManager: WalletRemovalManager? = null,
)

@Composable
fun FerretApp(
    dependencies: FerretDependencies,
    onUnlock: (() -> Unit)?,
    onSensitiveContentChanged: (Boolean) -> Unit = {},
) {
    FerretTheme {
        val manager = dependencies.walletManager
        if (manager == null) {
            FerretScreen {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(painterResource(Res.drawable.splash_ferret), null, Modifier.size(180.dp))
                    FerretEmptyState("Ferret", "Wallet setup is not available in this iOS build.")
                }
            }
            return@FerretTheme
        }
        val loadBalance = checkNotNull(dependencies.loadBalance) { "Wallet balance loader is unavailable." }
        val loadHistory = checkNotNull(dependencies.loadHistory) { "Wallet history loader is unavailable." }
        val encodeQr = checkNotNull(dependencies.encodeQr) { "QR encoder is unavailable." }
        val copyAddress = checkNotNull(dependencies.copyAddress) { "Clipboard is unavailable." }
        WalletNavigation(
            dependencies.wallets,
            manager,
            loadBalance,
            loadHistory,
            encodeQr,
            copyAddress,
            dependencies.l1WalletRepository,
            dependencies.l1MutationsAvailable,
            dependencies.paymentViewModelFactory,
            dependencies.invoiceScanner,
            dependencies.paymentActionsAvailable,
            dependencies.nowEpochMillis,
            dependencies.newOperationId,
            dependencies.paymentIntentHash,
            dependencies.loadSettings,
            dependencies.connectDrive,
            dependencies.verifyBackup,
            dependencies.restoreBackup,
            dependencies.walletRemovalManager,
            onUnlock,
            onSensitiveContentChanged,
        )
    }
}

@Composable
private fun WalletNavigation(
    repository: WalletRepository,
    manager: WalletManager,
    loadBalance: suspend (WalletProfile) -> Lovelace,
    loadHistory: suspend (WalletProfile) -> List<TransactionRecord>,
    encodeQr: (String) -> QrCode,
    copyAddress: (String) -> Unit,
    l1WalletRepository: L1WalletRepository?,
    l1MutationsAvailable: Boolean,
    paymentViewModelFactory: ((WalletId) -> PaymentViewModel)?,
    invoiceScanner: (@Composable ((String) -> Unit, () -> Unit) -> Unit)?,
    paymentActionsAvailable: Boolean,
    nowEpochMillis: (() -> Long)?,
    newOperationId: (() -> String)?,
    paymentIntentHash: ((PaymentQuote) -> String)?,
    loadSettings: (suspend (WalletProfile) -> WalletSettings)?,
    connectDrive: (suspend () -> String)?,
    verifyBackup: (suspend (WalletId) -> Long)?,
    restoreBackup: (suspend (WalletId) -> Long)?,
    walletRemovalManager: WalletRemovalManager?,
    onUnlock: (() -> Unit)?,
    onSensitiveContentChanged: (Boolean) -> Unit,
) {
    val state by repository.state.collectAsState()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val walletViewModel = viewModel { WalletPickerViewModel(manager) }
    val pickerState by walletViewModel.state.collectAsState()
    val activeWalletId = (state as? AppState.Ready)?.activeWalletId
    val paymentViewModel = if (activeWalletId != null && paymentViewModelFactory != null) {
        viewModel(key = "payment-${activeWalletId.value}") { paymentViewModelFactory(activeWalletId) }
    } else {
        null
    }
    val paymentState = paymentViewModel?.state?.collectAsState()?.value

    LaunchedEffect(Unit) { onUnlock?.invoke() }
    LaunchedEffect(state, destination?.route) {
        val target: Route? = when (val current = state) {
            AppState.Locked -> Route.Unlock.takeUnless { destination.has<Route.Unlock>() }
            AppState.CheckingConnectivity -> Route.CheckingConnectivity.takeUnless { destination.has<Route.CheckingConnectivity>() }
            AppState.Offline -> Route.Offline.takeUnless { destination.has<Route.Offline>() }
            AppState.NoWallets -> Route.WalletPicker.takeUnless {
                destination.has<Route.WalletPicker>() || destination.has<Route.CreateWallet>() || destination.has<Route.RestoreWallet>()
            }
            is AppState.Ready -> {
                val profile = current.wallets.first { it.id == current.activeWalletId }
                if (!profile.recoveryPhraseConfirmed) {
                    Route.RecoveryPhrase(profile.id.value).takeUnless {
                        destination.has<Route.RecoveryPhrase>() || destination.has<Route.VerifyRecovery>()
                    }
                } else {
                    Route.Home(profile.id.value).takeIf {
                        destination.has<Route.Unlock>() || destination.has<Route.CheckingConnectivity>() || destination.has<Route.Offline>() ||
                            destination.has<Route.RecoveryPhrase>() || destination.has<Route.VerifyRecovery>()
                    }
                }
            }
        }
        target?.let {
            navController.navigate(it) {
                launchSingleTop = true
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
        }
    }

    NavHost(navController, startDestination = Route.Unlock) {
        composable<Route.Unlock> {
            SensitiveContent(onSensitiveContentChanged) {
                FerretScreen {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Image(painterResource(Res.drawable.splash_ferret), null, Modifier.size(180.dp))
                        Text("Ferret", style = MaterialTheme.typography.displaySmall)
                        Text("Your wallet stays encrypted on this device.", textAlign = TextAlign.Center)
                    }
                    if (onUnlock != null) FerretPrimaryButton("Unlock", onUnlock)
                }
            }
        }
        composable<Route.CheckingConnectivity> { FerretScreen { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FerretLoadingState("Checking secure connection") } } }
        composable<Route.Offline> { FerretScreen { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FerretErrorState("Ferret is offline. Wallet access is unavailable.", "Retry", onUnlock) } } }
        composable<Route.WalletPicker> {
            LaunchedEffect(Unit) { walletViewModel.load() }
            WalletPickerScreen(
                pickerState,
                { profile ->
                    walletViewModel.select(profile.id) {
                        navController.navigate(Route.Home(profile.id.value)) {
                            launchSingleTop = true
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    }
                },
                { navController.navigate(Route.CreateWallet) },
                { navController.navigate(Route.RestoreWallet) },
            )
        }
        composable<Route.CreateWallet> {
            CreateWalletScreen(
                pickerState.busy,
                pickerState.error,
                { navController.popBackStack() },
                { name, network -> walletViewModel.create(name, network) {} },
            )
        }
        composable<Route.RestoreWallet> {
            SensitiveContent(onSensitiveContentChanged) {
                RestoreWalletScreen(
                    pickerState.busy,
                    pickerState.error,
                    { navController.popBackStack() },
                    { name, network, words ->
                        walletViewModel.restore(name, network, words) { profile ->
                            navController.navigate(Route.RestoreBackup(profile.id.value)) {
                                launchSingleTop = true
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            }
                        }
                    },
                )
            }
        }
        composable<Route.RestoreBackup> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.RestoreBackup>()
            val profile = (state as? AppState.Ready)?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null && connectDrive != null && restoreBackup != null) {
                val recoveryScope = rememberCoroutineScope()
                var accountConnected by remember(profile.id) { mutableStateOf(false) }
                var busy by remember(profile.id) { mutableStateOf(false) }
                var message by remember(profile.id) { mutableStateOf<String?>(null) }
                val continueToWallet = {
                    navController.navigate(Route.Home(profile.id.value)) {
                        launchSingleTop = true
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                }
                RestoreBackupScreen(
                    profile,
                    accountConnected,
                    busy,
                    message,
                    {
                        recoveryScope.launch {
                            busy = true
                            message = null
                            try {
                                connectDrive()
                                accountConnected = true
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                message = "Google Drive connection failed."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    {
                        recoveryScope.launch {
                            busy = true
                            message = null
                            try {
                                restoreBackup(profile.id)
                                continueToWallet()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                message = "No usable backup was found. A missing, conflicting, or modified backup is never restored."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    continueToWallet,
                )
            }
        }
        composable<Route.RecoveryPhrase> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.RecoveryPhrase>()
            var words by remember(route.walletId) { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(route.walletId) { walletViewModel.recoveryWords(WalletId(route.walletId)) { words = it } }
            SensitiveContent(onSensitiveContentChanged) {
                RecoveryPhraseScreen(words, pickerState.busy, pickerState.error) {
                    navController.navigate(Route.VerifyRecovery(route.walletId))
                }
            }
        }
        composable<Route.VerifyRecovery> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.VerifyRecovery>()
            var words by remember(route.walletId) { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(route.walletId) { walletViewModel.recoveryWords(WalletId(route.walletId)) { words = it } }
            SensitiveContent(onSensitiveContentChanged) {
                VerifyRecoveryScreen(words, pickerState.busy, pickerState.error) {
                    walletViewModel.confirmRecoveryPhrase(WalletId(route.walletId))
                }
            }
        }
        composable<Route.Home> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Home>()
            val ready = state as? AppState.Ready
            val profile = ready?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null) {
                val homeViewModel = viewModel { HomeViewModel(profile, loadBalance, loadHistory, nowEpochMillis ?: { 0L }) }
                val homeState by homeViewModel.state.collectAsState()
                LaunchedEffect(homeViewModel) { homeViewModel.refresh() }
                HomeScreen(
                    homeState,
                    homeViewModel::refresh,
                    { navController.navigate(Route.TopUp(profile.id.value)) },
                    if (paymentActionsAvailable && paymentViewModel != null && invoiceScanner != null) {
                        { navController.navigate(Route.ScanInvoice(profile.id.value)) }
                    } else {
                        null
                    },
                    if (l1MutationsAvailable && l1WalletRepository != null) {
                        { navController.navigate(Route.Transfer(profile.id.value)) }
                    } else {
                        null
                    },
                    { navController.navigate(Route.History(profile.id.value)) },
                    { navController.navigate(Route.WalletPicker) },
                    { navController.navigate(Route.Settings(profile.id.value)) },
                )
            }
        }
        composable<Route.TopUp> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.TopUp>()
            val profile = (state as? AppState.Ready)?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null) {
                val qrCode = remember(profile.paymentAddress) { encodeQr(profile.paymentAddress) }
                TopUpScreen(profile, qrCode, navController::popBackStack) { copyAddress(profile.paymentAddress) }
            }
        }
        composable<Route.Transfer> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Transfer>()
            val ready = state as? AppState.Ready
            val profile = ready?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null && l1WalletRepository != null && l1MutationsAvailable) {
                val transferViewModel = viewModel { TransferViewModel(profile.id, profile.network, l1WalletRepository) }
                val transferState by transferViewModel.state.collectAsState()
                SensitiveContent(onSensitiveContentChanged) {
                    TransferScreen(
                        profile,
                        transferViewModel.destinations(ready.wallets),
                        transferState,
                        transferViewModel::previewAsync,
                        transferViewModel::submitAsync,
                        navController::popBackStack,
                    )
                }
            }
        }
        composable<Route.ScanInvoice> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.ScanInvoice>()
            if (
                paymentActionsAvailable && paymentViewModel != null && invoiceScanner != null &&
                nowEpochMillis != null && activeWalletId?.value == route.walletId
            ) {
                when (val current = paymentState) {
                    PaymentUiState.Scanning -> invoiceScanner(
                        { paymentViewModel.scanned(it, nowEpochMillis()) },
                        { paymentViewModel.scanAgain() },
                    )
                    is PaymentUiState.Confirming -> {
                        LaunchedEffect(current.quote.id) { navController.navigate(Route.ConfirmPayment(route.walletId)) }
                        FerretScreen { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FerretLoadingState("Preparing payment") } }
                    }
                    is PaymentUiState.Processing -> FerretScreen { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FerretLoadingState("Reconciling payment") } }
                    is PaymentUiState.Complete -> {
                        LaunchedEffect(current.receipt.operationId) {
                            navController.navigate(Route.PaymentReceipt(route.walletId, current.receipt.operationId))
                        }
                    }
                    is PaymentUiState.Error -> FerretScreen {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FerretErrorState(current.message) }
                        FerretPrimaryButton("Scan another QR", paymentViewModel::scanAgain)
                    }
                    null -> Unit
                }
            }
        }
        composable<Route.ConfirmPayment> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.ConfirmPayment>()
            val current = paymentState
            if (
                paymentActionsAvailable && paymentViewModel != null && current is PaymentUiState.Confirming &&
                nowEpochMillis != null && newOperationId != null && paymentIntentHash != null &&
                activeWalletId?.value == route.walletId
            ) {
                var guardComplete by remember(current.quote.id) { mutableStateOf(false) }
                LaunchedEffect(current.confirmAfterEpochMillis) {
                    delay(maxOf(0, current.confirmAfterEpochMillis - nowEpochMillis()))
                    guardComplete = true
                }
                SensitiveContent(onSensitiveContentChanged) {
                    ConfirmPaymentScreen(current.description, current.quote, guardComplete) {
                        paymentViewModel.confirm(newOperationId(), paymentIntentHash(current.quote), nowEpochMillis())
                    }
                }
            } else if (current is PaymentUiState.Complete) {
                LaunchedEffect(current.receipt.operationId) {
                    navController.navigate(Route.PaymentReceipt(route.walletId, current.receipt.operationId))
                }
            }
        }
        composable<Route.PaymentReceipt> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.PaymentReceipt>()
            val complete = paymentState as? PaymentUiState.Complete
            if (complete?.receipt?.operationId == route.operationId && activeWalletId?.value == route.walletId) {
                SensitiveContent(onSensitiveContentChanged) {
                    PaymentReceiptScreen(complete.receipt) {
                        navController.navigate(Route.Home(route.walletId)) {
                            launchSingleTop = true
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    }
                }
            }
        }
        composable<Route.Settings> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Settings>()
            val profile = (state as? AppState.Ready)?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null && loadSettings != null) {
                val settingsScope = rememberCoroutineScope()
                var settings by remember(profile) { mutableStateOf<WalletSettings?>(null) }
                var backupBusy by remember(profile.id) { mutableStateOf(false) }
                var backupMessage by remember(profile.id) { mutableStateOf<String?>(null) }
                LaunchedEffect(profile) { settings = loadSettings(profile) }
                settings?.let { current ->
                    SettingsScreen(
                        current,
                        backupBusy,
                        backupMessage,
                        navController::popBackStack,
                        { walletViewModel.rename(profile.id, it) },
                        connectDrive?.let { connect ->
                            {
                                settingsScope.launch {
                                    backupBusy = true
                                    backupMessage = null
                                    try {
                                        connect()
                                        settings = loadSettings(profile)
                                        backupMessage = "Google Drive connected."
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        backupMessage = "Google Drive connection failed."
                                    } finally {
                                        backupBusy = false
                                    }
                                }
                            }
                        },
                        verifyBackup?.takeIf { current.driveAccount != null }?.let { verify ->
                            {
                                settingsScope.launch {
                                    backupBusy = true
                                    backupMessage = null
                                    try {
                                        val sequence = verify(profile.id)
                                        settings = loadSettings(profile)
                                        backupMessage = "Encrypted backup verified (sequence $sequence)."
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        backupMessage = "Encrypted backup verification failed."
                                    } finally {
                                        backupBusy = false
                                    }
                                }
                            }
                        },
                        { navController.navigate(Route.RemoveWallet(profile.id.value)) },
                    )
                } ?: FerretScreen {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FerretLoadingState("Loading settings") }
                }
            }
        }
        composable<Route.RemoveWallet> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.RemoveWallet>()
            val profile = (state as? AppState.Ready)?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null && walletRemovalManager != null) {
                val removalViewModel = viewModel { WalletRemovalViewModel(profile.id, walletRemovalManager) }
                val removalState by removalViewModel.state.collectAsState()
                LaunchedEffect(removalViewModel) { removalViewModel.load() }
                SensitiveContent(onSensitiveContentChanged) {
                    WalletRemovalScreen(
                        removalState,
                        navController::popBackStack,
                        removalViewModel::sweep,
                    ) {
                        removalViewModel.remove {
                            walletViewModel.load()
                            navController.navigate(Route.WalletPicker) {
                                launchSingleTop = true
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            }
                        }
                    }
                }
            }
        }
        composable<Route.History> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.History>()
            val profile = (state as? AppState.Ready)?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null) {
                val historyViewModel = viewModel { HistoryViewModel(profile, loadHistory, nowEpochMillis ?: { 0L }) }
                val historyState by historyViewModel.state.collectAsState()
                LaunchedEffect(historyViewModel) { historyViewModel.refresh() }
                HistoryScreen(historyState, historyViewModel::refresh, navController::popBackStack)
            }
        }
    }
}

private inline fun <reified T : Any> NavDestination?.has() =
    this?.route?.substringBefore('/')?.substringBefore('?') == T::class.qualifiedName

@Composable
private fun SensitiveContent(onChanged: (Boolean) -> Unit, content: @Composable () -> Unit) {
    DisposableEffect(onChanged) {
        onChanged(true)
        onDispose { onChanged(false) }
    }
    content()
}
