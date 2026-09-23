package io.riverark.ferret.core.channel

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.util.HexUtil
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.transaction.spec.Transaction
import io.riverark.ferret.core.cardano.AndroidCardanoTransactionEngine
import io.riverark.ferret.core.cardano.CardanoIntent
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
import java.math.BigInteger
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
            Result.success("fixture").withValue(
                Transaction.deserialize(cbor).witnessSet?.redeemers.orEmpty().map {
                    EvaluationResult.builder()
                        .redeemerTag(it.tag)
                        .index(it.index.intValueExact())
                        .exUnits(ExUnits.builder().mem(BigInteger.valueOf(10_000)).steps(BigInteger.valueOf(10_000_000)).build())
                        .build()
                },
            ) as Result<List<EvaluationResult>>
    }, catalog)
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

    @Test fun addPreviewSignsEvaluationBytesAndSignsExactTransactionBytes() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val datum = ChannelDatum(
            MAINNET.validatorHashHex,
            ChannelConstants(
                "01".repeat(32),
                walletVerificationKey,
                MAINNET.adaptorIdentityHex,
                1_800_000,
                ada,
            ),
            ChannelDatumStage.Opened(0),
        )
        val channelInput = LedgerUtxo(
            "22".repeat(32),
            0,
            MAINNET.validatorAddress,
            Lovelace(5_000_000),
            datumHex = datum.plutus().serializeToHex(),
        )
        val currentLedger = ledger.copy(
            utxos = listOf(
                LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(100_000_000)),
                LedgerUtxo("33".repeat(32), 0, profile.paymentAddress, Lovelace(5_000_000)),
                reference,
                channelInput,
            ),
        )
        val snapshot = ChannelSnapshot(
            keytag("01"),
            ada,
            ChannelState.Open("opening-transaction"),
            spendableBalance = AssetAmount(ada, 3_000_000),
        )
        val transactions = transactions(vault, currentLedger)

        val preview = transactions.previewAdd(
            profile.id,
            snapshot,
            AssetAmount(ada, 1_000_000),
            "00000000-0000-4000-8000-000000000017",
        )

        assertEquals(1, vault.seedRequests)
        assertEquals(snapshot.keytag, preview.operation.keytag)
        assertEquals("opening-transaction", preview.operation.priorChannelIdentity)
        assertEquals(AssetAmount(ada, 4_000_000), preview.resultingSpendableBalance)
        assertEquals(Lovelace(6_000_000), engine.inspect(
            (preview.operation.payload as ChannelPayload.Transaction).unsignedBody,
        ).outputs.single { it.address == MAINNET.validatorAddress }.lovelace)
        assertTrue(requireNotNull(preview.collateral).baseUnits > 0)

        val signed = transactions.sign(profile.id, preview.operation)
        assertEquals(2, vault.seedRequests)
        transactions.validateReplay(profile.id, signed)
        assertContentEquals(
            (signed.payload as ChannelPayload.Transaction).unsignedBody,
            preview.operation.payload.unsignedBody,
        )
    }

    @Test fun durableAddCreditsCapacityOnceAndKeepsSiblingState() = runBlocking {
        val vault = TestVault(profile, entropy, mutableListOf())
        val datum = ChannelDatum(
            MAINNET.validatorHashHex,
            ChannelConstants("01".repeat(32), walletVerificationKey, MAINNET.adaptorIdentityHex, 1_800_000, ada),
            ChannelDatumStage.Opened(0),
        )
        val channelInput = LedgerUtxo(
            "22".repeat(32), 0, MAINNET.validatorAddress, Lovelace(5_000_000),
            datumHex = datum.plutus().serializeToHex(),
        )
        val currentLedger = ledger.copy(utxos = listOf(
            LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(100_000_000)),
            LedgerUtxo("33".repeat(32), 0, profile.paymentAddress, Lovelace(5_000_000)),
            reference,
            channelInput,
        ))
        val selected = ChannelSnapshot(
            keytag("01"), ada, ChannelState.Open("opening-transaction"),
            spendableBalance = AssetAmount(ada, 3_000_000),
        )
        val sibling = ChannelSnapshot(
            keytag("02"), ada, ChannelState.Open("sibling-opening"),
            spendableBalance = AssetAmount(ada, 7_000_000),
        )
        var stored = collection(selected, sibling).copy(paidHashes = setOf("9".repeat(64)))
        val transactions = transactions(vault, currentLedger)
        val preview = transactions.previewAdd(
            profile.id,
            selected,
            AssetAmount(ada, 1_000_000),
            "00000000-0000-4000-8000-000000000016",
        )
        val repository = recoveryRepository(
            transactions,
            { stored },
            { stored = it },
            { _, _, _ -> error("reconcile not expected") },
            { _, operation, _ ->
                assertEquals(AssetAmount(ada, 3_000_000), stored.channels.getValue(selected.keytag.value).spendableBalance)
                ChannelRemoteResult(
                    operation.operationId,
                    operation.intentHash,
                    operation.keytag,
                    operation.asset,
                    transactionId = (operation.payload as ChannelPayload.Transaction).expectedTransactionId,
                    state = ChannelState.Open("opening-transaction"),
                    status = OperationState.COMPLETED,
                )
            },
        )
        repository.load(profile.id)

        repository.submit(profile.id, preview)
        repository.reconcile(profile.id, selected.keytag)

        assertEquals(AssetAmount(ada, 4_000_000), stored.channels.getValue(selected.keytag.value).spendableBalance)
        assertEquals(sibling, stored.channels.getValue(sibling.keytag.value))
        assertEquals(setOf("9".repeat(64)), stored.paidHashes)
        assertEquals(1, stored.channels.getValue(selected.keytag.value).history.size)
    }

    @Test fun unsignedAddRestartReturnsToPriorOpenChannelWithoutSigningOrNetworkMutation() = runBlocking {
        val vault = TestVault(profile, entropy, mutableListOf())
        val datum = ChannelDatum(
            MAINNET.validatorHashHex,
            ChannelConstants("01".repeat(32), walletVerificationKey, MAINNET.adaptorIdentityHex, 1_800_000, ada),
            ChannelDatumStage.Opened(0),
        )
        val channelInput = LedgerUtxo(
            "22".repeat(32), 0, MAINNET.validatorAddress, Lovelace(5_000_000),
            datumHex = datum.plutus().serializeToHex(),
        )
        val currentLedger = ledger.copy(utxos = listOf(
            LedgerUtxo("00".repeat(32), 0, profile.paymentAddress, Lovelace(100_000_000)),
            LedgerUtxo("33".repeat(32), 0, profile.paymentAddress, Lovelace(5_000_000)),
            reference,
            channelInput,
        ))
        val selected = ChannelSnapshot(
            keytag("01"), ada, ChannelState.Open("opening-transaction"),
            spendableBalance = AssetAmount(ada, 3_000_000),
        )
        val transactions = transactions(vault, currentLedger)
        val preview = transactions.previewAdd(
            profile.id,
            selected,
            AssetAmount(ada, 1_000_000),
            "00000000-0000-4000-8000-000000000015",
        )
        val seedRequests = vault.seedRequests
        var stored = collection(selected.copy(pending = preview.operation))
        var networkCalls = 0
        val repository = recoveryRepository(
            transactions,
            { stored },
            { stored = it },
            { _, _, _ -> networkCalls++; null },
            { _, operation, _ ->
                networkCalls++
                error("mutation not expected for ${operation.operationId}")
            },
        )
        repository.load(profile.id)

        repository.reconcile(profile.id, selected.keytag)

        val recovered = stored.channels.getValue(selected.keytag.value)
        assertEquals(ChannelState.Open("opening-transaction"), recovered.state)
        assertEquals(AssetAmount(ada, 3_000_000), recovered.spendableBalance)
        assertEquals(OperationState.FAILED, recovered.history.single().status)
        assertEquals(seedRequests, vault.seedRequests)
        assertEquals(0, networkCalls)
    }

    @Test fun previewUsesRealOpenBytesAndConfirmationOrdersDurableEffects() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.previewOpen(
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
            preview.sourceChange?.lovelace?.value,
        )
        assertTrue(preview.ledgerMinAda.baseUnits > 0)
        assertEquals(1, vault.seedRequests)
        assertTrue(payload.signedTransaction.isEmpty())

        var stored = emptyCollection()
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) {
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
                override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) {
                    val pending = requireNotNull(collection.channels[preview.operation.keytag.value]?.pending)
                    trace += if ((pending.payload as ChannelPayload.Transaction).signedTransaction.isEmpty()) {
                        "drive-unsigned"
                    } else {
                        "drive-signed"
                    }
                }
                override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) {
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
        val preview = transactions.previewOpen(
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

    @Test fun nativeSignedRecoveryReplaysExactAuthorizationOnce() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val usdm = requireNotNull(catalog.asset("usdm"))
        val nativeLedger = ledger.copy(utxos = listOf(
            ledger.utxos.first().copy(assets = mapOf(usdm.connectorUnit to 10_000_000)),
            reference,
        ))
        val transactions = transactions(vault, nativeLedger)
        val preview = transactions.previewOpen(
            profile.id,
            AssetAmount(usdm, 1_250_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val signed = transactions.sign(profile.id, preview.operation)
        val signedBytes = (signed.payload as ChannelPayload.Transaction).signedTransaction.copyOf()
        var stored = collection(ChannelSnapshot(
            signed.keytag,
            usdm,
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
        assertEquals(AssetAmount(usdm, 1_250_000), stored.channels.getValue(signed.keytag.value).spendableBalance)
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
                ChannelConstants("02".repeat(32), walletVerificationKey, MAINNET.adaptorIdentityHex, 1_800_000, ada),
                ChannelDatumStage.Opened(0),
            ).plutus().serializeToHex(),
        )
        val transactions = transactions(
            TestVault(profile, entropy, mutableListOf()),
            ledger.copy(utxos = ledger.utxos + existingUtxo),
        )
        val preview = transactions.previewOpen(
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
        val preview = transactions.previewOpen(
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

    @Test fun nativePreviewCarriesAdaAndPreservesOtherAssets() = runBlocking {
        val vault = TestVault(profile, entropy, mutableListOf())
        val usdm = requireNotNull(catalog.asset("usdm"))
        val usdcx = requireNotNull(catalog.asset("usdcx"))
        val nativeLedger = ledger.copy(utxos = listOf(
            ledger.utxos.first().copy(assets = mapOf(
                usdm.connectorUnit to 10_000_000,
                usdcx.connectorUnit to 3_000_000,
            )),
            reference,
        ))
        val preview = transactions(vault, nativeLedger).previewOpen(
            profile.id,
            AssetAmount(usdm, 1_250_000),
            "00000000-0000-4000-8000-000000000018",
        )

        assertEquals(AssetAmount(usdm, 1_250_000), preview.resultingSpendableBalance)
        assertEquals(8_750_000L, preview.sourceChange?.assets?.get(usdm.connectorUnit))
        assertEquals(3_000_000L, preview.sourceChange?.assets?.get(usdcx.connectorUnit))
        assertTrue(preview.outputAda.baseUnits >= 2_000_000)
        assertEquals(usdm, (preview.operation.payload as ChannelPayload.Transaction)
            .let { it.intent as CardanoIntent.OpenChannel }.datum.constants.asset)
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
            transactions(vault, lowLedger).previewOpen(
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
        val preview = transactions.previewOpen(
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
        transactions: ChannelTransactions,
        loadCollection: () -> ChannelCollectionV4,
        saveCollection: (ChannelCollectionV4) -> Unit,
        reconcileCall: suspend (WalletId, PreparedChannelOperation, WriterLease) -> ChannelRemoteResult?,
        mutateCall: suspend (WalletId, PreparedChannelOperation, WriterLease) -> ChannelRemoteResult,
    ) = ChannelRepository(
        WalletRepository(),
        object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = loadCollection()
            override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) = saveCollection(collection)
        },
        object : ChannelBackupProtocol {
            override suspend fun requireVerifiedWriter(walletId: WalletId) = writer()
            override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) = Unit
            override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) = Unit
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
    ) = ChannelTransactions(vault,
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
    nowEpochMillis = { 123 },)

    private fun emptyCollection() = ChannelCollectionV4(walletId = profile.id, catalogDigest = digest)

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
