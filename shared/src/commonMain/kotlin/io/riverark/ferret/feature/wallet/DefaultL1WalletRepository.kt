package io.riverark.ferret.feature.wallet

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.requireMatches
import io.riverark.ferret.core.cardano.SweepPreview
import io.riverark.ferret.core.cardano.requireL1Funding
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.TransactionSummary
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.ConnectorClient
import io.riverark.ferret.core.network.L1OperationDto
import io.riverark.ferret.core.network.L1SubmitRequest
import io.riverark.ferret.core.security.WalletOperationJournalV2
import io.riverark.ferret.core.security.SecureVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmInline

@Serializable
enum class L1OperationState { PREPARED, SUBMITTING, PENDING, CONFIRMED, SETTLED, REJECTED }

@Serializable
data class L1OperationRecord(
    val operationId: String,
    val expectedTransactionId: String? = null,
    val destinationWalletId: WalletId? = null,
    val destinationAddress: String? = null,
    val amount: AssetAmount,
    val fee: AssetAmount,
    val recipientAda: AssetAmount,
    val createdAtEpochMillis: Long,
    val state: L1OperationState,
) {
    init {
        require(OPERATION_ID.matches(operationId))
        require(expectedTransactionId == null || TRANSACTION_ID.matches(expectedTransactionId))
        require(amount.asset.catalogDigest == fee.asset.catalogDigest)
        require(fee.asset == recipientAda.asset && fee.asset.policyId == null)
        require(createdAtEpochMillis >= 0)
        require(destinationAddress == null || destinationAddress.isNotBlank())
    }
}

@Serializable
private data class L1OperationsV3(val schema: Int = 3, val records: List<L1OperationRecord>)
@Serializable
private data class L1OperationRecordV2(
    val operationId: String,
    val expectedTransactionId: String? = null,
    val destinationWalletId: WalletId? = null,
    val destinationAddress: String? = null,
    val amount: AssetAmount,
    val fee: AssetAmount,
    val createdAtEpochMillis: Long,
    val state: L1OperationState,
)

@Serializable
private data class L1OperationsV2(val schema: Int = 2, val records: List<L1OperationRecordV2>)

@Serializable
@JvmInline
private value class LegacyLovelace(val value: Long)

@Serializable
private data class LegacyL1OperationRecord(
    val operationId: String,
    val expectedTransactionId: String? = null,
    val destinationWalletId: WalletId? = null,
    val destinationAddress: String? = null,
    val amount: LegacyLovelace,
    val fee: LegacyLovelace,
    val createdAtEpochMillis: Long,
    val state: L1OperationState,
)

@Serializable
private data class LegacyL1OperationsV1(val records: List<LegacyL1OperationRecord>)

@Serializable
private data class LegacyWalletOperationJournalV1(
    val l1: ByteArray = byteArrayOf(),
    val channel: ByteArray = byteArrayOf(),
    val payment: ByteArray = byteArrayOf(),
)

private val OPERATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val TRANSACTION_ID = Regex("[0-9a-f]{64}")

class DefaultL1WalletRepository(
    private val wallets: WalletRepository,
    private val vault: SecureVault,
    private val catalog: AssetCatalog,
    private val loadLedger: suspend (WalletProfile) -> LedgerSnapshot,
    private val loadTransactions: suspend (WalletProfile) -> List<TransactionRecord>,
    private val submitOperation: suspend (WalletProfile, L1SubmitRequest) -> L1OperationDto,
    private val lookupOperation: suspend (WalletProfile, String) -> L1OperationDto,
    private val engine: CardanoTransactionEngine,
    private val newOperationId: () -> String,
    private val nowEpochMillis: () -> Long,
    private val hasPendingChannel: suspend (WalletId) -> Boolean = { false },
) : L1WalletRepository {
    constructor(
        wallets: WalletRepository,
        vault: SecureVault,
        catalog: AssetCatalog,
        connector: (WalletProfile) -> ConnectorClient,
        engine: CardanoTransactionEngine,
        newOperationId: () -> String,
        nowEpochMillis: () -> Long,
        hasPendingChannel: suspend (WalletId) -> Boolean = { false },
    ) : this(
        wallets,
        vault,
        catalog,
        { profile -> connector(profile).ledger(profile.paymentAddress, profile.network) },
        { profile -> connector(profile).transactions(profile.paymentAddress, catalog) },
        { profile, request -> connector(profile).submitL1(request) },
        { profile, operationId -> connector(profile).operation(operationId) },
        engine,
        newOperationId,
        nowEpochMillis,
        hasPendingChannel = hasPendingChannel,
    )
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun balance(walletId: WalletId): WalletBalance {
        val profile = profile(walletId)
        val ledger = loadLedger(profile)
        require(ledger.utxos.size <= MAX_LEDGER_UTXOS) { "too many wallet outputs" }
        val owned = ledger.utxos.filter { it.address == profile.paymentAddress }
        val totals = mutableMapOf("lovelace" to 0L)
        owned.forEach { utxo ->
            totals.add("lovelace", utxo.lovelace.value)
            utxo.assets.forEach { (unit, quantity) -> totals.add(unit, quantity) }
        }
        val eligibleOwned = owned.filter { it.isSpendableBy(profile.paymentAddress, allowNativeAssets = true) }
        val adaSpendable = owned.filter { it.isSpendableBy(profile.paymentAddress) }
            .fold(0L) { total, utxo -> addUnits(total, utxo.lovelace.value) }
        val reservations = mutableMapOf<String, Long>()
        operations(walletId).filter { it.state in UNRESOLVED_STATES }.forEach { operation ->
            val selected = catalog.requireAsset(operation.amount.asset)
            requireAda(operation.fee)
            requireAda(operation.recipientAda)
            if (selected == catalog.ada) {
                require(operation.recipientAda == operation.amount)
                reservations.add(selected.connectorUnit, addUnits(operation.amount.baseUnits, operation.fee.baseUnits))
            } else {
                reservations.add(selected.connectorUnit, operation.amount.baseUnits)
                reservations.add(
                    catalog.ada.connectorUnit,
                    addUnits(operation.recipientAda.baseUnits, operation.fee.baseUnits),
                )
            }
        }
        val assets = catalog.assets.map { asset ->
            val total = totals.remove(asset.connectorUnit) ?: 0L
            val reserved = reservations[asset.connectorUnit] ?: 0L
            val eligible = if (asset == catalog.ada) {
                adaSpendable
            } else {
                eligibleOwned.fold(0L) { sum, utxo -> addUnits(sum, utxo.assets[asset.connectorUnit] ?: 0L) }
            }
            AssetBalance(
                AssetAmount(asset, total),
                AssetAmount(asset, (eligible - reserved).coerceAtLeast(0)),
                AssetAmount(asset, reserved),
            )
        }
        require(totals.size <= MAX_UNSUPPORTED_ASSETS) { "too many unsupported assets" }
        return WalletBalance(assets, totals.filterValues { it > 0 }.entries.sortedBy { it.key }.associate { it.toPair() })
    }

    suspend fun hasNativeAssets(walletId: WalletId): Boolean {
        val profile = profile(walletId)
        return loadLedger(profile).utxos.any {
            it.address == profile.paymentAddress && it.assets.values.any { quantity -> quantity > 0 }
        }
    }
    override suspend fun history(walletId: WalletId): List<TransactionRecord> {
        val profile = profile(walletId)
        val remote = loadTransactions(profile)
        val local = operations(walletId)
        return (remote + local
            .filterNot { operation -> remote.any { it.id == (operation.expectedTransactionId ?: operation.operationId) } }
            .map { operation ->
                TransactionRecord(
                    operation.expectedTransactionId ?: operation.operationId,
                    operation.createdAtEpochMillis,
                    if (operation.amount.asset == catalog.ada) listOf(operation.amount)
                    else listOf(operation.amount, operation.recipientAda),
                    operation.fee,
                    io.riverark.ferret.core.model.Realm.L1,
                    when (operation.state) {
                        L1OperationState.CONFIRMED -> io.riverark.ferret.core.model.TransactionState.CONFIRMED
                        L1OperationState.SETTLED -> io.riverark.ferret.core.model.TransactionState.SETTLED
                        L1OperationState.REJECTED -> io.riverark.ferret.core.model.TransactionState.FAILED
                        else -> io.riverark.ferret.core.model.TransactionState.PENDING
                    },
                )
            }).sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })
    }

    override suspend fun previewTransfer(walletId: WalletId, destination: TransferDestination, amount: AssetAmount): TransferPreview {
        val selected = catalog.requireAsset(amount.asset)
        require(selected == amount.asset && amount.baseUnits > 0)
        return wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(profile.network.accepts(destination.address) && destination.address != profile.paymentAddress)
            val ledger = loadLedger(profile)
            require(ledger.currentSlot <= Long.MAX_VALUE - TRANSFER_VALIDITY_SLOTS)
            val intent = CardanoIntent.Transfer(
                profile.paymentAddress,
                destination.address,
                amount,
                newOperationId(),
                ledger.currentSlot,
                ledger.currentSlot + TRANSFER_VALIDITY_SLOTS,
            )
            val unsigned = engine.build(intent, ledger)
            require(unsigned.operationId == intent.operationId)
            val summary = engine.inspect(unsigned.cbor)
            summary.requireMatches(intent, profile.network, unsigned.feeBound)
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            summary.requireL1Funding(intent, ledger)
            engine.requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)
            val (recipientIndex, recipient) = transferOutput(summary, intent)
            TransferPreview(
                destination,
                amount,
                AssetAmount(catalog.ada, unsigned.feeBound.value),
                summary.outputs.singleOrNull { it.address == profile.paymentAddress },
                AssetAmount(catalog.ada, recipient.lovelace.value),
                AssetAmount(
                    catalog.ada,
                    engine.minimumAdaForOutput(unsigned.cbor, ledger.protocolParametersJson, recipientIndex).value,
                ),
                intent,
                unsigned,
                engine.transactionId(unsigned.cbor),
            )
        }
    }

    override suspend fun submitTransfer(walletId: WalletId, preview: TransferPreview): String =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(profile.network.accepts(preview.destination.address))
            val intent = requireNotNull(preview.intent)
            val unsigned = requireNotNull(preview.unsigned)
            val previewTransactionId = requireNotNull(preview.transactionId)
            require(intent.sourceAddress == profile.paymentAddress)
            require(intent.operationId == unsigned.operationId)
            require(intent.destinationAddress == preview.destination.address)
            require(intent.amount == preview.amount && catalog.requireAsset(preview.amount.asset) == preview.amount.asset)
            require(preview.amount.baseUnits > 0)
            require(requireAda(preview.feeBound) == unsigned.feeBound)
            val submitLedger = loadLedger(profile)
            require(submitLedger.network == profile.network)
            val summary = engine.inspect(unsigned.cbor)
            summary.requireMatches(intent, profile.network, requireAda(preview.feeBound))
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            summary.requireL1Funding(intent, submitLedger)
            val (recipientIndex, recipient) = transferOutput(summary, intent)
            require(preview.change == summary.outputs.singleOrNull { it.address == profile.paymentAddress })
            require(requireAda(preview.recipientAda) == recipient.lovelace)
            require(
                requireAda(preview.ledgerMinAda) ==
                    engine.minimumAdaForOutput(unsigned.cbor, submitLedger.protocolParametersJson, recipientIndex),
            )
            require(engine.transactionId(unsigned.cbor) == previewTransactionId)
            engine.requireAuthorized(unsigned, intent, submitLedger)
            val existing = operations(walletId)
            require(existing.none { it.state in UNRESOLVED_STATES }) { "another wallet operation is unresolved" }
            require(!hasPendingChannel(walletId)) { "channel operation is unresolved" }
            val prepared = L1OperationRecord(
                intent.operationId,
                destinationWalletId = vault.profiles()
                    .singleOrNull { it.network == profile.network && it.paymentAddress == preview.destination.address }
                    ?.id,
                destinationAddress = preview.destination.address,
                amount = preview.amount,
                fee = preview.feeBound,
                recipientAda = preview.recipientAda,
                createdAtEpochMillis = nowEpochMillis(),
                state = L1OperationState.PREPARED,
            )
            writeOperation(walletId, prepared)
            val signed = vault.withWalletSeed(walletId) { engine.sign(unsigned, it, intent, submitLedger) }
            try {
                val transactionId = engine.transactionId(signed.cbor)
                require(transactionId == previewTransactionId)
                val submitting = prepared.copy(
                    expectedTransactionId = transactionId,
                    state = L1OperationState.SUBMITTING,
                )
                writeOperation(walletId, submitting)
                val remote = submitOperation(profile, L1SubmitRequest(intent.operationId, transactionId, signed.cbor.hex()))
                writeOperation(walletId, submitting.withRemote(remote))
                intent.operationId
            } finally {
                signed.cbor.fill(0)
            }
        }

    override suspend fun previewSweep(walletId: WalletId, destinationAddress: String): SweepPreview =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(profile.network.accepts(destinationAddress) && destinationAddress != profile.paymentAddress)
            val ledger = loadLedger(profile)
            val owned = ledger.utxos.filter { it.address == profile.paymentAddress }
            require(owned.none { it.assets.values.any { quantity -> quantity > 0 } }) { "move native assets before sweeping" }
            val total = owned
                .filter { it.isSpendableBy(profile.paymentAddress) }
                .fold(Lovelace(0)) { sum, utxo -> sum + utxo.lovelace }
            require(total.value > INITIAL_SWEEP_FEE)
            val provisional = CardanoIntent.SweepWallet(
                profile.paymentAddress,
                destinationAddress,
                Lovelace(total.value - INITIAL_SWEEP_FEE),
                newOperationId(),
                ledger.currentSlot,
                ledger.currentSlot + TRANSFER_VALIDITY_SLOTS,
            )
            val unsigned = engine.buildSweep(provisional, ledger)
            val summary = engine.inspect(unsigned.cbor)
            val amount = summary.outputs.singleOrNull()?.takeIf { it.address == destinationAddress }?.lovelace
                ?: error("sweep must have one destination output")
            val intent = provisional.copy(amount = amount)
            summary.requireMatches(intent, profile.network, unsigned.feeBound)
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            summary.requireL1Funding(intent, ledger)
            require(amount + summary.fee == total)
            engine.requireMinimumAda(unsigned.cbor, ledger.protocolParametersJson)
            SweepPreview(
                destinationAddress,
                AssetAmount(catalog.ada, amount.value),
                AssetAmount(catalog.ada, summary.fee.value),
                intent,
                unsigned,
                engine.transactionId(unsigned.cbor),
            )
        }

    override suspend fun submitSweep(walletId: WalletId, preview: SweepPreview): String =
        wallets.withWalletLock(walletId) {
            val profile = profile(walletId)
            require(profile.network.accepts(preview.destinationAddress))
            require(preview.intent.sourceAddress == profile.paymentAddress)
            require(preview.intent.destinationAddress == preview.destinationAddress)
            require(preview.intent.amount == requireAda(preview.amount) && preview.intent.operationId == preview.unsigned.operationId)
            val summary = engine.inspect(preview.unsigned.cbor)
            summary.requireMatches(preview.intent, profile.network, preview.unsigned.feeBound)
            summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = false)
            require(summary.outputs.singleOrNull()?.let {
                it.address == preview.destinationAddress && it.lovelace == requireAda(preview.amount) && it.assets.isEmpty()
            } == true)
            require(summary.fee == requireAda(preview.fee))
            require(engine.transactionId(preview.unsigned.cbor) == preview.expectedTransactionId)
            val submitLedger = loadLedger(profile)
            require(submitLedger.network == profile.network)
            engine.requireAuthorized(preview.unsigned, preview.intent, submitLedger)
            val existing = operations(walletId)
            require(existing.none { it.state in UNRESOLVED_STATES }) { "another wallet operation is unresolved" }
            require(!hasPendingChannel(walletId)) { "channel operation is unresolved" }
            val prepared = L1OperationRecord(
                preview.intent.operationId,
                destinationAddress = preview.destinationAddress,
                amount = preview.amount,
                fee = preview.fee,
                recipientAda = preview.amount,
                createdAtEpochMillis = nowEpochMillis(),
                state = L1OperationState.PREPARED,
            )
            writeOperation(walletId, prepared)
            val signed = vault.withWalletSeed(walletId) {
                engine.sign(preview.unsigned, it, preview.intent, submitLedger)
            }
            try {
                val transactionId = engine.transactionId(signed.cbor)
                require(transactionId == preview.expectedTransactionId)
                val submitting = prepared.copy(expectedTransactionId = transactionId, state = L1OperationState.SUBMITTING)
                writeOperation(walletId, submitting)
                val remote = submitOperation(
                    profile,
                    L1SubmitRequest(preview.intent.operationId, transactionId, signed.cbor.hex()),
                )
                writeOperation(walletId, submitting.withRemote(remote))
                preview.intent.operationId
            } finally {
                signed.cbor.fill(0)
            }
        }

    suspend fun reconcilePending(walletId: WalletId): L1OperationRecord? = wallets.withWalletLock(walletId) {
        val local = operations(walletId)
        if (local.isEmpty()) return@withWalletLock null
        val profile = profile(walletId)
        val reconciled = local.map { operation ->
            when (operation.state) {
                L1OperationState.PREPARED -> operation.copy(state = L1OperationState.REJECTED)
                L1OperationState.SETTLED, L1OperationState.REJECTED -> operation
                else -> operation.withRemote(lookupOperation(profile, operation.operationId))
            }
        }
        writeOperations(walletId, reconciled)
        reconciled.last()
    }

    suspend fun operation(walletId: WalletId): L1OperationRecord? = operations(walletId).lastOrNull()

    suspend fun operations(walletId: WalletId): List<L1OperationRecord> {
        val bytes = vault.walletState(walletId).operationJournal
        if (bytes.isEmpty()) return emptyList()
        val journal = try {
            decodeJournal(bytes, forWrite = false)
        } finally {
            bytes.fill(0)
        }
        if (journal.l1.isEmpty()) {
            journal.channels.fill(0)
            return emptyList()
        }
        return try {
            decodeOperations(journal.l1)
        } finally {
            journal.l1.fill(0)
            journal.channels.fill(0)
        }
    }

    private suspend fun writeOperation(walletId: WalletId, operation: L1OperationRecord) {
        val operations = operations(walletId).toMutableList()
        val existing = operations.indexOfFirst { it.operationId == operation.operationId }
        if (existing < 0) operations += operation else operations[existing] = operation
        writeOperations(walletId, operations)
    }

    private suspend fun writeOperations(walletId: WalletId, operations: List<L1OperationRecord>) {
        validateOperations(operations)
        val current = vault.walletState(walletId)
        var journal: WalletOperationJournalV2? = null
        var l1 = byteArrayOf()
        var encoded = byteArrayOf()
        try {
            journal = if (current.operationJournal.isEmpty()) WalletOperationJournalV2() else
                decodeJournal(current.operationJournal, forWrite = true)
            l1 = json.encodeToString(L1OperationsV3(records = operations)).encodeToByteArray()
            require(l1.size <= MAX_L1_JOURNAL_BYTES) { "L1 operation journal is too large" }
            encoded = json.encodeToString(requireNotNull(journal).copy(l1 = l1)).encodeToByteArray()
            vault.updateWalletState(walletId, current.copy(operationJournal = encoded))
        } finally {
            current.channelRecovery.fill(0)
            current.operationJournal.fill(0)
            journal?.l1?.fill(0)
            journal?.channels?.fill(0)
            l1.fill(0)
            encoded.fill(0)
        }
    }

    private fun decodeJournal(bytes: ByteArray, forWrite: Boolean): WalletOperationJournalV2 {
        require(bytes.size <= MAX_L1_JOURNAL_BYTES) { "wallet operation journal is too large" }
        val encoded = bytes.decodeToString()
        val root = json.parseToJsonElement(encoded).jsonObject
        if ("schema" in root) {
            val journal = json.decodeFromString<WalletOperationJournalV2>(encoded)
            if (journal.schema != 2) {
                journal.l1.fill(0)
                journal.channels.fill(0)
                error("unsupported wallet operation journal")
            }
            return journal
        }
        require("channels" !in root) { "wallet operation journal schema is required" }
        val legacy = json.decodeFromString<LegacyWalletOperationJournalV1>(encoded)
        try {
            if (forWrite && legacy.payment.isNotEmpty()) {
                legacy.l1.fill(0)
                legacy.channel.fill(0)
                error("legacy payment journal requires migration")
            }
            return WalletOperationJournalV2(l1 = legacy.l1, channels = legacy.channel)
        } finally {
            legacy.payment.fill(0)
        }
    }

    private fun decodeOperations(bytes: ByteArray): List<L1OperationRecord> {
        require(bytes.size <= MAX_L1_JOURNAL_BYTES) { "L1 operation journal is too large" }
        val encoded = bytes.decodeToString()
        val root = json.parseToJsonElement(encoded).jsonObject
        val records = when (root["schema"]?.jsonPrimitive?.content?.toIntOrNull()) {
            3 -> json.decodeFromString<L1OperationsV3>(encoded).also {
                require(it.schema == 3) { "unsupported L1 operation journal" }
            }.records
            2 -> json.decodeFromString<L1OperationsV2>(encoded).also {
                require(it.schema == 2) { "unsupported L1 operation journal" }
            }.records.map {
                val amount = catalog.requireAsset(it.amount.asset).let { asset ->
                    require(asset == catalog.ada)
                    AssetAmount(asset, it.amount.baseUnits)
                }
                val fee = AssetAmount(catalog.ada, requireAda(it.fee).value)
                L1OperationRecord(
                    it.operationId,
                    it.expectedTransactionId,
                    it.destinationWalletId,
                    it.destinationAddress,
                    amount,
                    fee,
                    amount,
                    it.createdAtEpochMillis,
                    it.state,
                )
            }
            null -> {
                val legacy = if ("records" in root) {
                    json.decodeFromString<LegacyL1OperationsV1>(encoded).records
                } else {
                    listOf(json.decodeFromString<LegacyL1OperationRecord>(encoded))
                }
                legacy.map {
                    val amount = AssetAmount(catalog.ada, it.amount.value)
                    L1OperationRecord(
                        it.operationId,
                        it.expectedTransactionId,
                        it.destinationWalletId,
                        it.destinationAddress,
                        amount,
                        AssetAmount(catalog.ada, it.fee.value),
                        amount,
                        it.createdAtEpochMillis,
                        it.state,
                    )
                }
            }
            else -> error("unsupported L1 operation journal")
        }
        validateOperations(records)
        return records
    }

    private fun validateOperations(operations: List<L1OperationRecord>) {
        require(operations.size <= MAX_L1_OPERATIONS && operations.map(L1OperationRecord::operationId).distinct().size == operations.size)
        operations.forEach {
            catalog.requireAsset(it.amount.asset)
            requireAda(it.fee)
            requireAda(it.recipientAda)
            if (it.amount.asset == catalog.ada) require(it.recipientAda == it.amount)
        }
    }

    private fun requireAda(amount: AssetAmount): Lovelace {
        catalog.requireAsset(amount.asset)
        require(amount.asset == catalog.ada) { "Native asset transfer is unavailable." }
        return Lovelace(amount.baseUnits)
    }
    private fun transferOutput(
        summary: TransactionSummary,
        intent: CardanoIntent.Transfer,
    ): IndexedValue<TransactionOutputSummary> = summary.outputs.withIndex().single { (_, output) ->
        output.address == intent.destinationAddress &&
            if (intent.amount.asset.policyId == null) {
                output.lovelace.value == intent.amount.baseUnits && output.assets.isEmpty()
            } else {
                output.assets == mapOf(intent.amount.asset.connectorUnit to intent.amount.baseUnits)
            }
    }

    private fun MutableMap<String, Long>.add(unit: String, quantity: Long) {
        this[unit] = addUnits(this[unit] ?: 0L, quantity)
    }

    private fun addUnits(left: Long, right: Long): Long {
        require(right >= 0 && left <= Long.MAX_VALUE - right) { "asset amount overflow" }
        return left + right
    }

    private suspend fun profile(walletId: WalletId): WalletProfile =
        vault.profiles().single { it.id == walletId }


    private fun L1OperationRecord.withRemote(remote: L1OperationDto): L1OperationRecord {
        require(remote.operationId == operationId && remote.expectedTransactionId == expectedTransactionId) {
            "operation identity mismatch"
        }
        return copy(
            state = when (remote.status) {
                "confirmed" -> L1OperationState.CONFIRMED
                "settled" -> L1OperationState.SETTLED
                "rejected" -> L1OperationState.REJECTED
                else -> L1OperationState.PENDING
            },
        )
    }

    private fun ByteArray.hex() = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun io.riverark.ferret.core.model.CardanoNetwork.accepts(address: String) =
        if (this == io.riverark.ferret.core.model.CardanoNetwork.MAINNET) {
            address.startsWith("addr1")
        } else {
            address.startsWith("addr_test1")
        }

    private companion object {
        const val MAX_LEDGER_UTXOS = 10_000
        const val MAX_UNSUPPORTED_ASSETS = 10_000
        const val MAX_L1_OPERATIONS = 10_000
        const val MAX_L1_JOURNAL_BYTES = 1_048_576
        const val INITIAL_SWEEP_FEE = 500_000L
        const val TRANSFER_VALIDITY_SLOTS = 3_600L
        val UNRESOLVED_STATES = setOf(
            L1OperationState.PREPARED,
            L1OperationState.SUBMITTING,
            L1OperationState.PENDING,
        )
    }
}
