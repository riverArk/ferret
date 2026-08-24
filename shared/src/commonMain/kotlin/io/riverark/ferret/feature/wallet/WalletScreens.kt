package io.riverark.ferret.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletProfile

@Composable
fun WalletPickerScreen(state: WalletPickerUiState, onSelect: (WalletProfile) -> Unit, onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Wallets", style = MaterialTheme.typography.headlineLarge)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardanoNetwork.entries.forEach { network ->
                item { Text(network.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium) }
                items(state.wallets.filter { it.network == network }, key = { it.id.value }) { wallet ->
                    Card(onClick = { onSelect(wallet) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { Text(wallet.name); Text(wallet.paymentAddress) }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCreate, enabled = !state.busy) { Text("Create wallet") }
            Button(onClick = onRestore, enabled = !state.busy) { Text("Restore wallet") }
        }
    }
}

@Composable
fun RecoveryPhraseScreen(words: List<String>, verificationIndexes: Set<Int>, answers: Map<Int, String>, onAnswer: (Int, String) -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Record these 24 words", style = MaterialTheme.typography.headlineMedium)
        words.forEachIndexed { index, word -> Text("${index + 1}. $word") }
        verificationIndexes.sorted().forEach { index ->
            OutlinedTextField(answers[index].orEmpty(), { onAnswer(index, it) }, label = { Text("Word ${index + 1}") })
        }
        Button(onClick = onConfirm, enabled = verificationIndexes.all { answers[it]?.trim() == words[it] }) { Text("Confirm recovery phrase") }
    }
}

@Composable
fun TopUpScreen(profile: WalletProfile, onCopy: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Add ADA", style = MaterialTheme.typography.headlineLarge)
        Text(profile.network.name)
        Text(profile.paymentAddress)
        Button(onClick = onCopy) { Text("Copy address") }
    }
}

@Composable
fun HistoryScreen(records: List<TransactionRecord>) {
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(records, key = TransactionRecord::id) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${record.realm}: ${record.amount.value} lovelace")
                    Text("${record.state} · fee ${record.fee.value}")
                    Text(record.id)
                }
            }
        }
    }
}
