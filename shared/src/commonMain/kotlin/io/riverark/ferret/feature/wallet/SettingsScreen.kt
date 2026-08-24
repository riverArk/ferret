package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.riverark.ferret.core.model.WalletProfile

data class WalletSettings(
    val profile: WalletProfile,
    val paymentCredential: String,
    val stakingCredential: String,
    val adaptorStatus: String,
    val driveAccount: String?,
    val driveSequence: Long?,
    val lockStatus: String,
    val version: String,
    val buildCommit: String,
    val diagnosticCode: String?,
)

@Composable
fun SettingsScreen(settings: WalletSettings, onVerifyBackup: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(settings.profile.name)
        Text("Network: ${settings.profile.network}")
        Text("Address: ${settings.profile.paymentAddress}")
        Text("Payment credential: ${settings.paymentCredential}")
        Text("Staking credential: ${settings.stakingCredential}")
        Text("Channel: ${settings.profile.channelState}")
        Text("Adaptor: ${settings.adaptorStatus}")
        Text("Drive: ${settings.driveAccount ?: "not connected"}")
        settings.driveSequence?.let { Text("Last verified backup sequence: $it") }
        Text("App lock: ${settings.lockStatus}")
        Text("Version ${settings.version} (${settings.buildCommit})")
        settings.diagnosticCode?.let { Text("Diagnostic code: $it") }
        Button(onClick = onVerifyBackup) { Text("Verify encrypted backup") }
        Button(onClick = onRemove) { Text("Remove wallet") }
    }
}
