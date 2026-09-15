package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.riverark.ferret.core.channel.ChannelCollectionV3
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
    val balance: WalletBalance,
    val channels: ChannelCollectionV3,
    val adaptorStatus: String,
    val driveAccount: String?,
    val driveGeneration: Long?,
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
    backupStale: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onConnectBackup: (() -> Unit)?,
    onVerifyBackup: (() -> Unit)?,
    onTakeoverBackup: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onReplaceMissingBackup: (() -> Unit)?,
) {
    var name by rememberSaveable(settings.profile.id.value) { mutableStateOf(settings.profile.name) }
    var confirmTakeover by rememberSaveable(settings.profile.id.value) { mutableStateOf(false) }
    var confirmReplacement by rememberSaveable(settings.profile.id.value) { mutableStateOf(false) }
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
            item { FerretListRow("Channels", settings.channels.channels.size.toString()) }
            item {
                FerretListRow(
                    "Pending channel work",
                    settings.channels.channels.values.count { it.pending != null || it.payments.pending != null }.toString(),
                )
            }
            item { FerretListRow("Tracked assets", settings.balance.assets.size.toString()) }
            if (settings.balance.unsupportedAssets.isNotEmpty()) {
                item { FerretListRow("Unsupported native assets", settings.balance.unsupportedAssets.size.toString()) }
            }
            item { FerretListRow("Adaptor", settings.adaptorStatus) }

            section("Backup")
            item { FerretListRow("Drive account", settings.driveAccount ?: "not connected") }
            backupMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item {
                FerretSecondaryButton(
                    if (settings.driveAccount == null) "Connect Google Drive" else "Change Google Drive account",
                    { onConnectBackup?.invoke() },
                    enabled = !backupBusy && onConnectBackup != null,
                )
            }
            settings.driveGeneration?.let { generation -> item { FerretListRow("Backup generation", generation.toString()) } }
            settings.driveSequence?.let { sequence -> item { FerretListRow("Last verified backup sequence", sequence.toString()) } }
            item { FerretSecondaryButton("Verify encrypted backup", { onVerifyBackup?.invoke() }, enabled = !backupBusy && onVerifyBackup != null) }
            onTakeoverBackup?.let {
                item {
                    FerretDangerButton(
                        if (backupStale) "Take over backup" else "Make this device active",
                        { confirmTakeover = true },
                        enabled = !backupBusy,
                    )
                }
            }
            onReplaceMissingBackup?.let {
                item {
                    FerretDangerButton(
                        "Create replacement backup",
                        { confirmReplacement = true },
                        enabled = !backupBusy,
                    )
                }
            }

            section("Security")
            item { FerretListRow("App lock", settings.lockStatus) }

            section("App diagnostics")
            item { FerretListRow("Version", "${settings.version} (${settings.buildCommit})") }
            settings.diagnosticCode?.let { code -> item { FerretListRow("Diagnostic code", code) } }
            onRemove?.let { item { FerretDangerButton("Remove wallet", it) } }
        }
    }
    if (confirmTakeover) {
        AlertDialog(
            onDismissRequest = { confirmTakeover = false },
            title = { Text(if (backupStale) "Take over this backup?" else "Make this device active?") },
            text = { Text("Ferret will restore the newest encrypted channel state and start a new backup generation. Other devices must verify again before writing.") },
            confirmButton = {
                TextButton({
                    confirmTakeover = false
                    onTakeoverBackup?.invoke()
                }) { Text("Take over") }
            },
            dismissButton = { TextButton({ confirmTakeover = false }) { Text("Cancel") } },
        )
    }
    if (confirmReplacement) {
        AlertDialog(
            onDismissRequest = { confirmReplacement = false },
            title = { Text("Create a replacement backup?") },
            text = { Text("No Ferret backup exists in the selected Drive account. Create one only if this is the intended account and this device holds the unchanged initial wallet state.") },
            confirmButton = {
                TextButton({
                    confirmReplacement = false
                    onReplaceMissingBackup?.invoke()
                }) { Text("Create backup") }
            },
            dismissButton = { TextButton({ confirmReplacement = false }) { Text("Cancel") } },
        )
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
