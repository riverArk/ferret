package io.riverark.ferret

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.riverark.ferret.core.model.AppState
import io.riverark.ferret.core.model.WalletRepository

data class FerretDependencies(val wallets: WalletRepository)

private val FerretYellow = Color(0xFFFFD600)

@Composable
fun FerretApp(dependencies: FerretDependencies) {
    val state by dependencies.wallets.state.collectAsState()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(FerretYellow), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Ferret", style = MaterialTheme.typography.displayMedium)
                when (val current = state) {
                    AppState.Locked -> Text("Unlock required")
                    AppState.CheckingConnectivity -> Text("Checking secure connection")
                    AppState.Offline -> Text("Offline. Wallet access is unavailable.")
                    AppState.NoWallets -> Text("No wallets on this device")
                    is AppState.Ready -> {
                        val wallet = current.wallets.first { it.id == current.activeWalletId }
                        Text(wallet.name, style = MaterialTheme.typography.headlineMedium)
                        Text(wallet.network.name)
                    }
                }
            }
        }
    }
}
