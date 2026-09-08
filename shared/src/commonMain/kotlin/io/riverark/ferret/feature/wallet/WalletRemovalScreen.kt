package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.riverark.ferret.core.cardano.SweepPreview
import io.riverark.ferret.core.model.RemovalReadiness
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRemovalManager
import io.riverark.ferret.core.model.blockers
import io.riverark.ferret.ui.FerretCard
import io.riverark.ferret.ui.FerretDangerButton
import io.riverark.ferret.ui.FerretDataBlock
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretTextButton
import io.riverark.ferret.ui.FerretTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

 data class WalletRemovalUiState(
    val readiness: RemovalReadiness? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val sweepPreview: SweepPreview? = null,
)

class WalletRemovalViewModel(
    private val walletId: WalletId,
    private val manager: WalletRemovalManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WalletRemovalUiState())
    val state = mutableState.asStateFlow()

    fun load() {
        launchReadiness { manager.readiness(walletId) }
    }

    fun sweep(destination: String) {
        launchState { current ->
            current.copy(sweepPreview = manager.previewSweep(walletId, destination))
        }
    }

    fun confirmSweep() {
        val preview = mutableState.value.sweepPreview ?: return
        launchReadiness {
            manager.submitSweep(walletId, preview)
            manager.readiness(walletId)
        }
    }

    fun remove(onRemoved: () -> Unit) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                manager.remove(walletId)
                onRemoved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = "Wallet removal could not be completed safely.")
            }
        }
    }

    private fun launchReadiness(action: suspend () -> RemovalReadiness) {
        launchState { current -> current.copy(readiness = action(), sweepPreview = null) }
    }

    private fun launchState(action: suspend (WalletRemovalUiState) -> WalletRemovalUiState) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                mutableState.value = action(mutableState.value).copy(busy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Removal readiness is unavailable.", busy = false)
            }
        }
    }
}

@Composable
fun WalletRemovalScreen(
    state: WalletRemovalUiState,
    onBack: () -> Unit,
    onSweep: (String) -> Unit,
    onConfirmSweep: () -> Unit,
    onRemove: () -> Unit,
) {
    val readiness = state.readiness
    var destination by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    FerretScreen {
        FerretTopBar("Remove wallet", navigation = { FerretTextButton("Back", onBack) })
        Text("Removal cannot erase copies of the recovery phrase or records retained by external providers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        readiness?.let { current ->
            FerretCard(Modifier.fillMaxWidth()) {
                FerretDataBlock("Wallet", current.profile.name)
                FerretDataBlock("Remaining L1 balance", formatAda(current.spendable))
                current.blockers().forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (current.spendable.value > 0) {
                state.sweepPreview?.let { preview ->
                    FerretCard(Modifier.fillMaxWidth()) {
                        FerretDataBlock("Sweep amount", formatAda(preview.amount))
                        FerretDataBlock("Network fee", formatAda(preview.fee))
                    }
                    FerretDangerButton("Confirm sweep", onConfirmSweep, enabled = !state.busy)
                } ?: run {
                    OutlinedTextField(
                        destination,
                        { destination = it.trim() },
                        Modifier.fillMaxWidth(),
                        label = { Text("Same-network sweep address") },
                        singleLine = true,
                    )
                    FerretPrimaryButton("Preview sweep", { onSweep(destination) }, enabled = destination.isNotBlank() && !state.busy)
                }
            } else if (current.blockers().isEmpty()) {
                OutlinedTextField(
                    confirmation,
                    { confirmation = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Type ${current.profile.name} to confirm") },
                    singleLine = true,
                )
                FerretDangerButton("Delete wallet and backup", onRemove, enabled = confirmation == current.profile.name && !state.busy)
            }
        }
        state.error?.let { FerretErrorState(it) }
        if (readiness == null && state.error == null) Box(Modifier.weight(1f)) { Text("Checking removal safety") }
    }
}
