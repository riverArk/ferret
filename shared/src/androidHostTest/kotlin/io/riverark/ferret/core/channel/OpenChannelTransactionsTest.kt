package io.riverark.ferret.core.channel

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.util.HexUtil
import io.riverark.ferret.core.cardano.AndroidCardanoTransactionEngine
import io.riverark.ferret.core.cardano.ChannelConstants
import io.riverark.ferret.core.cardano.ChannelDatum
import io.riverark.ferret.core.cardano.ChannelDatumStage
import io.riverark.ferret.core.cardano.InsufficientFundsException
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.TransactionDatum
import io.riverark.ferret.core.cardano.plutus
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.AdaptorChannelParametersDto
import io.riverark.ferret.core.network.AdaptorClosePeriodDto
import io.riverark.ferret.core.network.AdaptorInfoDto
import io.riverark.ferret.core.network.AdaptorTermsDto
import io.riverark.ferret.core.network.AdaptorTransactionHelpDto
import io.riverark.ferret.core.network.ConnectorAssetDto
import io.riverark.ferret.core.network.ConnectorUtxoDto
import io.riverark.ferret.core.network.MAINNET
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenChannelTransactionsTest {
    private val vectors = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResource("konduit/channel-conformance.json")).readText(),
    ).jsonObject
    private val protocolParameters = requireNotNull(vectors["protocol_parameters_fixture"]).toString()
    private val digest = "a".repeat(64)
    private val catalog = AssetCatalog(
        listOf(
            ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest),
            ChannelAsset("usda", "1".repeat(56), "", 6, AssetPricing.USD_PEG, digest),
            ChannelAsset("usdcx", "2".repeat(56), "", 6, AssetPricing.USD_PEG, digest),
            ChannelAsset("usdm", "3".repeat(56), "", 6, AssetPricing.USD_PEG, digest),
        ),
        digest,
        emptyMap(),
    )
    private val ada = catalog.ada
    private val entropy = ByteArray(32) { it.toByte() }
    private val engine = AndroidCardanoTransactionEngine(object : TransactionProcessor {
        override fun submitTransaction(cborData: ByteArray): Result<String> = error("submission not used")
        @Suppress("UNCHECKED_CAST")
        override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> =
            Result.success("fixture").withValue(Collections.emptyList<EvaluationResult>()) as Result<List<EvaluationResult>>
    })
    private val derived = runBlocking { engine.deriveWallet(entropy, CardanoNetwork.MAINNET) }
    private val profile = WalletProfile(
        WalletId("mainnet-${derived.paymentCredentialHex}"),
        "Mainnet wallet",
        CardanoNetwork.MAINNET,
        derived.paymentAddress,
        derived.stakeAddress,
    )
    private val walletVerificationKey = Account.createFromMnemonic(
        Networks.mainnet(),
        MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" "),
    ).let { HexUtil.encodeHexString(it.publicKeyBytes()) }
    private val reference = ConnectorUtxoDto(
        "11".repeat(32),
        0,
        MAINNET.scriptDeploymentAddress,
        listOf(ConnectorAssetDto("lovelace", "2000000")),
        referenceScriptHash = fixture("validator_hash"),
        referenceScriptVersion = 3,
        referenceScript = fixture("reference_script"),
    ).ledger()
    private val ledger = LedgerSnapshot(
        CardanoNetwork.MAINNET,
        listOf(LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(100_000_000)), reference),
        protocolParameters,
        4_492_800,
    )
    private val info = AdaptorInfoDto(
        AdaptorTermsDto(0),
        AdaptorChannelParametersDto(
            MAINNET.adaptorIdentityHex,
            AdaptorClosePeriodDto(1_800, 0),
            32,
        ),
        AdaptorTransactionHelpDto(MAINNET.scriptDeploymentAddress, MAINNET.validatorHashHex),
        digest,
    )

    @Test fun previewUsesRealOpenBytesAndConfirmationOrdersDurableEffects() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            AssetAmount(ada, 5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val payload = preview.operation.payload as ChannelPayload.Transaction
        val summary = engine.inspect(payload.unsignedBody)
        val channelOutput = summary.outputs.single { it.address == MAINNET.validatorAddress }
        assertEquals(Lovelace(5_000_000), channelOutput.lovelace)
        assertTrue(channelOutput.datum is TransactionDatum.Inline)
        assertEquals(AssetAmount(ada, 3_000_000), preview.resultingSpendableBalance)
        assertEquals(
            100_000_000L - preview.amount.baseUnits - preview.actualFee.baseUnits,
            preview.sourceChange.baseUnits,
        )
        assertTrue(preview.ledgerMinAda.baseUnits > 0)
        assertEquals(1, vault.seedRequests)
        assertTrue(payload.signedTransaction.isEmpty())

        var stored = emptyCollection()
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV3) {
                    stored = collection
                    val pending = collection.channels[preview.operation.keytag.value]?.pending
                    trace += if ((pending?.payload as? ChannelPayload.Transaction)?.signedTransaction?.isNotEmpty() == true) {
                        "journal-signed"
                    } else {
                        "journal-unsigned"
                    }
                }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = writer()
                override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV3) {
                    val pending = requireNotNull(collection.channels[preview.operation.keytag.value]?.pending)
                    trace += if ((pending.payload as ChannelPayload.Transaction).signedTransaction.isEmpty()) {
                        "drive-unsigned"
                    } else {
                        "drive-signed"
                    }
                }
                override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV3) {
                    trace += "drive-terminal"
                }
            },
            object : ChannelRemote {
                override suspend fun mutate(
                    walletId: WalletId,
                    operation: PreparedChannelOperation,
                    writer: WriterLease,
                ): ChannelRemoteResult {
                    trace += "post"
                    val signed = (operation.payload as ChannelPayload.Transaction).signedTransaction
                    engine.inspect(signed).requireL1Witnesses(derived.paymentCredentialHex, signed = true)
                    assertEquals(payload.expectedTransactionId, engine.transactionId(signed))
                    return completed(operation)
                }
                override suspend fun reconcile(
                    walletId: WalletId,
                    operation: PreparedChannelOperation,
                    writer: WriterLease,
                ): ChannelRemoteResult? = error("lookup not expected")
            },
            newOperationId = { "00000000-0000-4000-8000-000000000019" },
            transactions = transactions,
        )

        repository.submit(profile.id, preview)
        val saved = requireNotNull(stored.channels[preview.operation.keytag.value])
        assertEquals(ChannelState.Open(payload.expectedTransactionId), saved.state)
        assertEquals(null, saved.pending)
        assertTrue(trace.indexOf("journal-unsigned") < trace.indexOf("drive-unsigned"))
        assertTrue(trace.indexOf("drive-unsigned") < trace.indexOf("seed-sign"))
        assertTrue(trace.indexOf("seed-sign") < trace.indexOf("journal-signed"))
        assertTrue(trace.indexOf("journal-signed") < trace.indexOf("drive-signed"))
        assertTrue(trace.indexOf("drive-signed") < trace.indexOf("post"))
    }

    @Test fun unsignedRecoveryFailsWithoutLookupSigningOrPost() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            AssetAmount(ada, 5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        var stored = collection(ChannelSnapshot(
            preview.operation.keytag,
            ada,
            ChannelState.Opening(preview.operation.operationId),
            preview.operation,
        ))
        var lookups = 0
        var posts = 0
        val repository = recoveryRepository(
            transactions,
            { stored },
            { stored = it },
            reconcileCall = { _, _, _ -> lookups++; null },
            mutateCall = { _, _, _ -> posts++; error("post not expected") },
        )
        val seedRequests = vault.seedRequests

        repository.reconcile(profile.id, preview.operation.keytag)

        val saved = requireNotNull(stored.channels[preview.operation.keytag.value])
        assertEquals(seedRequests, vault.seedRequests)
        assertEquals(0, lookups)
        assertEquals(0, posts)
        assertEquals(ChannelState.Absent, saved.state)
        assertEquals(null, saved.pending)
    }

    @Test fun signedProposedRecoveryReplaysExactAuthorizationOnce() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            AssetAmount(ada, 5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val signed = transactions.sign(profile.id, preview.operation)
        val signedBytes = (signed.payload as ChannelPayload.Transaction).signedTransaction.copyOf()
        var stored = collection(ChannelSnapshot(
            signed.keytag,
            ada,
            ChannelState.Opening(signed.operationId),
            signed,
        ))
        var posts = 0
        val repository = recoveryRepository(
            transactions,
            { stored },
            { stored = it },
            reconcileCall = { _, _, _ -> null },
            mutateCall = { _, operation, _ ->
                posts++
                assertContentEquals(
                    signedBytes,
                    (operation.payload as ChannelPayload.Transaction).signedTransaction,
                )
                completed(operation)
            },
        )
        val seedRequests = vault.seedRequests

        repository.reconcile(profile.id, signed.keytag)

        assertEquals(seedRequests, vault.seedRequests)
        assertEquals(1, posts)
        assertEquals(null, stored.channels[signed.keytag.value]?.pending)
        assertContentEquals(signedBytes, signed.payload.signedTransaction)
    }

    @Test fun differentAdaKeytagCoexistsAndIsNotConsumed() = runBlocking {
        val existingKeytag = keytag("02")
        val existingTransactionId = "22".repeat(32)
        val existing = ChannelSnapshot(
            existingKeytag,
            ada,
            ChannelState.Open(existingTransactionId),
            spendableBalance = AssetAmount(ada, 3_000_000),
        )
        val existingUtxo = LedgerUtxo(
            existingTransactionId,
            0,
            MAINNET.validatorAddress,
            Lovelace(5_000_000),
            datumHex = ChannelDatum(
                MAINNET.validatorHashHex,
                ChannelConstants("02".repeat(32), walletVerificationKey, MAINNET.adaptorIdentityHex, 1_800_000),
                ChannelDatumStage.Opened(0),
            ).plutus().serializeToHex(),
        )
        val transactions = transactions(
            TestVault(profile, entropy, mutableListOf()),
            ledger.copy(utxos = ledger.utxos + existingUtxo),
        )
        val preview = transactions.preview(
            profile.id,
            AssetAmount(ada, 5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val summary = engine.inspect((preview.operation.payload as ChannelPayload.Transaction).unsignedBody)
        assertTrue(summary.inputs.none { it.transactionId == existingTransactionId && it.index == 0 })

        var stored = collection(existing)
        val repository = recoveryRepository(
            transactions,
            { stored },
            { stored = it },
            reconcileCall = { _, _, _ -> error("lookup not expected") },
            mutateCall = { _, operation, _ -> completed(operation) },
        )
        repository.submit(profile.id, preview)

        assertEquals(2, stored.channels.size)
        assertEquals(existing, stored.channels[existingKeytag.value])
        assertEquals(
            ChannelState.Open(preview.operation.payload.expectedTransactionId),
            stored.channels[preview.operation.keytag.value]?.state,
        )
    }

    @Test fun duplicateKeytagRejectsBeforeSigningOrRemoteMutation() = runBlocking {
        val vault = TestVault(profile, entropy, mutableListOf())
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            AssetAmount(ada, 5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val duplicate = ChannelSnapshot(
            preview.operation.keytag,
            ada,
            ChannelState.Closed,
        )
        var stored = collection(duplicate)
        var posts = 0
        val repository = recoveryRepository(
            transactions,
            { stored },
            { stored = it },
            reconcileCall = { _, _, _ -> error("lookup not expected") },
            mutateCall = { _, operation, _ -> posts++; completed(operation) },
        )
        val seedRequests = vault.seedRequests

        assertFailsWith<IllegalArgumentException> { repository.submit(profile.id, preview) }

        assertEquals(seedRequests, vault.seedRequests)
        assertEquals(0, posts)
        assertEquals(mapOf(duplicate.keytag.value to duplicate), stored.channels)
    }

    @Test fun nativePreviewRejectsBeforeLedgerBuildOrSigning() = runBlocking {
        val vault = TestVault(profile, entropy, mutableListOf())
        var ledgerLoads = 0
        val transactions = transactions(vault, beforeLedger = { ledgerLoads++ })

        assertFailsWith<IllegalArgumentException> {
            transactions.preview(
                profile.id,
                AssetAmount(requireNotNull(catalog.asset("usdm")), 5_000_000),
                "00000000-0000-4000-8000-000000000018",
            )
        }

        assertEquals(0, ledgerLoads)
        assertEquals(0, vault.seedRequests)
    }

    @Test fun previewReportsInsufficientConfirmedAda() = runBlocking {
        val vault = TestVault(profile, entropy, mutableListOf())
        val lowLedger = ledger.copy(
            utxos = listOf(
                LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(3_000_000)),
                reference,
            ),
        )

        assertFailsWith<InsufficientFundsException> {
            transactions(vault, lowLedger).preview(
                profile.id,
                AssetAmount(ada, 5_000_000),
                "00000000-0000-4000-8000-000000000018",
            )
        }
        Unit
    }

    @Test fun staleOrTamperedPreviewFailsBeforeTransactionSigning() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            AssetAmount(ada, 5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val requestsBefore = vault.seedRequests
        assertFailsWith<IllegalArgumentException> {
            transactions.validatePreview(
                profile.id,
                preview.copy(actualFee = AssetAmount(ada, preview.actualFee.baseUnits + 1)),
            )
        }
        assertEquals(requestsBefore, vault.seedRequests)
        val payload = preview.operation.payload as ChannelPayload.Transaction
        assertFailsWith<IllegalArgumentException> {
            transactions.validatePreview(
                profile.id,
                preview.copy(operation = preview.operation.copy(
                    payload = payload.copy(unsignedBody = payload.unsignedBody.copyOf().also { it[it.lastIndex] = 1 }),
                )),
            )
        }
        assertEquals(requestsBefore, vault.seedRequests)
    }

    private fun recoveryRepository(
        transactions: OpenChannelTransactions,
        loadCollection: () -> ChannelCollectionV3,
        saveCollection: (ChannelCollectionV3) -> Unit,
        reconcileCall: suspend (WalletId, PreparedChannelOperation, WriterLease) -> ChannelRemoteResult?,
        mutateCall: suspend (WalletId, PreparedChannelOperation, WriterLease) -> ChannelRemoteResult,
    ) = ChannelRepository(
        WalletRepository(),
        object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = loadCollection()
            override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV3) = saveCollection(collection)
        },
        object : ChannelBackupProtocol {
            override suspend fun requireVerifiedWriter(walletId: WalletId) = writer()
            override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV3) = Unit
            override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV3) = Unit
        },
        object : ChannelRemote {
            override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                mutateCall(walletId, operation, writer)
            override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                reconcileCall(walletId, operation, writer)
        },
        transactions = transactions,
    )

    private fun transactions(
        vault: TestVault,
        currentLedger: LedgerSnapshot = ledger,
        beforeLedger: () -> Unit = {},
    ) = OpenChannelTransactions(
        vault,
        engine,
        catalog,
        loadLedger = { beforeLedger(); currentLedger },
        loadInfo = { info },
        verificationKey = {
            vault.notePublicDerivation()
            walletVerificationKey
        },
        availability = {},
        newTag = { ByteArray(32) { 1 } },
        nowEpochMillis = { 123 },
    )

    private fun emptyCollection() = ChannelCollectionV3(walletId = profile.id, catalogDigest = digest)

    private fun collection(vararg entries: ChannelSnapshot) = emptyCollection().copy(
        channels = entries.associateBy { it.keytag.value },
    )

    private fun keytag(byte: String) =
        ProtocolKeytag.from(walletVerificationKey, ProtocolTag(byte.repeat(32)), 32)

    private fun writer() = WriterLease(
        "a".repeat(64),
        1,
        "b".repeat(64),
        "c".repeat(64),
        9_999,
    )

    private fun completed(operation: PreparedChannelOperation): ChannelRemoteResult {
        val transactionId = (operation.payload as ChannelPayload.Transaction).expectedTransactionId
        return ChannelRemoteResult(
            operationId = operation.operationId,
            intentHash = operation.intentHash,
            keytag = operation.keytag,
            asset = operation.asset,
            transactionId = transactionId,
            state = ChannelState.Open(transactionId),
            status = OperationState.COMPLETED,
        )
    }

    private fun fixture(name: String) = requireNotNull(vectors[name]).jsonPrimitive.content

    private class TestVault(
        private val profile: WalletProfile,
        entropy: ByteArray,
        private val trace: MutableList<String>,
    ) : SecureVault {
        private val seed = entropy.copyOf()
        var seedRequests = 0
        override val isUnlocked = true
        fun notePublicDerivation() { seedRequests++ }
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = listOf(profile)
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) = error("not used")
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T {
            seedRequests++
            trace += "seed-sign"
            val copy = seed.copyOf()
            return try { action(copy) } finally { copy.fill(0) }
        }
        override suspend fun walletState(walletId: WalletId) = WalletEncryptedStateV1()
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) = Unit
    }
}
