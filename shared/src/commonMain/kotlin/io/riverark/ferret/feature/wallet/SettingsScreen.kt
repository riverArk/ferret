package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.ui.FerretDangerButton
import io.riverark.ferret.ui.FerretListRow
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSecondaryButton
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretTextButton
import io.riverark.ferret.ui.FerretTopBar

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
fun SettingsScreen(
    settings: WalletSettings,
    backupBusy: Boolean,
    backupMessage: String?,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onConnectBackup: (() -> Unit)?,
    onVerifyBackup: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var name by rememberSaveable(settings.profile.id.value) { mutableStateOf(settings.profile.name) }
    FerretScreen {
        FerretTopBar("Settings", navigation = { FerretTextButton("Back", onBack) })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
            section("Profile")
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Wallet name") }, singleLine = true)
            }
            item { FerretSecondaryButton("Rename wallet", { onRename(name.trim()) }, enabled = name.isNotBlank() && name.trim() != settings.profile.name) }
            item { FerretListRow("Payment address", settings.profile.paymentAddress) }
            item { FerretListRow("Payment credential", settings.paymentCredential) }
            item { FerretListRow("Staking address", settings.stakingCredential) }

            section("Network and channel")
            item { FerretListRow("Network", settings.profile.network.name) }
            item { FerretListRow("Channel", settings.profile.channelState.toString()) }
            item { FerretListRow("Adaptor", settings.adaptorStatus) }

            section("Backup")
            item { FerretListRow("Drive account", settings.driveAccount ?: "not connected") }
            backupMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (settings.driveAccount == null) {
                item { FerretSecondaryButton("Connect Google Drive", { onConnectBackup?.invoke() }, enabled = !backupBusy && onConnectBackup != null) }
            }
            settings.driveSequence?.let { sequence -> item { FerretListRow("Last verified backup sequence", sequence.toString()) } }
            item { FerretSecondaryButton("Verify encrypted backup", { onVerifyBackup?.invoke() }, enabled = !backupBusy && onVerifyBackup != null) }

            section("Security")
            item { FerretListRow("App lock", settings.lockStatus) }

            section("App diagnostics")
            item { FerretListRow("Version", "${settings.version} (${settings.buildCommit})") }
            settings.diagnosticCode?.let { code -> item { FerretListRow("Diagnostic code", code) } }
            item { FerretDangerButton("Remove wallet", onRemove) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String) {
    item {
        Text(
            title.uppercase(),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
