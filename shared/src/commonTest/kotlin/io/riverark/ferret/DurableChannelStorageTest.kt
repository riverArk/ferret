package io.riverark.ferret

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.ChannelConstants
import io.riverark.ferret.core.cardano.ChannelDatum
import io.riverark.ferret.core.cardano.ChannelDatumStage
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelPayload
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.PreparedChannelOperation
import io.riverark.ferret.core.channel.ProtocolTag
import io.riverark.ferret.core.channel.VaultChannelJournal
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletOperationJournalV2
import io.riverark.ferret.core.security.WalletSecretV1
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DurableChannelStorageTest {
    @Test fun persistsDeterministicallyAndPreservesL1Bytes() = runBlocking {
        val vault = FakeVault(Json.encodeToString(WalletOperationJournalV2(l1 = byteArrayOf(7, 8))).encodeToByteArray())
        val journal = VaultChannelJournal(vault, CATALOG)
        val first = entry("01")
        val second = entry("02")
        val collection = ChannelCollectionV4(
            walletId = WALLET,
            catalogDigest = DIGEST,
            channels = linkedMapOf(second.keytag.value to second, first.keytag.value to first),
            paidHashes = linkedSetOf("22".repeat(32), "11".repeat(32)),
        )

        journal.persist(WALLET, collection)
        val encoded = journal.backupSnapshot(WALLET)
        val reordered = journal.encodeBackup(collection.copy(
            channels = linkedMapOf(first.keytag.value to first, second.keytag.value to second),
            paidHashes = linkedSetOf("11".repeat(32), "22".repeat(32)),
        ))

        assertContentEquals(encoded, reordered)
        assertContentEquals(byteArrayOf(7, 8), Json.decodeFromString<WalletOperationJournalV2>(vault.state.operationJournal.decodeToString()).l1)
        encoded.fill(0)
        reordered.fill(0)
    }

    @Test fun malformedNewSchemaNeverFallsBackAndIdentitylessLegacyIsPreservedOpaque() {
        val journal = VaultChannelJournal(FakeVault(), CATALOG)
        assertFails { journal.decodeBackup(WALLET, "{\"schema\":3,\"state\":{}}".encodeToByteArray()) }

        val legacy = "{\"state\":{\"type\":\"Open\",\"channelId\":\"unknown\"},\"spendableBalance\":{\"value\":2500000}}".encodeToByteArray()
        val migrated = journal.decodeBackup(WALLET, legacy)
        assertTrue(migrated.channels.isEmpty())
        assertContentEquals(legacy, migrated.unresolvedLegacy)
        legacy.fill(0)

        val absent = Json.encodeToString<ChannelState>(ChannelState.Absent)
        val empty = journal.decodeBackup(
            WALLET,
            """{"state":$absent,"spendableBalance":0}""".encodeToByteArray(),
        )
        assertTrue(empty.channels.isEmpty())
        assertTrue(empty.unresolvedLegacy.isEmpty())
    }
    @Test fun migratesScalarLegacyChannelAndReceiptAmounts() {
        val keytag = "04".repeat(66)
        val paymentHash = "ab".repeat(32)
        val state = Json.encodeToString<ChannelState>(ChannelState.Open("legacy-channel"))
        val legacy =
            """{"schema":2,"channel":{"state":$state,"pending":null,"history":[],"spendableBalance":2500000,"verifiedChannelData":"$keytag"},"payment":{"pending":null,"receipts":[{"receipt":{"operationId":"legacy-payment","paymentHash":"$paymentHash","amount":1500000,"fee":1414,"verified":true},"completedAtEpochMillis":123}],"paidHashes":["$paymentHash"]}}"""

        val migrated = VaultChannelJournal(FakeVault(), CATALOG)
            .decodeBackup(WALLET, legacy.encodeToByteArray())
        val channel = migrated.channels.getValue(keytag)
        val receipt = channel.payments.receipts.single().receipt

        assertEquals(2_500_000, channel.spendableBalance.baseUnits)
        assertEquals(1_500_000, receipt.amount.baseUnits)
        assertEquals(1_414, receipt.fee.baseUnits)
        assertEquals(ADA, receipt.amount.asset)
    }

    @Test fun schemaThreePendingOpenMigratesToAdaAssetIntent() {
        val journal = VaultChannelJournal(FakeVault(), CATALOG)
        val tag = "01".repeat(32)
        val verificationKey = "02".repeat(32)
        val keytag = ProtocolKeytag.from(verificationKey, ProtocolTag(tag), 32)
        val amount = AssetAmount(ADA, 5_000_000)
        val datum = ChannelDatum(
            "03".repeat(28),
            ChannelConstants(tag, verificationKey, "04".repeat(32), 1_800_000, ADA),
            ChannelDatumStage.Opened(0),
        )
        val intent = CardanoIntent.OpenChannel(
            "addr1source",
            "addr1validator",
            LedgerUtxo("11".repeat(32), 0, "addr1reference", Lovelace(2_000_000)),
            datum,
            amount,
            "00000000-0000-4000-8000-000000000001",
            10,
            20,
        )
        val pending = PreparedChannelOperation(
            intent.operationId,
            "22".repeat(32),
            keytag,
            ADA,
            ChannelAction.Open(amount),
            preparedAtEpochMillis = 123,
            payload = ChannelPayload.Transaction(byteArrayOf(1), "33".repeat(32), intent = intent, feeBound = Lovelace(200_000)),
            resultingSpendableBalance = AssetAmount(ADA, 3_000_000),
            state = OperationState.PROPOSED,
        )
        val current = ChannelCollectionV4(
            walletId = WALLET,
            catalogDigest = DIGEST,
            channels = mapOf(keytag.value to ChannelSnapshot(
                keytag,
                ADA,
                ChannelState.Opening(intent.operationId),
                pending,
                spendableBalance = AssetAmount(ADA, 3_000_000),
            )),
        )
        val root = Json.parseToJsonElement(journal.encodeBackup(current).decodeToString()).jsonObject
        val channels = root.getValue("channels").jsonObject
        val entry = channels.getValue(keytag.value).jsonObject
        val operation = entry.getValue("pending").jsonObject
        val payload = operation.getValue("payload").jsonObject
        val oldIntent = payload.getValue("intent").jsonObject
        val oldDatum = oldIntent.getValue("datum").jsonObject
        val oldConstants = oldDatum.getValue("constants").jsonObject
        val schemaThreeIntent = JsonObject(oldIntent.toMutableMap().apply {
            put("amount", JsonObject(mapOf("value" to JsonPrimitive(amount.baseUnits))))
            put("datum", JsonObject(oldDatum.toMutableMap().apply {
                put("constants", JsonObject(oldConstants.toMutableMap().apply { remove("asset") }))
            }))
        })
        val schemaThree = JsonObject(root.toMutableMap().apply {
            put("schema", JsonPrimitive(3))
            put("channels", JsonObject(channels.toMutableMap().apply {
                put(keytag.value, JsonObject(entry.toMutableMap().apply {
                    put("pending", JsonObject(operation.toMutableMap().apply {
                        put("payload", JsonObject(payload.toMutableMap().apply {
                            put("intent", schemaThreeIntent)
                        }))
                    }))
                }))
            }))
        }).toString().encodeToByteArray()

        val migrated = journal.decodeBackup(WALLET, schemaThree)
        val migratedIntent = (migrated.channels.getValue(keytag.value).pending?.payload as ChannelPayload.Transaction)
            .intent as CardanoIntent.OpenChannel

        assertEquals(4, migrated.schema)
        assertEquals(amount, migratedIntent.amount)
        assertEquals(ADA, migratedIntent.datum.constants.asset)
    }

    @Test fun nativeAddRoundTripsAndRejectsMismatchedProjectedCapacity() {
        val journal = VaultChannelJournal(FakeVault(), CATALOG)
        val usdm = requireNotNull(CATALOG.asset("usdm"))
        val tag = "01".repeat(32)
        val verificationKey = "02".repeat(32)
        val keytag = ProtocolKeytag.from(verificationKey, ProtocolTag(tag), 32)
        val datum = ChannelDatum(
            "03".repeat(28),
            ChannelConstants(tag, verificationKey, "04".repeat(32), 1_800_000, usdm),
            ChannelDatumStage.Opened(0),
        )
        val amount = AssetAmount(usdm, 25_000)
        val intent = CardanoIntent.AddChannelFunds(
            "addr1source",
            LedgerUtxo(
                "11".repeat(32),
                0,
                "addr1validator",
                Lovelace(3_000_000),
                mapOf(usdm.connectorUnit to 100_000),
                datumHex = "d87980",
            ),
            LedgerUtxo("22".repeat(32), 0, "addr1reference", Lovelace(2_000_000)),
            datum,
            datum,
            amount,
            "00000000-0000-4000-8000-000000000001",
            10,
            20,
        )
        val operation = PreparedChannelOperation(
            intent.operationId,
            "33".repeat(32),
            keytag,
            usdm,
            ChannelAction.Add(amount),
            priorChannelIdentity = "opening-transaction",
            preparedAtEpochMillis = 123,
            payload = ChannelPayload.Transaction(
                byteArrayOf(1),
                "44".repeat(32),
                intent = intent,
                feeBound = Lovelace(200_000),
            ),
            resultingSpendableBalance = AssetAmount(usdm, 105_000),
        )
        val entry = ChannelSnapshot(
            keytag,
            usdm,
            ChannelState.Open("opening-transaction"),
            pending = operation,
            spendableBalance = AssetAmount(usdm, 80_000),
        )
        val collection = ChannelCollectionV4(
            walletId = WALLET,
            catalogDigest = DIGEST,
            channels = mapOf(keytag.value to entry),
        )

        val encoded = journal.encodeBackup(collection)
        val decoded = journal.decodeBackup(WALLET, encoded).channels.getValue(keytag.value)
        assertEquals(entry.copy(pending = null), decoded.copy(pending = null))
        assertEquals(operation, decoded.pending!!.copy(payload = operation.payload))
        assertContentEquals(
            (operation.payload as ChannelPayload.Transaction).unsignedBody,
            (decoded.pending.payload as ChannelPayload.Transaction).unsignedBody,
        )
        assertFails {
            journal.encodeBackup(collection.copy(channels = mapOf(
                keytag.value to entry.copy(pending = operation.copy(
                    resultingSpendableBalance = AssetAmount(usdm, 105_001),
                )),
            )))
        }
        encoded.fill(0)
    }

    @Test fun guardedCleanupRemovesOnlyFailedEmptyAttemptsAndPreservesOpenChannel() {
        val journal = VaultChannelJournal(FakeVault(), CATALOG)
        val openKey = "04".repeat(66)
        val failedKey = "05".repeat(66)
        val openState = Json.encodeToString<ChannelState>(ChannelState.Open("open-channel"))
        val absentState = Json.encodeToString<ChannelState>(ChannelState.Absent)
        val keyedFailure =
            """{"operationId":"failed-keyed","intentHash":"${"11".repeat(32)}","transactionId":"${"33".repeat(32)}","state":$absentState,"status":"FAILED","verifiedChannelData":"$failedKey"}"""
        val unidentifiedFailure =
            """{"operationId":"failed-unidentified","intentHash":"${"22".repeat(32)}","transactionId":"${"44".repeat(32)}","state":$absentState,"status":"FAILED"}"""
        val unidentifiedSuccess =
            """{"operationId":"open-success","intentHash":"${"55".repeat(32)}","transactionId":"open-channel","state":$openState,"status":"COMPLETED"}"""
        val legacy =
            """{"schema":2,"channel":{"state":$openState,"pending":null,"history":[$keyedFailure,$unidentifiedFailure,$unidentifiedSuccess],"spendableBalance":463953,"verifiedChannelData":"$openKey"},"payment":{}}"""
        val migrated = journal.decodeBackup(WALLET, legacy.encodeToByteArray())

        val cleaned = journal.cleanupInactive(migrated)

        assertEquals(setOf(openKey), cleaned.channels.keys)
        assertEquals(463_953, cleaned.channels.getValue(openKey).spendableBalance.baseUnits)
        assertEquals("open-success", cleaned.channels.getValue(openKey).history.single().operationId)
        assertTrue(cleaned.unresolvedLegacy.isEmpty())
        assertFails {
            journal.cleanupInactive(
                journal.decodeBackup(WALLET, legacy.replace("\"status\":\"FAILED\"", "\"status\":\"COMPLETED\"").encodeToByteArray()),
            )
        }
    }


    private fun entry(byte: String): ChannelSnapshot {
        val keytag = ProtocolKeytag(byte.repeat(66))
        return ChannelSnapshot(keytag, ADA, ChannelState.Open("channel-$byte"))
    }

    private class FakeVault(journal: ByteArray = byteArrayOf()) : SecureVault {
        var state = WalletEncryptedStateV1(operationJournal = journal)
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = listOf(WalletProfile(WALLET, "Wallet", CardanoNetwork.MAINNET, "addr1", "stake1"))
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T = error("not used")
        override suspend fun walletState(walletId: WalletId) = state.copy(
            channelRecovery = state.channelRecovery.copyOf(), operationJournal = state.operationJournal.copyOf(),
        )
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) {
            this.state = state.copy(
                channelRecovery = state.channelRecovery.copyOf(), operationJournal = state.operationJournal.copyOf(),
            )
        }
    }

    private companion object {
        const val DIGEST = "09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae"
        val WALLET = WalletId("mainnet-" + "00".repeat(28))
        val ADA = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, DIGEST)
        val CATALOG = AssetCatalog(
            listOf(
                ADA,
                ChannelAsset("usda", "01".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST),
                ChannelAsset("usdcx", "02".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST),
                ChannelAsset("usdm", "03".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST),
            ),
            DIGEST,
            emptyMap(),
        )
    }
}
