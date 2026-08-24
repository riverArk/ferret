package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ferret.shared.generated.resources.Res
import ferret.shared.generated.resources.empty_activity_ferret
import ferret.shared.generated.resources.ferret_unpack
import ferret.shared.generated.resources.splash_ferret
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.ui.FerretCard
import io.riverark.ferret.ui.FerretDataBlock
import io.riverark.ferret.ui.FerretEmptyState
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSecondaryButton
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretStatusChip
import io.riverark.ferret.ui.FerretTopBar
import org.jetbrains.compose.resources.painterResource
import kotlin.random.Random

@Composable
fun WalletPickerScreen(
    state: WalletPickerUiState,
    onSelect: (WalletProfile) -> Unit,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    FerretScreen {
        if (state.wallets.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(painterResource(Res.drawable.splash_ferret), null, Modifier.size(180.dp))
                Text("Ferret", style = MaterialTheme.typography.displaySmall)
                Text("Your recovery phrase is the only way back into your wallet.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FerretTopBar("Wallets")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
                CardanoNetwork.entries.forEach { network ->
                    val wallets = state.wallets.filter { it.network == network }
                    if (wallets.isNotEmpty()) {
                        item { Text(network.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium) }
                        items(wallets, key = { it.id.value }) { wallet ->
                            FerretCard(Modifier.fillMaxWidth(), onClick = { onSelect(wallet) }) {
                                Text(wallet.name, style = MaterialTheme.typography.titleLarge)
                                Text(wallet.paymentAddress, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { FerretErrorState(it) }
        FerretPrimaryButton("Create wallet", onCreate, enabled = !state.busy)
        FerretSecondaryButton("Restore wallet", onRestore, enabled = !state.busy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWalletScreen(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onCreate: (String, CardanoNetwork) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf(CardanoNetwork.PREPROD) }
    WalletForm("Create wallet", name, { name = it }, network, { network = it }, onBack) {
        if (network == CardanoNetwork.MAINNET) Text("Mainnet uses real ADA.", color = MaterialTheme.colorScheme.error)
        error?.let { FerretErrorState(it) }
        FerretPrimaryButton("Continue", { onCreate(name.trim(), network) }, enabled = name.isNotBlank() && !busy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreWalletScreen(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRestore: (String, CardanoNetwork, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf(CardanoNetwork.PREPROD) }
    var phrase by rememberSaveable { mutableStateOf("") }
    val count = phrase.trim().split(Regex("\\s+")).filter(String::isNotBlank).size
    WalletForm("Restore wallet", name, { name = it }, network, { network = it }, onBack, Modifier.imePadding()) {
        if (network == CardanoNetwork.MAINNET) Text("Mainnet uses real ADA.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            phrase,
            { phrase = it },
            Modifier.fillMaxWidth().heightIn(min = 180.dp),
            label = { Text("24-word recovery phrase") },
            supportingText = { Text("$count of 24 words") },
            minLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        error?.let { FerretErrorState(it) }
        FerretPrimaryButton("Restore wallet", { onRestore(name.trim(), network, phrase) }, enabled = name.isNotBlank() && count == 24 && !busy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletForm(
    title: String,
    name: String,
    onName: (String) -> Unit,
    network: CardanoNetwork,
    onNetwork: (CardanoNetwork) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FerretScreen {
        FerretTopBar(title, navigation = { io.riverark.ferret.ui.FerretTextButton("Back", onBack) })
        LazyColumn(modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FerretSpacing.md)) {
            item { OutlinedTextField(name, onName, Modifier.fillMaxWidth(), label = { Text("Wallet name") }, singleLine = true) }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CardanoNetwork.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = item == network,
                            onClick = { onNetwork(item) },
                            shape = SegmentedButtonDefaults.itemShape(index, CardanoNetwork.entries.size),
                            label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
            item { content() }
        }
    }
}

@Composable
fun RecoveryPhraseScreen(words: List<String>, busy: Boolean, error: String?, onContinue: () -> Unit) {
    FerretScreen {
        FerretTopBar("Back up wallet")
        Image(painterResource(Res.drawable.ferret_unpack), null, Modifier.fillMaxWidth().heightIn(max = 140.dp))
        Text("Record these 24 words", style = MaterialTheme.typography.headlineSmall)
        Text("Write them down in order. Ferret cannot recover them for you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (words.size == 24) {
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FerretSpacing.xs), horizontalArrangement = Arrangement.spacedBy(FerretSpacing.xs)) {
                itemsIndexed(words) { index, word ->
                    FerretCard(Modifier.fillMaxWidth()) { Text("${index + 1}. $word", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        error?.let { FerretErrorState(it) }
        FerretPrimaryButton("I wrote them down", onContinue, enabled = words.size == 24 && !busy)
    }
}

@Composable
fun VerifyRecoveryScreen(words: List<String>, busy: Boolean, error: String?, onConfirm: () -> Unit) {
    val indexes = remember(words) {
        if (words.size == 24) recoveryVerificationIndexes(words.size) else emptyList()
    }
    var answers by remember(indexes) { mutableStateOf(indexes.associateWith { "" }) }
    val matches = words.size == 24 && indexes.all { answers[it].orEmpty().trim().lowercase() == words[it].trim().lowercase() }
    FerretScreen {
        FerretTopBar("Verify recovery phrase")
        Text("Enter three words from your recovery phrase.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        indexes.forEach { index ->
            OutlinedTextField(
                answers[index].orEmpty(),
                { answer -> answers = answers + (index to answer) },
                Modifier.fillMaxWidth(),
                label = { Text("Word ${index + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )
        }
        Box(Modifier.weight(1f))
        error?.let { FerretErrorState(it) }
        FerretPrimaryButton("Confirm recovery phrase", onConfirm, enabled = matches && !busy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onTopUp: () -> Unit,
    onHistory: () -> Unit,
    onWallets: () -> Unit,
) {
    FerretScreen {
        FerretTopBar("Ferret")
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(FerretSpacing.md),
            ) {
                item { Text(state.profile.name, style = MaterialTheme.typography.headlineLarge) }
                item { FerretStatusChip(state.profile.network.name) }
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        when {
                            state.balance != null -> FerretDataBlock("Available balance", formatAda(state.balance))
                            state.error != null -> FerretErrorState(state.error, "Retry", onRefresh)
                            else -> Text("Loading balance")
                        }
                    }
                }
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        SelectionContainer { FerretDataBlock("Payment address", state.profile.paymentAddress) }
                    }
                }
                if (state.balance != null && state.error != null) {
                    item { FerretErrorState(state.error, "Retry", onRefresh) }
                }
            }
        }
        when {
            state.balance?.value == 0L -> FerretPrimaryButton("Add ADA", onTopUp)
            state.balance != null -> {
                FerretPrimaryButton("Open channel", {}, enabled = false)
                FerretSecondaryButton("Add ADA", onTopUp)
            }
        }
        FerretSecondaryButton("History", onHistory)
        FerretSecondaryButton("Wallets", onWallets)
    }
}

internal fun formatAda(lovelace: Lovelace): String {
    val whole = lovelace.value / 1_000_000
    val fraction = (lovelace.value % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return "₳ $whole" + if (fraction.isEmpty()) "" else ".$fraction"
}

data class QrCode(val size: Int, val modules: BooleanArray) {
    init {
        require(size > 0 && modules.size == size * size)
    }
}

@Composable
fun TopUpScreen(profile: WalletProfile, qrCode: QrCode, onBack: () -> Unit, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    FerretScreen {
        FerretTopBar("Add ADA", navigation = { io.riverark.ferret.ui.FerretTextButton("Back", onBack) })
        FerretStatusChip(profile.network.name)
        Canvas(
            Modifier.size(256.dp).align(Alignment.CenterHorizontally)
                .semantics { contentDescription = "QR code for ${profile.paymentAddress}" },
        ) {
            drawRect(Color.White)
            val moduleSize = size.minDimension / qrCode.size
            qrCode.modules.forEachIndexed { index, dark ->
                if (dark) {
                    drawRect(
                        Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            (index % qrCode.size) * moduleSize,
                            (index / qrCode.size) * moduleSize,
                        ),
                        size = androidx.compose.ui.geometry.Size(moduleSize, moduleSize),
                    )
                }
            }
        }
        FerretCard(Modifier.fillMaxWidth()) { FerretDataBlock("Payment address", profile.paymentAddress) }
        if (copied) Text("Address copied", color = MaterialTheme.colorScheme.primary)
        Box(Modifier.weight(1f))
        FerretPrimaryButton("Copy address", { onCopy(); copied = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    FerretScreen {
        FerretTopBar("History", navigation = { io.riverark.ferret.ui.FerretTextButton("Back", onBack) })
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                state.records.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm),
                ) {
                    items(state.records, key = TransactionRecord::id) { record ->
                        FerretCard(Modifier.fillMaxWidth()) {
                            Text("${record.realm}: ${formatAda(record.amount)}", style = MaterialTheme.typography.titleMedium)
                            Text("${record.state} · fee ${formatAda(record.fee)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(record.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FerretErrorState(state.error, "Retry", onRefresh)
                }
                !state.loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(painterResource(Res.drawable.empty_activity_ferret), null, Modifier.size(180.dp))
                    FerretEmptyState("No activity yet", "Wallet activity will appear here.")
                }
            }
        }
        if (state.records.isNotEmpty() && state.error != null) {
            FerretErrorState(state.error, "Retry", onRefresh)
        }
    }
}

internal fun recoveryVerificationIndexes(wordCount: Int, random: Random = Random.Default): List<Int> {
    require(wordCount >= 3)
    return (0 until wordCount).shuffled(random).take(3).sorted()
}
