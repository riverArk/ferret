package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ferret.shared.generated.resources.Res
import ferret.shared.generated.resources.account_balance_wallet
import ferret.shared.generated.resources.asset_1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e34_5553444378
import ferret.shared.generated.resources.asset_c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad_0014df105553444d
import ferret.shared.generated.resources.asset_fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae456_55534441
import ferret.shared.generated.resources.add_circle
import ferret.shared.generated.resources.bolt
import ferret.shared.generated.resources.history
import ferret.shared.generated.resources.qr_code_scanner
import ferret.shared.generated.resources.settings
import ferret.shared.generated.resources.empty_activity_ferret
import ferret.shared.generated.resources.ferret_unpack
import ferret.shared.generated.resources.splash_ferret
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.isEligibleForPayment
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.parseAmount
import io.riverark.ferret.ui.FerretCard
import io.riverark.ferret.ui.FerretDataBlock
import io.riverark.ferret.ui.FerretEmptyState
import io.riverark.ferret.ui.FerretErrorState
import io.riverark.ferret.ui.FerretPrimaryButton
import io.riverark.ferret.ui.FerretLoadingState
import io.riverark.ferret.ui.FerretListRow
import io.riverark.ferret.ui.FerretScreen
import io.riverark.ferret.ui.FerretSecondaryButton
import io.riverark.ferret.ui.FerretSpacing
import io.riverark.ferret.ui.FerretStatusChip
import io.riverark.ferret.ui.FerretTopBar
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.random.Random

internal fun channelReadyToReturn(channel: ChannelSnapshot): Boolean =
    (channel.state == ChannelState.Closed || channel.state == ChannelState.Responded) &&
        channel.pending == null && channel.payments.pending == null &&
        channel.chainObservation?.let {
            it.canReturn && it.confirmationDepth >= 5 &&
                ((channel.state == ChannelState.Closed && it.datum.stage is io.riverark.ferret.core.cardano.ChannelDatumStage.Closed) ||
                    (channel.state == ChannelState.Responded && it.datum.stage is io.riverark.ferret.core.cardano.ChannelDatumStage.Responded))
        } == true

internal fun channelProgressLabel(channel: ChannelSnapshot): String =
    if (channelReadyToReturn(channel)) "Ready to return funds" else channelStateLabel(channel.state)

internal fun settlementWait(afterEpochMillis: Long, nowEpochMillis: Long): String {
    if (afterEpochMillis <= nowEpochMillis) return "Waiting for a refreshed status"
    val millis = afterEpochMillis - nowEpochMillis.coerceAtLeast(0)
    val minutes = millis / 60_000 + if (millis % 60_000 != 0L) 1 else 0
    return when {
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"}"
        minutes < 1_440 -> {
            val hours = minutes / 60 + if (minutes % 60 != 0L) 1 else 0
            "$hours hour${if (hours == 1L) "" else "s"}"
        }
        else -> {
            val days = minutes / 1_440 + if (minutes % 1_440 != 0L) 1 else 0
            "$days day${if (days == 1L) "" else "s"}"
        }
    }
}

@Composable
private fun ChannelHeldFunds(channel: ChannelSnapshot, catalog: AssetCatalog) {
    when (channel.state) {
        is ChannelState.FundsReturned -> Unit
        is ChannelState.Open -> FerretDataBlock("Spendable capacity", formatAsset(channel.spendableBalance, catalog))
        ChannelState.Absent, is ChannelState.Opening -> FerretDataBlock("Tracked amount", formatAsset(channel.spendableBalance, catalog))
        else -> {
            val observation = channel.chainObservation
            if (observation != null) {
                val units = if (channel.asset == catalog.ada) observation.output.lovelace.value
                    else observation.output.assets[channel.asset.connectorUnit] ?: 0
                FerretDataBlock("Funds still in channel", formatAsset(AssetAmount(channel.asset, units), catalog))
                if (channel.asset != catalog.ada) {
                    FerretDataBlock("Channel ADA", formatAsset(AssetAmount(catalog.ada, observation.output.lovelace.value), catalog))
                }
                Text("Includes unsettled payments", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FerretDataBlock("Last known available funds", formatAsset(channel.spendableBalance, catalog))
                Text("Status unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CloseTransactionDetails(identity: String?, destination: String? = null) {
    var expanded by rememberSaveable(identity, destination) { mutableStateOf(false) }
    FerretSecondaryButton(
        if (expanded) "Hide transaction details" else "Show transaction details",
        { expanded = !expanded },
        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    )
    if (expanded) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
                identity?.let { FerretDataBlock("Transaction ID", it) }
                destination?.let { FerretDataBlock("Destination · this wallet", it) }
            }
        }
    }
}

@Composable
fun ChannelCloseScreen(
    profile: WalletProfile,
    catalog: AssetCatalog,
    state: ChannelCloseUiState,
    onPreviewClose: () -> Unit,
    onPreviewReturnFunds: () -> Unit,
    onSubmit: () -> Unit,
    onCancelPreview: () -> Unit,
    onRefresh: () -> Unit,
    onTopUp: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val channel = state.channel
    val preview = state.preview
    val payload = preview?.operation?.payload as? io.riverark.ferret.core.channel.ChannelPayload.Transaction
    val intent = payload?.intent as? io.riverark.ferret.core.cardano.CardanoIntent.CloseChannel
    val closingPreview = intent?.step == io.riverark.ferret.core.cardano.CloseChannelStep.CLOSE
    val proof = channel?.returnProof
    val returned = channel?.state is ChannelState.FundsReturned && proof != null && proof.confirmationDepth >= 5
    val uncertain = state.operationId != null && (channel?.pending != null || state.error != null || preview != null)
    val waiting = channel != null && !returned && (channel.state is ChannelState.Closing ||
        channel.state == ChannelState.Closed || channel.state == ChannelState.Responded ||
        channel.state == ChannelState.Ending || channel.pending != null || uncertain)
    val unavailable = channel == null || state.error != null ||
        (channel.state !is ChannelState.Open && !returned && channel.chainObservation == null)
    val title = when {
        returned -> "Funds returned"
        uncertain -> "Checking transaction status"
        preview != null -> if (closingPreview) "Review close" else "Review return funds"
        channel != null -> if (channel.state is ChannelState.Open) "Close channel" else channelProgressLabel(channel)
        else -> "Close channel"
    }
    FerretScreen {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
            item {
                FerretTopBar(title, navigation = { io.riverark.ferret.ui.FerretTextButton("Back", onBack) })
                FerretStatusChip("${profile.network.name} · ${profile.name}")
            }
            channel?.let {
                item {
                    FerretCard(Modifier.fillMaxWidth().semantics { stateDescription = title }) {
                        FerretDataBlock("Asset", assetName(it.asset, catalog))
                        FerretDataBlock("Channel", state.collection?.channels?.keys?.toList()
                            ?.let(::distinctKeytagSuffixes)?.get(it.keytag.value) ?: it.keytag.value)
                        if (!returned) ChannelHeldFunds(it, catalog)
                    }
                }
            }
            if (preview != null && intent != null && !uncertain) {
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        if (closingPreview) {
                            FerretDataBlock("Current capacity", formatAsset(preview.amount, catalog))
                            val extraAda = preview.outputAda.baseUnits - intent.channelInput.lovelace.value
                            FerretDataBlock("Required extra ADA", formatAsset(AssetAmount(catalog.ada, extraAda.coerceAtLeast(0)), catalog))
                            val deadline = (intent.resultingDatum?.stage as? io.riverark.ferret.core.cardano.ChannelDatumStage.Closed)?.elapseAtEpochMillis
                            deadline?.let {
                                FerretDataBlock("Response wait", settlementWait(it, preview.operation.preparedAtEpochMillis))
                            }
                            Text("No funds return in this transaction. A later return transaction has a separate fee.")
                        } else {
                            FerretDataBlock("Gross asset returned", formatAsset(preview.amount, catalog))
                            if (preview.amount.asset != catalog.ada) {
                                FerretDataBlock("Gross ADA released · reserve included", formatAsset(preview.outputAda, catalog))
                            } else Text("The gross returned ADA already includes the channel reserve.")
                            TransactionChange("Wallet change", preview.sourceChange, catalog)
                        }
                        FerretDataBlock("Network fee · separate", formatAsset(preview.actualFee, catalog))
                        if (preview.feeBound != preview.actualFee) FerretDataBlock("Fee bound", formatAsset(preview.feeBound, catalog))
                        preview.collateral?.let {
                            FerretDataBlock("Collateral at risk", formatAsset(it, catalog))
                            Text("Collateral is not charged when the transaction succeeds.")
                        }
                        CloseTransactionDetails(payload?.expectedTransactionId, if (closingPreview) null else intent.sourceAddress)
                    }
                }
                item {
                    FerretPrimaryButton(if (closingPreview) "Confirm close channel" else "Confirm return funds",
                        onSubmit, enabled = !state.busy && !unavailable)
                    FerretSecondaryButton("Cancel", onCancelPreview, enabled = !state.busy)
                }
            } else if (returned) {
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        FerretDataBlock("Gross asset returned", formatAsset(proof!!.returnedAmount, catalog))
                        if (proof.returnedAmount.asset != catalog.ada) {
                            FerretDataBlock("Gross ADA released · reserve included", formatAsset(AssetAmount(catalog.ada, proof.releasedAda.value), catalog))
                        }
                        FerretDataBlock("Network fee · separate", formatAsset(AssetAmount(catalog.ada, proof.fee.value), catalog))
                        Text("Your remaining channel funds are back in this wallet.")
                        CloseTransactionDetails(proof.transactionId, proof.sourceAddress)
                    }
                }
            } else if (uncertain) {
                item { Text("Checking transaction status. Refresh status before continuing.") }
            } else if (channel != null) when (channel.state) {
                is ChannelState.Open -> item {
                    Text("Closing stops payments from this channel. Your remaining funds and ADA reserve return to this wallet after settlement. You will confirm a second transaction when they are ready.")
                    FerretPrimaryButton("Review close", onPreviewClose, enabled = !state.busy && !unavailable &&
                        channel.pending == null && channel.payments.pending == null &&
                        state.collection?.unresolvedLegacy?.isEmpty() == true &&
                        state.collection?.channels?.values?.none { it.pending?.payload is io.riverark.ferret.core.channel.ChannelPayload.Transaction } == true)
                }
                is ChannelState.Closing -> item { Text("Your close request is being confirmed. Funds are still in the channel.") }
                ChannelState.Ending -> item { Text("Your return transaction is being confirmed.") }
                ChannelState.Closed, ChannelState.Responded -> item {
                    if (channelReadyToReturn(channel)) {
                        Text("Review the current remaining funds before confirming their return to this wallet.")
                        FerretPrimaryButton("Return funds", onPreviewReturnFunds, enabled = !state.busy && !unavailable)
                    } else {
                        if (channel.state == ChannelState.Closed) {
                            Text("Payments from this channel have stopped. The provider can settle completed payments during this period.")
                            Text("If it does not respond, your remaining funds can be returned after this waiting period.")
                        } else {
                            FerretDataBlock("Remaining pending payments", channel.chainObservation?.datum?.stage?.evidenceCborHex?.size?.toString() ?: "Unavailable")
                            Text("The amount available to return can change as pending payments finish.")
                        }
                        channel.chainObservation?.returnAfterEpochMillis?.let {
                            FerretDataBlock("Remaining wait", settlementWait(it, state.lastRefreshEpochMillis ?: 0))
                        }
                    }
                }
                else -> Unit
            }
            if (waiting) item {
                val transactionId = (channel?.pending?.payload as? io.riverark.ferret.core.channel.ChannelPayload.Transaction)
                    ?.expectedTransactionId ?: channel?.history?.lastOrNull { it.transactionId != null }?.transactionId
                CloseTransactionDetails(transactionId)
            }
            if (waiting) item { Text("You can leave this screen. Reopen Ferret to check progress and confirm the return.") }
            if (unavailable) item {
                FerretErrorState("Unable to check channel status. Refresh before continuing.")
            }
            state.error?.let { error ->
                item {
                    FerretErrorState(error)
                    if (error.contains("collateral", ignoreCase = true) || error.contains("Insufficient", ignoreCase = true)) {
                        Text("ADA in your L1 wallet is needed for fees and collateral. Collateral funding requires a separate ADA-only output of at least 5 ADA; this is not the amount at risk.")
                        FerretSecondaryButton("Add ADA for fees and collateral", onTopUp)
                    }
                    if (error.contains("backup", true) || error.contains("Drive", true) || error.contains("writer", true) || error.contains("lease", true)) {
                        FerretSecondaryButton("Resolve backup", onSettings)
                    }
                }
            }
            channel?.history?.lastOrNull()?.takeIf { it.status == OperationState.FAILED }?.failureMessage?.let { failure ->
                item {
                    FerretErrorState(failure)
                    Regex("Collateral charged: ([0-9]+) lovelace").find(failure)?.groupValues?.get(1)?.toLongOrNull()?.let {
                        FerretDataBlock("Collateral charged", formatAsset(AssetAmount(catalog.ada, it), catalog))
                    }
                }
            }
            if (state.busy) item { FerretLoadingState(if (state.operationId != null) "Checking transaction status" else "Preparing transaction") }
            item {
                FerretSecondaryButton(if (state.error != null || uncertain) "Retry status" else "Refresh status", onRefresh, enabled = !state.busy)
                FerretPrimaryButton("Back to wallet", onBack)
            }
        }
    }
}
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

@Composable
fun RestoreBackupScreen(
    profile: WalletProfile,
    accountConnected: Boolean,
    busy: Boolean,
    message: String?,
    onConnect: () -> Unit,
    onRestore: () -> Unit,
    onSkip: () -> Unit,
) {
    FerretScreen {
        FerretTopBar("Recover channel")
        FerretStatusChip(profile.network.name)
        Text(
            "Connect the Google Drive account used by this wallet to recover its encrypted channel backup.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Only encrypted app data is read. Your recovery phrase never leaves this device. You can retry later from Settings.")
        message?.let { FerretErrorState(it) }
        Box(Modifier.weight(1f))
        if (!accountConnected) {
            FerretPrimaryButton("Connect Google Drive", onConnect, enabled = !busy)
        } else {
            FerretPrimaryButton("Restore encrypted backup", onRestore, enabled = !busy)
        }
        FerretSecondaryButton("Continue without channel recovery", onSkip, enabled = !busy)
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
    catalog: AssetCatalog,
    onRefresh: () -> Unit,
    onTopUp: () -> Unit,
    onOpenChannel: (() -> Unit)?,
    onPay: (() -> Unit)?,
    onTransfer: (() -> Unit)?,
    onChannel: (() -> Unit)?,
    onHistory: () -> Unit,
    onWallets: () -> Unit,
    onSettings: () -> Unit,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    val menuState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = channelDisplayOrder(state.channels?.channels?.values.orEmpty())
    val adaBalance = state.balance?.assets?.singleOrNull { it.total.asset == catalog.ada }
    val canPay = state.channels?.unresolvedLegacy?.isEmpty() == true &&
        entries.any { it.isEligibleForPayment(catalog) }
    fun select(action: () -> Unit) {
        showMenu = false
        action()
    }

    Box(Modifier.fillMaxSize()) {
        FerretScreen {
            FerretTopBar("Ferret")
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(FerretSpacing.md),
                ) {
                    Text(state.profile.name, style = MaterialTheme.typography.headlineLarge)
                    FerretStatusChip(state.profile.network.name)
                    when {
                        state.balance != null -> {
                            state.balance.assets.forEach { balance ->
                                AssetHoldingRow(balance, channelBalance(balance.total.asset, entries), catalog)
                            }
                            if (state.balance.unsupportedAssets.isNotEmpty()) {
                                FerretErrorState("Unsupported native assets present.")
                            }
                        }
                        state.error != null -> FerretErrorState(state.error)
                        else -> FerretCard(Modifier.fillMaxWidth()) { Text("Loading balances") }
                    }
                    if (entries.isNotEmpty() || state.channels?.unresolvedLegacy?.isNotEmpty() == true) {
                        val labels = distinctKeytagSuffixes(entries.map { it.keytag.value })
                        entries.filter { it.state != ChannelState.Absent && it.state !is ChannelState.FundsReturned }.takeIf { it.isNotEmpty() }?.let { active ->
                            Text("Channels", style = MaterialTheme.typography.titleMedium)
                            if (onChannel != null && active.any {
                                it.state is ChannelState.Closing || it.state == ChannelState.Closed ||
                                    it.state == ChannelState.Responded || it.state == ChannelState.Ending
                            }) {
                                FerretListRow(
                                    if (active.any(::channelReadyToReturn)) "Ready to return funds" else "View channel progress",
                                    "Check settlement and confirm the return when ready",
                                    onClick = onChannel,
                                )
                            }
                            active.forEach { channel ->
                                ChannelSummary(channel, labels.getValue(channel.keytag.value), catalog)
                            }
                        }
                        entries.filter { it.state is ChannelState.FundsReturned }.takeIf { it.isNotEmpty() }?.let { returned ->
                            Text("Closed channels", style = MaterialTheme.typography.titleMedium)
                            returned.forEach { ChannelSummary(it, labels.getValue(it.keytag.value), catalog) }
                        }
                        entries.filter { it.state == ChannelState.Absent }.let { notOpened ->
                            if (notOpened.isNotEmpty() || state.channels?.unresolvedLegacy?.isNotEmpty() == true) {
                                Text("Not opened channels", style = MaterialTheme.typography.titleMedium)
                                if (state.channels?.unresolvedLegacy?.isNotEmpty() == true) {
                                    FerretErrorState(LEGACY_CHANNEL_MESSAGE)
                                }
                                notOpened.forEach { channel ->
                                    ChannelSummary(channel, labels.getValue(channel.keytag.value), catalog)
                                }
                            }
                        }
                    }
                    FerretCard(Modifier.fillMaxWidth()) {
                        Text("Latest activity", style = MaterialTheme.typography.titleMedium)
                        state.latestActivity?.let { activity ->
                            activity.amounts.forEach { Text("${activity.realm}: ${formatAsset(it, catalog)}") }
                            Text(
                                "${activity.state} · fee ${formatAsset(activity.fee, catalog)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } ?: Text("No activity yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FerretCard(Modifier.fillMaxWidth()) {
                        SelectionContainer { FerretDataBlock("Payment address", state.profile.paymentAddress) }
                    }
                    if (state.balance != null && state.error != null) FerretErrorState(state.error)
                }
            }
            FerretSecondaryButton(
                "Menu",
                { showMenu = true },
                Modifier.semantics { stateDescription = if (showMenu) "Expanded" else "Collapsed" },
            )
        }
        if (canPay && onPay != null) {
            FloatingActionButton(
                onClick = onPay,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(end = FerretSpacing.md, bottom = 72.dp),
            ) {
                Icon(painterResource(Res.drawable.qr_code_scanner), contentDescription = "Pay invoice")
            }
        }
    }

    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }, sheetState = menuState) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = FerretSpacing.md, vertical = FerretSpacing.sm),
            ) {
                HomeMenuSection("Wallet actions")
                if (state.channels?.let(::channelRouteAvailable) == true && onChannel != null) {
                    HomeMenuItem("View channels", Res.drawable.bolt) { select(onChannel) }
                }
                if (
                    state.channels?.unresolvedLegacy?.isEmpty() == true &&
                    adaBalance?.spendable?.baseUnits?.let { it > 0 } == true &&
                    onOpenChannel != null
                ) {
                    HomeMenuItem("Open channel", Res.drawable.bolt) { select(onOpenChannel) }
                }
                if (canPay && onPay != null) HomeMenuItem("Pay invoice", Res.drawable.qr_code_scanner) { select(onPay) }
                HomeMenuItem("Add ADA", Res.drawable.add_circle) { select(onTopUp) }
                if (adaBalance?.spendable?.baseUnits?.let { it > 0 } == true && onTransfer != null) {
                    HomeMenuItem("Transfer asset", Res.drawable.account_balance_wallet) { select(onTransfer) }
                }
                HorizontalDivider(Modifier.padding(vertical = FerretSpacing.sm))
                HomeMenuSection("Navigation")
                HomeMenuItem("History", Res.drawable.history) { select(onHistory) }
                HomeMenuItem("Wallets", Res.drawable.account_balance_wallet) { select(onWallets) }
                HomeMenuItem("Settings", Res.drawable.settings) { select(onSettings) }
            }
        }
    }
}

@Composable
private fun HomeMenuSection(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(horizontal = FerretSpacing.md, vertical = FerretSpacing.xs),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HomeMenuItem(title: String, icon: DrawableResource, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        leadingContent = {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        },
    )
}

internal fun channelRouteAvailable(collection: ChannelCollectionV4) =
    collection.channels.isNotEmpty() || collection.unresolvedLegacy.isNotEmpty()

internal fun channelStateLabel(state: ChannelState) = when (state) {
    ChannelState.Absent -> "Not opened"
    is ChannelState.Opening -> "Opening"
    is ChannelState.Open -> "Open"
    is ChannelState.Closing -> "Confirming close"
    ChannelState.Closed -> "Waiting for settlement"
    ChannelState.Responded -> "Finishing pending payments"
    ChannelState.Ending -> "Returning funds"
    is ChannelState.FundsReturned -> "Funds returned"
}

@Composable
fun ChannelScreen(
    profile: WalletProfile,
    collection: ChannelCollectionV4?,
    catalog: AssetCatalog,
    error: String?,
    cleaning: Boolean,
    onCleanupInactive: (() -> Unit)?,
    onAddFunds: ((ProtocolKeytag) -> Unit)?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onCloseChannel: ((ProtocolKeytag) -> Unit)? = null,
) {
    var confirmingCleanup by rememberSaveable(collection?.unresolvedLegacy?.contentHashCode()) {
        mutableStateOf(false)
    }
    FerretScreen {
        FerretTopBar("Channels", navigation = { io.riverark.ferret.ui.FerretTextButton("Back", onBack) })
        FerretStatusChip(profile.network.name)
        when {
            error != null -> FerretErrorState(error, "Retry", onRetry)
            collection == null -> FerretLoadingState("Loading channels")
            else -> {
                if (collection.channels.isEmpty() && collection.unresolvedLegacy.isEmpty()) {
                    FerretEmptyState("No channels", "Open an ADA channel from the wallet menu.")
                } else {
                    val entries = channelDisplayOrder(collection.channels.values)
                    val active = entries.filter { it.state != ChannelState.Absent && it.state !is ChannelState.FundsReturned }
                    val returned = entries.filter { it.state is ChannelState.FundsReturned }
                    val notOpened = entries.filter { it.state == ChannelState.Absent }
                    val labels = distinctKeytagSuffixes(entries.map { it.keytag.value })
                    val fundingAvailable = collection.unresolvedLegacy.isEmpty() &&
                        collection.channels.values.none { it.pending?.payload is io.riverark.ferret.core.channel.ChannelPayload.Transaction }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm),
                    ) {
                        if (active.isNotEmpty()) {
                            item { Text("Channels", style = MaterialTheme.typography.titleMedium) }
                            items(active, key = { it.keytag.value }) { channel ->
                                ChannelSummary(
                                    channel,
                                    labels.getValue(channel.keytag.value),
                                    catalog,
                                    detailed = true,
                                    onAddFunds = onAddFunds?.takeIf {
                                        fundingAvailable && channel.state is ChannelState.Open &&
                                            channel.pending == null && channel.payments.pending == null
                                    }?.let { add -> { add(channel.keytag) } },
                                    onCloseChannel = onCloseChannel?.let { close -> { close(channel.keytag) } },
                                    closeDisabledReason = if (channel.state is ChannelState.Open &&
                                        (!fundingAvailable || channel.pending != null || channel.payments.pending != null)
                                    ) "Finish pending work and resolve the backup before closing this channel." else null,
                                )
                            }
                        }
                        if (returned.isNotEmpty()) {
                            item { Text("Closed channels", style = MaterialTheme.typography.titleMedium) }
                            items(returned, key = { it.keytag.value }) { channel ->
                                ChannelSummary(channel, labels.getValue(channel.keytag.value), catalog, detailed = true,
                                    onCloseChannel = onCloseChannel?.let { close -> { close(channel.keytag) } })
                            }
                        }
                        if (notOpened.isNotEmpty() || collection.unresolvedLegacy.isNotEmpty()) {
                            item { Text("Not opened channels", style = MaterialTheme.typography.titleMedium) }
                            if (collection.unresolvedLegacy.isNotEmpty()) {
                                item { FerretErrorState(LEGACY_CHANNEL_MESSAGE) }
                                if (notOpened.isNotEmpty() && onCleanupInactive != null) {
                                    item {
                                        if (confirmingCleanup) {
                                            FerretCard(Modifier.fillMaxWidth()) {
                                                Text("This removes only failed, zero-balance channel attempts.")
                                                Text("Your wallet and open channel are not removed.")
                                                FerretSecondaryButton(
                                                    "Confirm cleanup",
                                                    {
                                                        confirmingCleanup = false
                                                        onCleanupInactive()
                                                    },
                                                    enabled = !cleaning,
                                                )
                                                io.riverark.ferret.ui.FerretTextButton(
                                                    "Cancel",
                                                    { confirmingCleanup = false },
                                                    enabled = !cleaning,
                                                )
                                            }
                                        } else {
                                            FerretSecondaryButton(
                                                "Clean up failed channel attempts",
                                                { confirmingCleanup = true },
                                                enabled = !cleaning,
                                            )
                                        }
                                    }
                                }
                            }
                            items(notOpened, key = { it.keytag.value }) { channel ->
                                ChannelSummary(channel, labels.getValue(channel.keytag.value), catalog, detailed = true)
                            }
                        }
                    }
                }
            }
        }
        FerretSecondaryButton("Refresh", onRetry)
    }
}

@Composable
private fun AssetHoldingRow(balance: AssetBalance, channelBalance: AssetAmount?, catalog: AssetCatalog) {
    val asset = catalog.requireAsset(balance.total.asset)
    FerretCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FerretSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(assetDrawable(asset)), null, Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
                Text(assetName(asset, catalog), style = MaterialTheme.typography.titleMedium)
                FerretDataBlock("Total holdings", formatAsset(balance.total, catalog))
                FerretDataBlock("Transfer available", formatAsset(balance.spendable, catalog))
                channelBalance?.let { FerretDataBlock("In channels", formatAsset(it, catalog)) }
                if (balance.pending.baseUnits > 0) FerretDataBlock("Pending", formatAsset(balance.pending, catalog))
            }
        }
    }
}

@Composable
private fun ChannelSummary(
    snapshot: ChannelSnapshot,
    shortKeytag: String,
    catalog: AssetCatalog,
    detailed: Boolean = false,
    onAddFunds: (() -> Unit)? = null,
    onCloseChannel: (() -> Unit)? = null,
    closeDisabledReason: String? = null,
) {
    val status = channelProgressLabel(snapshot)
    FerretCard(Modifier.fillMaxWidth().semantics { stateDescription = status }) {
        FerretDataBlock("Channel", shortKeytag)
        FerretDataBlock("Asset", assetName(snapshot.asset, catalog))
        ChannelHeldFunds(snapshot, catalog)
        FerretDataBlock("Status", status)
        snapshot.pending?.let {
            FerretDataBlock("Pending operation", it.operationId)
            FerretDataBlock("Reconciliation", it.state.label())
        }
        snapshot.payments.pending?.let { FerretDataBlock("Pending payment", it.operationId) }
        if (detailed) {
            when (val state = snapshot.state) {
                is ChannelState.Opening -> FerretDataBlock("Opening transaction", state.txId)
                is ChannelState.Open -> FerretDataBlock("Opening reference", state.channelId)
                is ChannelState.Closing -> FerretDataBlock("Closing transaction", state.txId)
                else -> Unit
            }
            snapshot.history.lastOrNull { it.transactionId != null }?.let {
                FerretDataBlock("Latest channel transaction", requireNotNull(it.transactionId))
                FerretDataBlock("Latest transaction status", it.status.label())
            }
        }
        onAddFunds?.let { FerretSecondaryButton("Add funds", it) }
        onCloseChannel?.let { close ->
            FerretSecondaryButton(
                when {
                    snapshot.state is ChannelState.Open -> "Close channel"
                    snapshot.state is ChannelState.FundsReturned -> "View returned funds"
                    channelReadyToReturn(snapshot) -> "Return funds"
                    else -> "View close progress"
                },
                close,
                enabled = closeDisabledReason == null && snapshot.state != ChannelState.Absent &&
                    snapshot.state !is ChannelState.Opening,
                modifier = Modifier.semantics { closeDisabledReason?.let { stateDescription = it } },
            )
            closeDisabledReason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

}
internal fun channelDisplayOrder(channels: Collection<ChannelSnapshot>): List<ChannelSnapshot> =
    channels.sortedWith(
        compareBy<ChannelSnapshot> {
            when (it.state) {
                is ChannelState.Open -> 0
                is ChannelState.FundsReturned -> 2
                ChannelState.Absent -> 3
                else -> 1
            }
        }.thenBy { it.keytag.value },
    )

internal fun channelBalance(asset: ChannelAsset, channels: Collection<ChannelSnapshot>): AssetAmount? {
    var total: AssetAmount? = null
    channels.forEach { channel ->
        if (channel.asset == asset && channel.state !is ChannelState.FundsReturned) {
            total = (total ?: AssetAmount(asset, 0)) + channel.spendableBalance
        }
    }
    return total
}

internal fun distinctKeytagSuffixes(keytags: Collection<String>): Map<String, String> {
    require(keytags.size == keytags.toSet().size)
    var length = 12
    while (keytags.map { it.takeLast(length.coerceAtMost(it.length)) }.distinct().size != keytags.size) length += 4
    return keytags.associateWith { it.takeLast(length.coerceAtMost(it.length)) }
}

internal fun formatAsset(amount: AssetAmount, catalog: AssetCatalog): String {
    val asset = catalog.requireAsset(amount.asset)
    return if (asset == catalog.ada) "₳ ${amount.format()}" else "${amount.format()} ${assetTicker(asset, catalog)}"
}

internal fun assetName(asset: ChannelAsset, catalog: AssetCatalog): String =
    if (catalog.requireAsset(asset) == catalog.ada) "Cardano" else requireNotNull(catalog.presentations[asset.alias]).name

@Composable
private fun TransactionChange(label: String, change: io.riverark.ferret.core.cardano.TransactionOutputSummary?, catalog: AssetCatalog) {
    FerretDataBlock("$label · ADA", formatAsset(AssetAmount(catalog.ada, change?.lovelace?.value ?: 0), catalog))
    change?.assets?.entries?.sortedBy { it.key }?.forEach { (unit, quantity) ->
        val asset = catalog.assetForConnectorUnit(unit)
        FerretDataBlock(
            "$label · ${asset?.let { assetTicker(it, catalog) } ?: unit}",
            asset?.let { formatAsset(AssetAmount(it, quantity), catalog) } ?: quantity.toString(),
        )
    }
}

private fun assetTicker(asset: ChannelAsset, catalog: AssetCatalog): String =
    if (catalog.requireAsset(asset) == catalog.ada) "ADA" else requireNotNull(catalog.presentations[asset.alias]).ticker

private fun assetDrawable(asset: ChannelAsset): DrawableResource = when (asset.connectorUnit) {
    "lovelace" -> Res.drawable.account_balance_wallet
    "1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e345553444378" ->
        Res.drawable.asset_1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e34_5553444378
    "c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad0014df105553444d" ->
        Res.drawable.asset_c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad_0014df105553444d
    "fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae45655534441" ->
        Res.drawable.asset_fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae456_55534441
    else -> error("Asset catalog unavailable.")
}

internal fun OperationState.label() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

@Composable
fun TransferScreen(
    profile: WalletProfile,
    catalog: AssetCatalog,
    destinations: List<TransferDestination>,
    state: TransferUiState,
    onPreview: (TransferDestination, AssetAmount) -> Unit,
    onPreviewSweep: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    var recipientAddress by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var selectedAlias by rememberSaveable { mutableStateOf(catalog.ada.alias) }
    val selectedAsset = requireNotNull(catalog.asset(selectedAlias))
    val trimmedAddress = recipientAddress.trim()
    val savedDestination = destinations.firstOrNull { it.address == trimmedAddress }
    val destination = trimmedAddress.takeIf(String::isNotBlank)?.let {
        savedDestination ?: TransferDestination("External address", it)
    }
    val parsedAmount = parseAssetAmount(selectedAsset, amount)
    val editable = state.preview == null && state.sweepPreview == null && !state.busy
    FerretScreen {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm),
        ) {
            item {
                FerretTopBar("Transfer asset", navigation = {
                    io.riverark.ferret.ui.FerretTextButton("Back", onBack)
                })
            }
            item { FerretStatusChip(profile.network.name) }
            item {
                Text("Send a catalog asset to any ${profile.network.name.lowercase()} address", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    recipientAddress,
                    { recipientAddress = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Recipient address") },
                    singleLine = true,
                    enabled = editable,
                )
            }
            items(destinations, key = TransferDestination::address) { wallet ->
                FerretListRow(
                    wallet.name,
                    wallet.address,
                    onClick = { if (editable) recipientAddress = wallet.address },
                    trailing = { if (savedDestination == wallet) Text("Selected") },
                )
            }
            items(catalog.assets, key = ChannelAsset::alias) { asset ->
                FerretListRow(
                    assetName(asset, catalog),
                    assetTicker(asset, catalog),
                    onClick = {
                        if (editable) {
                            selectedAlias = asset.alias
                            amount = ""
                        }
                    },
                    trailing = { if (selectedAsset == asset) Text("Selected") },
                )
            }
            item {
                OutlinedTextField(
                    amount,
                    { amount = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("${assetTicker(selectedAsset, catalog)} amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = editable,
                )
            }
            state.preview?.let { preview ->
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        FerretDataBlock("Recipient", preview.destination.name)
                        FerretDataBlock("Address", preview.destination.address)
                        FerretDataBlock("Amount", formatAsset(preview.amount, catalog))
                        FerretDataBlock("Fee", formatAsset(preview.feeBound, catalog))
                        FerretDataBlock("Recipient ADA", formatAsset(preview.recipientAda, catalog))
                        FerretDataBlock("Ledger minimum ADA", formatAsset(preview.ledgerMinAda, catalog))
                        TransactionChange("Change", preview.change, catalog)
                        preview.transactionId?.let { FerretDataBlock("Transaction ID", it) }
                    }
                }
            }
            state.sweepPreview?.let { preview ->
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        FerretDataBlock("Recipient", savedDestination?.name ?: "External address")
                        FerretDataBlock("Address", preview.destinationAddress)
                        FerretDataBlock("Amount", formatAsset(preview.amount, catalog))
                        FerretDataBlock("Fee", formatAsset(preview.fee, catalog))
                        FerretDataBlock("Change", formatAsset(AssetAmount(catalog.ada, 0), catalog))
                        FerretDataBlock("Network", profile.network.name)
                    }
                }
            }
            state.operationId?.let { operationId -> item { FerretDataBlock("Operation submitted", operationId) } }
            state.error?.let { error -> item { FerretErrorState(error) } }
            if (state.preview == null && state.sweepPreview == null) {
                item {
                    FerretPrimaryButton(
                        "Preview transfer",
                        { onPreview(checkNotNull(destination), checkNotNull(parsedAmount)) },
                        enabled = destination != null && parsedAmount != null && !state.busy,
                    )
                }
                item {
                    FerretSecondaryButton(
                        "Preview send all",
                        { onPreviewSweep(trimmedAddress) },
                        enabled = destination != null && selectedAsset == catalog.ada && !state.busy,
                    )
                }
            } else {
                item {
                    FerretPrimaryButton("Confirm transfer", onSubmit, enabled = !state.busy && state.operationId == null)
                }
            }
        }
    }
}

@Composable
fun ChannelFundingScreen(
    profile: WalletProfile,
    catalog: AssetCatalog,
    targetChannel: ChannelSnapshot?,
    state: ChannelFundingUiState,
    onAmountChanged: () -> Unit,
    onPreview: (AssetAmount) -> Unit,
    onSubmit: () -> Unit,
    onStatus: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var amount by remember(targetChannel?.keytag?.value) { mutableStateOf("") }
    var selectedAlias by rememberSaveable(targetChannel?.keytag?.value) {
        mutableStateOf(targetChannel?.asset?.alias ?: catalog.ada.alias)
    }
    val selectedAsset = targetChannel?.asset ?: requireNotNull(catalog.asset(selectedAlias))
    val parsedAmount = parseAssetAmount(selectedAsset, amount)
    FerretScreen {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm),
        ) {
            item {
                FerretTopBar(if (targetChannel == null) "Open channel" else "Add funds", navigation = {
                    io.riverark.ferret.ui.FerretTextButton("Back", onBack)
                })
            }
            item { FerretStatusChip("${profile.network.name} · ${profile.name}") }
            item {
                Text(
                    if (targetChannel == null) {
                        "The channel deposit includes any required ADA output and the protocol reserve. The transaction fee is separate."
                    } else {
                        "Add funds to channel ${targetChannel.keytag.value.takeLast(12)}. Collateral is reserved separately and is at risk only if the script fails."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            targetChannel?.let { channel ->
                item { FerretDataBlock("Current capacity", formatAsset(channel.spendableBalance, catalog)) }
            }
            if (targetChannel == null) {
                items(catalog.assets, key = ChannelAsset::alias) { asset ->
                    FerretListRow(
                        assetName(asset, catalog),
                        assetTicker(asset, catalog),
                        onClick = {
                            if (!state.busy && state.preview == null) {
                                selectedAlias = asset.alias
                                amount = ""
                                onAmountChanged()
                            }
                        },
                        trailing = { if (selectedAsset == asset) Text("Selected") },
                    )
                }
            }
            item {
                OutlinedTextField(
                    amount,
                    {
                        amount = it
                        onAmountChanged()
                    },
                    Modifier.fillMaxWidth().semantics {
                        stateDescription = if (state.busy) "Processing" else "Editable"
                    },
                    enabled = !state.busy,
                    label = { Text(if (targetChannel == null) "Channel deposit (${assetTicker(selectedAsset, catalog)})" else "Amount to add (${assetTicker(selectedAsset, catalog)})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
            state.preview?.let { preview ->
                item {
                    FerretCard(Modifier.fillMaxWidth()) {
                        FerretDataBlock(if (targetChannel == null) "Deposit" else "Added amount", formatAsset(preview.amount, catalog))
                        FerretDataBlock("Transaction fee", formatAsset(preview.actualFee, catalog))
                        targetChannel?.let { FerretDataBlock("Old capacity", formatAsset(it.spendableBalance, catalog)) }
                        FerretDataBlock("Channel output ADA", formatAsset(preview.outputAda, catalog))
                        FerretDataBlock("Ledger minimum ADA", formatAsset(preview.ledgerMinAda, catalog))
                        FerretDataBlock("Protocol reserve", formatAsset(preview.protocolReserve, catalog))
                        if (targetChannel != null && selectedAsset.policyId != null) {
                            val intent = (preview.operation.payload as? io.riverark.ferret.core.channel.ChannelPayload.Transaction)
                                ?.intent as? io.riverark.ferret.core.cardano.CardanoIntent.AddChannelFunds
                            intent?.let {
                                FerretDataBlock(
                                    "Extra output ADA",
                                    formatAsset(AssetAmount(catalog.ada, preview.outputAda.baseUnits - it.channelInput.lovelace.value), catalog),
                                )
                            }
                        }
                        FerretDataBlock(if (targetChannel == null) "Channel capacity" else "Projected capacity", formatAsset(preview.resultingSpendableBalance, catalog))
                        preview.collateral?.let {
                            FerretDataBlock("Collateral at risk (ADA)", formatAsset(it, catalog))
                            Text("Collateral is not charged when the transaction succeeds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TransactionChange("Wallet change", preview.sourceChange, catalog)
                    }
                }
            }
            state.error?.let { error -> item { FerretErrorState(error) } }
            item {
                if (state.busy) {
                    FerretLoadingState(if (state.preview == null) "Preparing channel funding" else "Submitting channel funding")
                } else {
                    when {
                        state.operationId != null && state.error != null ->
                            FerretPrimaryButton("View channel status", onStatus)
                        state.preview != null ->
                            FerretPrimaryButton(if (targetChannel == null) "Open channel" else "Add funds", onSubmit)
                        else ->
                            FerretPrimaryButton(
                                if (targetChannel == null) "Preview channel" else "Preview addition",
                                { onPreview(checkNotNull(parsedAmount)) },
                                enabled = parsedAmount != null,
                            )
                    }
                }
            }
            if (state.error != null && state.operationId == null) {
                item { FerretSecondaryButton("Verify backup in Settings", onSettings) }
            }
        }
    }
}

internal fun parseAssetAmount(asset: ChannelAsset, value: String): AssetAmount? =
    runCatching { asset.parseAmount(value) }.getOrNull()?.takeIf { it.baseUnits > 0 }

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
    catalog: AssetCatalog,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    val channelLabels = distinctKeytagSuffixes(state.records.mapNotNull { it.channelKeytag?.value }.distinct())
    FerretScreen {
        FerretTopBar("History", navigation = { io.riverark.ferret.ui.FerretTextButton("Back", onBack) })
        state.lastRefreshEpochMillis?.let { Text("Last refreshed: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    items(state.records, key = { "${it.realm}:${it.channelKeytag?.value.orEmpty()}:${it.id}" }) { record ->
                        val rowId = "${record.realm}:${record.channelKeytag?.value.orEmpty()}:${record.id}"
                        FerretCard(
                            Modifier.fillMaxWidth(),
                            onClick = { expandedId = rowId.takeUnless { it == expandedId } },
                        ) {
                            record.amounts.forEach {
                                Text("${record.realm}: ${formatAsset(it, catalog)}", style = MaterialTheme.typography.titleMedium)
                            }
                            record.channelKeytag?.let { FerretDataBlock("Channel", channelLabels.getValue(it.value)) }
                            Text(
                                "${record.state} · fee ${formatAsset(record.fee, catalog)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(record.id, maxLines = if (expandedId == rowId) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                            if (expandedId == rowId) {
                                FerretDataBlock("Recorded at", record.timestampEpochMillis.toString())
                                FerretDataBlock("Realm", record.realm.name)
                            }
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
private const val LEGACY_CHANNEL_MESSAGE =
    "Legacy channel recovery requires verified identity. Some older channel records could not be safely matched, so channel actions are disabled. Recovered balances remain visible."

internal fun recoveryVerificationIndexes(wordCount: Int, random: Random = Random.Default): List<Int> {
    require(wordCount >= 3)
    return (0 until wordCount).shuffled(random).take(3).sorted()
}
