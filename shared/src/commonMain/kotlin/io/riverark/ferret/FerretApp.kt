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
import io.riverark.ferret.core.model.AppState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletManager
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.feature.wallet.CreateWalletScreen
import io.riverark.ferret.feature.wallet.HomeScreen
import io.riverark.ferret.feature.wallet.QrCode
import io.riverark.ferret.feature.wallet.HomeViewModel
import io.riverark.ferret.feature.wallet.HistoryScreen
import io.riverark.ferret.feature.wallet.HistoryViewModel
import io.riverark.ferret.feature.wallet.RecoveryPhraseScreen
import io.riverark.ferret.feature.wallet.RestoreWalletScreen
import io.riverark.ferret.feature.wallet.VerifyRecoveryScreen
import io.riverark.ferret.feature.wallet.TopUpScreen
import io.riverark.ferret.feature.wallet.WalletPickerScreen
import io.riverark.ferret.feature.wallet.WalletPickerViewModel
import io.riverark.ferret.navigation.Route
import io.riverark.ferret.ui.FerretEmptyState
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretLoadingState
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretTheme
import org.jetbrains.compose.resources.painterResource

data class FerretDependencies(
    val wallets: WalletRepository,
    val walletManager: WalletManager?,
    val loadBalance: (suspend (WalletProfile) -> Lovelace)? = null,
    val loadHistory: (suspend (WalletProfile) -> List<TransactionRecord>)? = null,
    val encodeQr: ((String) -> QrCode)? = null,
    val copyAddress: ((String) -> Unit)? = null,
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
    onUnlock: (() -> Unit)?,
    onSensitiveContentChanged: (Boolean) -> Unit,
) {
    val state by repository.state.collectAsState()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val walletViewModel = viewModel { WalletPickerViewModel(manager) }
    val pickerState by walletViewModel.state.collectAsState()

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
                { walletViewModel.select(it.id) },
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
                            navController.navigate(Route.Home(profile.id.value)) {
                                launchSingleTop = true
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            }
                        }
                    },
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
                val homeViewModel = viewModel { HomeViewModel(profile, loadBalance) }
                val homeState by homeViewModel.state.collectAsState()
                LaunchedEffect(homeViewModel) { homeViewModel.refresh() }
                HomeScreen(
                    homeState,
                    homeViewModel::refresh,
                    { navController.navigate(Route.TopUp(profile.id.value)) },
                    { navController.navigate(Route.History(profile.id.value)) },
                    { navController.navigate(Route.WalletPicker) },
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
        composable<Route.History> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.History>()
            val profile = (state as? AppState.Ready)?.wallets?.firstOrNull { it.id.value == route.walletId }
            if (profile != null) {
                val historyViewModel = viewModel { HistoryViewModel(profile, loadHistory) }
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
