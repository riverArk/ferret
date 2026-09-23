package io.riverark.ferret.core.channel

import io.riverark.ferret.core.backup.BackupCheckpointV1
import io.riverark.ferret.core.backup.WalletBackupCoordinator
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletOperationJournalV2
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ChannelCollectionV4(
    val schema: Int = 4,
    val walletId: WalletId,
    val catalogDigest: String,
    val channels: Map<String, ChannelSnapshot> = emptyMap(),
    val paidHashes: Set<String> = emptySet(),
    val unresolvedLegacy: ByteArray = byteArrayOf(),
) {
    init {
        require(schema == 4)
        require(SHA256.matches(catalogDigest))
        require(channels.size <= MAX_CHANNELS)
        require(channels.all { (key, entry) -> key == entry.keytag.value && entry.asset.catalogDigest == catalogDigest })
        require(paidHashes.size <= MAX_FINANCIAL_RECORDS && paidHashes.all(SHA256::matches))
        require(unresolvedLegacy.size <= MAX_JOURNAL_BYTES)
    }
}

private val SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_CHANNELS = 10_000
private const val MAX_FINANCIAL_RECORDS = 10_000
private const val MAX_JOURNAL_BYTES = 1_048_576

@Serializable
private data class WalletOperationJournalV1(
    val schema: Int = 1,
    val l1: ByteArray = byteArrayOf(),
    val channel: ByteArray = byteArrayOf(),
    val payment: ByteArray = byteArrayOf(),
) {
    init { require(schema == 1) }
}

@Serializable
private data class LegacyChannelRecoveryV2(
    val schema: Int = 2,
    val channel: JsonElement,
    val payment: JsonElement = JsonObject(emptyMap()),
)

class VaultChannelJournal(
    private val vault: SecureVault,
    private val catalog: AssetCatalog,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) : ChannelJournal {
    override suspend fun load(walletId: WalletId): ChannelCollectionV4 {
        val state = vault.walletState(walletId)
        if (state.operationJournal.isEmpty()) {
            state.channelRecovery.fill(0)
            return empty(walletId)
        }
        return try {
            val root = json.parseToJsonElement(state.operationJournal.decodeToString()).jsonObject
            val schema = root["schema"]?.jsonPrimitive?.intOrNull
            when {
                schema == 2 || schema == null && "channels" in root -> {
                    val envelope = json.decodeFromString<WalletOperationJournalV2>(state.operationJournal.decodeToString())
                    try {
                        if (envelope.channels.isEmpty()) empty(walletId) else decode(walletId, envelope.channels)
                    } finally {
                        envelope.l1.fill(0)
                        envelope.channels.fill(0)
                    }
                }
                schema == 1 || schema == null && root.keys.all { it in setOf("l1", "channel", "payment") } -> {
                    val migrated = migrateEnvelope(
                        walletId,
                        json.decodeFromString(state.operationJournal.decodeToString()),
                        state.operationJournal,
                    )
                    persist(walletId, migrated)
                    migrated
                }
                else -> error("unsupported wallet operation journal schema")
            }
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
        }
    }

    override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) {
        val normalized = validate(walletId, collection)
        val state = vault.walletState(walletId)
        val envelope = decodeEnvelope(state.operationJournal)
        val channels = encode(normalized)
        val encoded = json.encodeToString(envelope.copy(channels = channels)).encodeToByteArray()
        require(encoded.size <= MAX_JOURNAL_BYTES) { "Channel history storage is full." }
        try {
            vault.updateWalletState(walletId, state.copy(operationJournal = encoded))
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
            envelope.l1.fill(0)
            envelope.channels.fill(0)
            channels.fill(0)
            encoded.fill(0)
        }
    }

    suspend fun backupSnapshot(walletId: WalletId): ByteArray = encode(load(walletId))

    fun decodeBackup(walletId: WalletId, bytes: ByteArray): ChannelCollectionV4 = decode(walletId, bytes)

    fun normalizeBackup(walletId: WalletId, bytes: ByteArray): ByteArray = encode(decode(walletId, bytes))
    fun encodeBackup(collection: ChannelCollectionV4): ByteArray = encode(collection)

    override fun cleanupInactive(collection: ChannelCollectionV4): ChannelCollectionV4 {
        val current = validate(collection.walletId, collection)
        require(current.unresolvedLegacy.isNotEmpty()) { "No legacy channel cleanup is needed." }
        val (snapshot, payment) = legacySource(current.unresolvedLegacy)
        val history = snapshot.arrayObjects("history")
        val currentEvidence = buildSet {
            snapshot.string("verifiedChannelData")?.takeIf(::validKeytag)?.let(::add)
            snapshot.obj("pending")?.let { pending ->
                pending.string("keytag")?.takeIf(::validKeytag)?.let(::add)
                pending.openIntentKeytag()?.let(::add)
            }
        }
        val groupedKeys = history.mapNotNull { it.string("verifiedChannelData")?.takeIf(::validKeytag) }.toSet()
        require(currentEvidence.size == 1) {
            "Cleanup refused because the active channel identity is ambiguous."
        }
        require(groupedKeys.all(current.channels::containsKey)) {
            "Cleanup refused because identified channel history is incomplete."
        }
        val unmatched = history.filter { it.string("verifiedChannelData")?.takeIf(::validKeytag) == null }
        require(payment?.jsonObject?.obj("pending") == null) {
            "Cleanup refused because a legacy payment is still pending."
        }
        val retained = current.channels.filterValues { !discardableEntry(it) }.toMutableMap()
        var changed = retained.size < current.channels.size
        unmatched.forEach { result ->
            if (discardableLegacyResult(result)) {
                changed = true
                return@forEach
            }
            val resultState = json.decodeFromJsonElement(
                io.riverark.ferret.core.model.ChannelState.serializer(),
                result.getValue("state"),
            )
            val openingReference = (resultState as? io.riverark.ferret.core.model.ChannelState.Open)?.channelId
            val target = current.channels.values.singleOrNull {
                (it.state as? io.riverark.ferret.core.model.ChannelState.Open)?.channelId == openingReference
            }
            require(
                openingReference != null &&
                    result.string("status") in setOf(OperationState.COMPLETED.name, OperationState.FAILED.name) &&
                    target != null,
            ) { "Cleanup refused because an unmatched record cannot be bound to the open channel." }
            val converted = json.decodeFromJsonElement(
                ChannelRemoteResult.serializer(),
                convertLegacyResult(result, target.keytag),
            )
            val retainedTarget = requireNotNull(retained[target.keytag.value])
            require(retainedTarget.history.none { it.operationId == converted.operationId })
            retained[target.keytag.value] = retainedTarget.copy(history = retainedTarget.history + converted)
            changed = true
        }
        require(changed) { "No safely removable channel attempts were found." }
        return validate(current.walletId, current.copy(channels = retained, unresolvedLegacy = byteArrayOf()))
    }

    suspend fun installBackup(walletId: WalletId, bytes: ByteArray, checkpoint: BackupCheckpointV1): ChannelCollectionV4 {
        val collection = decode(walletId, bytes)
        val state = vault.walletState(walletId)
        val envelope = decodeEnvelope(state.operationJournal)
        val channels = encode(collection)
        val checkpointBytes = json.encodeToString(checkpoint).encodeToByteArray()
        val journalBytes = json.encodeToString(envelope.copy(channels = channels)).encodeToByteArray()
        require(checkpointBytes.size <= MAX_JOURNAL_BYTES && journalBytes.size <= MAX_JOURNAL_BYTES)
        try {
            vault.updateWalletState(walletId, state.copy(
                channelRecovery = checkpointBytes,
                operationJournal = journalBytes,
                backupGeneration = checkpoint.generation,
            ))
            return collection
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
            envelope.l1.fill(0)
            envelope.channels.fill(0)
            channels.fill(0)
            checkpointBytes.fill(0)
            journalBytes.fill(0)
        }
    }

    private fun decode(walletId: WalletId, bytes: ByteArray): ChannelCollectionV4 {
        require(bytes.size in 1..MAX_JOURNAL_BYTES)
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val schema = root["schema"]?.jsonPrimitive?.intOrNull
        return when {
            schema == 4 || schema == null && ("walletId" in root || "channels" in root) ->
                validate(walletId, json.decodeFromString(bytes.decodeToString()))
            schema == 3 -> validate(
                walletId,
                json.decodeFromJsonElement(ChannelCollectionV4.serializer(), convertCollectionV3(root)),
            )
            schema == 2 || schema == null && "channel" in root ->
                migrateRecovery(walletId, json.decodeFromString(bytes.decodeToString()), bytes)
            schema == null && "state" in root -> migrateSnapshot(walletId, root, null, bytes)
            else -> error("unsupported channel backup schema")
        }
    }
    private fun convertCollectionV3(root: JsonObject): JsonObject {
        require(root["schema"]?.jsonPrimitive?.intOrNull == 3)
        val walletId = json.decodeFromJsonElement(WalletId.serializer(), root.getValue("walletId"))
        require(walletId.value.isNotBlank())
        require(root.getValue("catalogDigest").jsonPrimitive.content == catalog.digest)
        val channels = root.getValue("channels").jsonObject
        val converted = channels.mapValues { (key, rawEntry) ->
            val entry = rawEntry.jsonObject
            val entryAsset = catalog.requireAsset(
                json.decodeFromJsonElement(ChannelAsset.serializer(), entry.getValue("asset")),
            )
            require(key == json.decodeFromJsonElement(ProtocolKeytag.serializer(), entry.getValue("keytag")).value)
            val pending = entry["pending"]
            if (pending == null || pending is kotlinx.serialization.json.JsonNull) return@mapValues rawEntry
            val operation = pending.jsonObject
            val operationAsset = catalog.requireAsset(
                json.decodeFromJsonElement(ChannelAsset.serializer(), operation.getValue("asset")),
            )
            require(operationAsset == entryAsset)
            val payload = operation.getValue("payload").jsonObject
            val intentElement = payload["intent"]
            if (intentElement == null || intentElement is kotlinx.serialization.json.JsonNull) return@mapValues rawEntry
            val intent = intentElement.jsonObject
            val ada = catalog.ada
            require(entryAsset == ada)
            val action = operation.getValue("action").jsonObject
            operation["resultingSpendableBalance"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.let {
                require(json.decodeFromJsonElement(AssetAmount.serializer(), it).asset == ada)
            }

            fun constants(value: JsonElement): JsonObject {
                val datum = value.jsonObject
                val constants = datum.getValue("constants").jsonObject
                require("asset" !in constants)
                return JsonObject(datum.toMutableMap().apply {
                    put("constants", JsonObject(constants.toMutableMap().apply {
                        put("asset", json.encodeToJsonElement(ChannelAsset.serializer(), ada))
                    }))
                })
            }

            val convertedIntent = when {
                "validatorAddress" in intent -> {
                    val actionAmount = json.decodeFromJsonElement(
                        AssetAmount.serializer(),
                        action.getValue("amount"),
                    )
                    require(actionAmount.asset == ada)
                    val units = when (val oldAmount = intent.getValue("amount")) {
                        is JsonObject -> {
                            require(oldAmount.keys == setOf("value"))
                            oldAmount.getValue("value").jsonPrimitive.content.toLong()
                        }
                        else -> error("invalid schema-3 open amount")
                    }
                    require(units == actionAmount.baseUnits)
                    JsonObject(intent.toMutableMap().apply {
                        put("amount", json.encodeToJsonElement(AssetAmount.serializer(), actionAmount))
                        put("datum", constants(intent.getValue("datum")))
                    })
                }
                "currentDatum" in intent -> {
                    action["amount"]?.let {
                        require(json.decodeFromJsonElement(AssetAmount.serializer(), it).asset == ada)
                    }
                    JsonObject(intent.toMutableMap().apply {
                        put("currentDatum", constants(intent.getValue("currentDatum")))
                        intent["resultingDatum"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.let {
                            put("resultingDatum", constants(it))
                        }
                    })
                }
                else -> error("unsupported schema-3 channel intent")
            }
            JsonObject(entry.toMutableMap().apply {
                put("pending", JsonObject(operation.toMutableMap().apply {
                    put("payload", JsonObject(payload.toMutableMap().apply {
                        put("intent", convertedIntent)
                    }))
                }))
            })
        }
        return JsonObject(root.toMutableMap().apply {
            put("schema", JsonPrimitive(4))
            put("channels", JsonObject(converted))
        })
    }

    private fun decodeEnvelope(bytes: ByteArray): WalletOperationJournalV2 {
        if (bytes.isEmpty()) return WalletOperationJournalV2()
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val schema = root["schema"]?.jsonPrimitive?.intOrNull
        return when {
            schema == 2 || schema == null && "channels" in root ->
                json.decodeFromString<WalletOperationJournalV2>(bytes.decodeToString()).also { require(it.schema == 2) }
            schema == 1 || schema == null && root.keys.all { it in setOf("l1", "channel", "payment") } ->
                json.decodeFromString<WalletOperationJournalV1>(bytes.decodeToString()).let {
                    try { WalletOperationJournalV2(l1 = it.l1.copyOf()) }
                    finally { it.l1.fill(0); it.channel.fill(0); it.payment.fill(0) }
                }
            else -> error("unsupported wallet operation journal schema")
        }
    }

    private fun migrateEnvelope(
        walletId: WalletId,
        old: WalletOperationJournalV1,
        source: ByteArray,
    ): ChannelCollectionV4 = try {
        if (old.channel.isEmpty()) {
            if (legacyPaymentEmpty(old.payment)) empty(walletId) else empty(walletId).copy(unresolvedLegacy = source.copyOf())
        } else {
            val payment = old.payment.takeIf(ByteArray::isNotEmpty)?.let { json.parseToJsonElement(it.decodeToString()) }
            migrateSnapshot(walletId, json.parseToJsonElement(old.channel.decodeToString()).jsonObject, payment, source)
        }
    } finally {
        old.l1.fill(0)
        old.channel.fill(0)
        old.payment.fill(0)
    }

    private fun migrateRecovery(walletId: WalletId, old: LegacyChannelRecoveryV2, source: ByteArray): ChannelCollectionV4 {
        require(old.schema == 2)
        return migrateSnapshot(walletId, old.channel.jsonObject, old.payment, source)
    }

    private fun legacySource(source: ByteArray): Pair<JsonObject, JsonElement?> {
        val root = json.parseToJsonElement(source.decodeToString()).jsonObject
        val schema = root["schema"]?.jsonPrimitive?.intOrNull
        if (schema == 1 || schema == null && root["channel"] is kotlinx.serialization.json.JsonArray) {
            val envelope = json.decodeFromString<WalletOperationJournalV1>(source.decodeToString())
            return try {
                require(envelope.channel.isNotEmpty())
                json.parseToJsonElement(envelope.channel.decodeToString()).jsonObject to
                    envelope.payment.takeIf(ByteArray::isNotEmpty)?.let {
                        json.parseToJsonElement(it.decodeToString())
                    }
            } finally {
                envelope.l1.fill(0)
                envelope.channel.fill(0)
                envelope.payment.fill(0)
            }
        }
        if (schema == 2 && root["channel"] is JsonObject) {
            return root.getValue("channel").jsonObject to root["payment"]
        }
        require(schema == null && "state" in root)
        return root to null
    }

    private fun discardableLegacyResult(result: JsonObject): Boolean {
        val stateType = result.obj("state")?.string("type")
        return result.string("status") == OperationState.FAILED.name &&
            (stateType?.endsWith(".Absent") == true || stateType?.endsWith("\$Absent") == true)
    }

    private fun discardableEntry(entry: ChannelSnapshot) =
        entry.state == io.riverark.ferret.core.model.ChannelState.Absent &&
            entry.pending == null &&
            entry.protocolReceipt == null &&
            entry.spendableBalance.baseUnits == 0L &&
            entry.payments.pending == null &&
            entry.payments.receipts.isEmpty() &&
            entry.history.all {
                it.status == OperationState.FAILED &&
                    it.state == io.riverark.ferret.core.model.ChannelState.Absent &&
                    it.protocolReceipt == null
            }

    private fun migrateSnapshot(
        walletId: WalletId,
        snapshot: JsonObject,
        payment: JsonElement?,
        source: ByteArray,
    ): ChannelCollectionV4 {
        if (isEmptyLegacy(snapshot, payment)) return empty(walletId)
        val currentEvidence = buildSet {
            snapshot.string("verifiedChannelData")?.takeIf(::validKeytag)?.let(::add)
            snapshot.obj("pending")?.let { pending ->
                pending.string("keytag")?.takeIf(::validKeytag)?.let(::add)
                pending.openIntentKeytag()?.let(::add)
            }
        }
        require(currentEvidence.size <= 1) { "contradictory legacy channel identity" }
        val history = snapshot.arrayObjects("history")
        val groupedHistory = history.mapNotNull { result ->
            result.string("verifiedChannelData")?.takeIf(::validKeytag)?.let { it to result }
        }.groupBy({ it.first }, { it.second })
        val currentKey = currentEvidence.singleOrNull() ?: groupedHistory.keys.singleOrNull()
        val unresolved = currentKey == null || history.size != groupedHistory.values.sumOf { it.size }
        val entries = mutableMapOf<String, ChannelSnapshot>()
        (groupedHistory.keys + listOfNotNull(currentKey)).forEach { key ->
            val keytag = ProtocolKeytag(key)
            val records = groupedHistory[key].orEmpty()
            val isCurrent = key == currentKey
            val sourceSnapshot = if (isCurrent) {
                JsonObject(snapshot.toMutableMap().apply {
                    put("history", kotlinx.serialization.json.JsonArray(records))
                })
            } else {
                val terminal = records.lastOrNull()?.takeIf {
                    it.string("status") in setOf(OperationState.COMPLETED.name, OperationState.FAILED.name)
                } ?: return@forEach
                JsonObject(snapshot.toMutableMap().apply {
                    put("state", terminal.getValue("state"))
                    put("pending", kotlinx.serialization.json.JsonNull)
                    put("verifiedChannelData", JsonPrimitive(key))
                    put("history", kotlinx.serialization.json.JsonArray(records))
                    put("spendableBalance", JsonPrimitive(0))
                })
            }
            val converted = convertSnapshot(sourceSnapshot, keytag, payment.takeIf { isCurrent })
            entries[key] = json.decodeFromJsonElement(ChannelSnapshot.serializer(), converted)
        }
        if (entries.isEmpty()) return empty(walletId).copy(
            paidHashes = payment?.jsonObject?.arrayStrings("paidHashes")?.toSet().orEmpty(),
            unresolvedLegacy = source.copyOf(),
        )
        return validate(walletId, ChannelCollectionV4(
            walletId = walletId,
            catalogDigest = catalog.digest,
            channels = entries,
            paidHashes = payment?.jsonObject?.arrayStrings("paidHashes")?.toSet().orEmpty(),
            unresolvedLegacy = if (unresolved || entries.size < groupedHistory.size) source.copyOf() else byteArrayOf(),
        ))
    }

    private fun convertSnapshot(snapshot: JsonObject, keytag: ProtocolKeytag, payment: JsonElement?): JsonObject {
        val asset = json.encodeToJsonElement(ChannelAsset.serializer(), catalog.ada)
        fun legacyUnits(value: JsonElement?): Long = when (value) {
            null, kotlinx.serialization.json.JsonNull -> 0
            is JsonPrimitive -> value.content.toLong()
            is JsonObject -> {
                require(value.keys == setOf("value"))
                value.getValue("value").jsonPrimitive.content.toLong()
            }
            else -> error("invalid legacy asset amount")
        }
        fun amount(value: JsonElement?): JsonElement = json.encodeToJsonElement(
            AssetAmount.serializer(), AssetAmount(catalog.ada, legacyUnits(value)),
        )
        fun quote(value: JsonObject): JsonObject = JsonObject(value.toMutableMap().apply {
            put("keytag", JsonPrimitive(keytag.value))
            put("amount", amount(value["amount"]))
            put("routingFee", amount(value["routingFee"]))
            put("adaptorFee", amount(value["adaptorFee"]))
            put("bindingVersion", JsonPrimitive(1))
        })
        fun operation(value: JsonObject): JsonObject {
            val action = value.obj("action")
            val payload = value.obj("payload")
            return JsonObject(value.toMutableMap().apply {
                put("keytag", JsonPrimitive(keytag.value)); put("asset", asset)
                action?.get("amount")?.let { amountValue ->
                    put("action", JsonObject(action.toMutableMap().apply {
                        put("amount", amount(amountValue))
                    }))
                }
                value["resultingSpendableBalance"]?.let { put("resultingSpendableBalance", amount(it)) }
                payload?.obj("quote")?.let { legacyQuote ->
                    put("payload", JsonObject(payload.toMutableMap().apply { put("quote", quote(legacyQuote)) }))
                }
            })
        }
        fun pendingPayment(value: JsonObject): JsonObject = JsonObject(value.toMutableMap().apply { put("quote", quote(value.getValue("quote").jsonObject)) })
        fun receipt(value: JsonObject): JsonObject = JsonObject(value.toMutableMap().apply {
            val oldReceipt = value.getValue("receipt").jsonObject
            put("receipt", JsonObject(oldReceipt.toMutableMap().apply {
                put("keytag", JsonPrimitive(keytag.value)); put("amount", amount(oldReceipt["amount"])); put("fee", amount(oldReceipt["fee"]))
            }))
        })
        val legacyPayment = payment?.jsonObject
        val payments = JsonObject(mapOf(
            "pending" to (legacyPayment?.obj("pending")?.let(::pendingPayment) ?: kotlinx.serialization.json.JsonNull),
            "receipts" to kotlinx.serialization.json.JsonArray(legacyPayment?.arrayObjects("receipts")?.map(::receipt).orEmpty()),
        ))
        return JsonObject(snapshot.toMutableMap().apply {
            remove("verifiedChannelData")
            put("keytag", JsonPrimitive(keytag.value)); put("asset", asset)
            snapshot.obj("pending")?.let { put("pending", operation(it)) }
            put("history", kotlinx.serialization.json.JsonArray(snapshot.arrayObjects("history").map {
                convertLegacyResult(it, keytag)
            }))
            put("spendableBalance", amount(snapshot["spendableBalance"]))
            put("payments", payments)
        })
    }

    private fun convertLegacyResult(value: JsonObject, keytag: ProtocolKeytag) =
        JsonObject(value.toMutableMap().apply {
            remove("verifiedChannelData")
            put("keytag", JsonPrimitive(keytag.value))
            put("asset", json.encodeToJsonElement(ChannelAsset.serializer(), catalog.ada))
        })

    private fun validate(walletId: WalletId, value: ChannelCollectionV4): ChannelCollectionV4 {
        require(value.walletId == walletId && value.catalogDigest == catalog.digest)
        val ids = mutableMapOf<String, Pair<ProtocolKeytag, String>>()
        fun record(operationId: String, keytag: ProtocolKeytag, intentHash: String) {
            val identity = keytag to intentHash
            require(ids[operationId].let { it == null || it == identity })
            ids[operationId] = identity
        }
        val receiptIds = mutableSetOf<String>()
        var receipts = 0
        value.channels.forEach { (key, entry) ->
            require(key == entry.keytag.value)
            catalog.requireAsset(entry.asset)
            require(entry.spendableBalance.asset == entry.asset)
            entry.pending?.let { operation -> validateOperation(entry, operation); record(operation.operationId, entry.keytag, operation.intentHash) }
            entry.history.forEach { result ->
                require(result.keytag == entry.keytag && result.asset == entry.asset)
                record(result.operationId, entry.keytag, result.intentHash)
            }
            entry.payments.pending?.let { pending ->
                val operation = requireNotNull(entry.pending).also { require(it.operationId == pending.operationId) }
                val payload = operation.payload as? ChannelPayload.Payment
                    ?: error("pending payment operation payload is unavailable")
                require(payload.quote == pending.quote && payload.invoiceHash == pending.paymentHash)
                require(pending.quote.keytag == entry.keytag && pending.quote.amount.asset == entry.asset)
                require(pending.paymentHash == pending.quote.invoiceHash && pending.createdAtEpochMillis >= 0)
            }
            entry.payments.receipts.forEach { stored ->
                require(stored.completedAtEpochMillis >= 0 && stored.receipt.keytag == entry.keytag)
                require(stored.receipt.amount.asset == entry.asset && stored.receipt.fee.asset == entry.asset)
                require(SHA256.matches(stored.receipt.paymentHash))
                require(receiptIds.add(stored.receipt.operationId))
                ids[stored.receipt.operationId]?.let { require(it.first == entry.keytag) }
                if (stored.receipt.verified) require(stored.receipt.paymentHash in value.paidHashes)
            }
            receipts += entry.payments.receipts.size
            require(receipts <= MAX_FINANCIAL_RECORDS)
        }
        require(value.paidHashes.all(SHA256::matches))
        return value.copy(
            channels = value.channels.entries.sortedBy { it.key }.associate { it.toPair() },
            paidHashes = value.paidHashes.sorted().toSet(),
        )
    }

    private fun validateOperation(entry: ChannelSnapshot, operation: PreparedChannelOperation) {
        require(operation.keytag == entry.keytag && operation.asset == entry.asset)
        when (val action = operation.action) {
            is ChannelAction.Open -> require(action.amount.asset == entry.asset)
            is ChannelAction.Add -> require(action.amount.asset == entry.asset)
            else -> Unit
        }
        operation.resultingSpendableBalance?.let { require(it.asset == entry.asset) }
        (operation.payload as? ChannelPayload.Payment)?.let {
            require(it.quote.keytag == entry.keytag && it.quote.amount.asset == entry.asset)
            require(it.invoiceHash == it.quote.invoiceHash)
        }
        (operation.payload as? ChannelPayload.Transaction)?.let { payload ->
            when (val intent = payload.intent) {
                is io.riverark.ferret.core.cardano.CardanoIntent.OpenChannel -> {
                    val action = operation.action as? ChannelAction.Open ?: error("invalid open transaction")
                    require(intent.amount == action.amount)
                    require(intent.amount.asset == operation.asset && operation.asset == entry.asset)
                    require(intent.datum.constants.asset == entry.asset)
                    require(operation.keytag == ProtocolKeytag.from(
                        intent.datum.constants.addVerificationKeyHex,
                        ProtocolTag(intent.datum.constants.tagHex),
                        32,
                    ))
                    require(operation.resultingSpendableBalance?.asset == entry.asset)
                }
                is io.riverark.ferret.core.cardano.CardanoIntent.AddChannelFunds -> {
                    val action = operation.action as? ChannelAction.Add ?: error("invalid add transaction")
                    require(intent.amount == action.amount && intent.amount.baseUnits > 0)
                    require(intent.amount.asset == operation.asset && operation.asset == entry.asset)
                    require(intent.currentDatum == intent.resultingDatum)
                    require(intent.currentDatum.constants.asset == entry.asset)
                    require(intent.currentDatum.stage is io.riverark.ferret.core.cardano.ChannelDatumStage.Opened)
                    require(intent.channelInput.datumHex != null)
                    require(intent.channelInput.scriptRefHex == null && intent.channelInput.scriptRefHashHex == null)
                    require(operation.keytag == ProtocolKeytag.from(
                        intent.currentDatum.constants.addVerificationKeyHex,
                        ProtocolTag(intent.currentDatum.constants.tagHex),
                        32,
                    ))
                    require(operation.priorChannelIdentity == (entry.state as? io.riverark.ferret.core.model.ChannelState.Open)?.channelId)
                    require(operation.resultingSpendableBalance == entry.spendableBalance + action.amount)
                }
                else -> error("unsupported channel transaction intent")
            }
        }
    }

    private fun encode(value: ChannelCollectionV4): ByteArray {
        val bytes = json.encodeToString(validate(value.walletId, value)).encodeToByteArray()
        require(bytes.size <= MAX_JOURNAL_BYTES) { "Channel history storage is full." }
        return bytes
    }

    private fun empty(walletId: WalletId) = ChannelCollectionV4(walletId = walletId, catalogDigest = catalog.digest)
    private fun validKeytag(value: String) = runCatching { ProtocolKeytag(value) }.isSuccess
    private fun isEmptyLegacy(snapshot: JsonObject, payment: JsonElement?): Boolean {
        val state = snapshot.obj("state")
        val absent = state?.string("type")?.endsWith(".Absent") == true ||
            state?.string("type")?.endsWith("\$Absent") == true
        val balance = when (val value = snapshot["spendableBalance"]) {
            null, kotlinx.serialization.json.JsonNull -> 0L
            is JsonPrimitive -> value.content.toLong()
            is JsonObject -> {
                require(value.keys == setOf("value"))
                value.getValue("value").jsonPrimitive.content.toLong()
            }
            else -> error("invalid legacy asset amount")
        }
        return absent && snapshot.obj("pending") == null && snapshot.arrayObjects("history").isEmpty() &&
            balance == 0L &&
            payment?.jsonObject?.let { it.obj("pending") == null && it.arrayObjects("receipts").isEmpty() && it.arrayStrings("paidHashes").isEmpty() } != false
    }

    private fun legacyPaymentEmpty(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val value = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        return value.obj("pending") == null && value.arrayObjects("receipts").isEmpty() &&
            value.arrayStrings("paidHashes").isEmpty()
    }
}

class DriveChannelBackupProtocol(
    private val backups: WalletBackupCoordinator,
    private val requireWriter: suspend (WalletId, BackupCheckpointV1) -> WriterLease,
    private val journal: VaultChannelJournal,
) : ChannelBackupProtocol {
    override suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease {
        val checkpoint = backups.verify(walletId)
        return try { requireWriter(walletId, checkpoint) } finally { checkpoint.clear() }
    }

    override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) = write(walletId, collection)
    override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) = write(walletId, collection)

    private suspend fun write(walletId: WalletId, collection: ChannelCollectionV4) {
        val bytes = journal.encodeBackup(collection)
        try { backups.writeNext(walletId, bytes) } finally { bytes.fill(0) }
    }

    private fun BackupCheckpointV1.clear() {
        ciphertextHash.fill(0); channelSnapshot.fill(0); previousHash.fill(0); snapshotDigest.fill(0)
    }
}

private fun JsonObject.string(name: String) = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.obj(name: String) = get(name)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonObject
private fun JsonObject.arrayObjects(name: String) = (get(name) as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject }.orEmpty()
private fun JsonObject.arrayStrings(name: String) = (get(name) as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

private fun JsonObject.openIntentKeytag(): String? {
    val constants = obj("payload")?.obj("intent")?.obj("datum")?.obj("constants") ?: return null
    val verificationKey = constants.string("addVerificationKeyHex") ?: return null
    val tag = constants.string("tagHex") ?: return null
    return (verificationKey + tag).takeIf { runCatching { ProtocolKeytag(it) }.isSuccess }
}
