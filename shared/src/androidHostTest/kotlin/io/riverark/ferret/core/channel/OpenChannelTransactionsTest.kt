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
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.TransactionDatum
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.model.CardanoNetwork
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
    )

    @Test fun previewUsesRealOpenBytesAndConfirmationOrdersDurableEffects() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val payload = preview.operation.payload as ChannelPayload.Transaction
        val summary = engine.inspect(payload.unsignedBody)
        val channelOutput = summary.outputs.single { it.address == MAINNET.validatorAddress }
        assertEquals(Lovelace(5_000_000), channelOutput.lovelace)
        assertTrue(channelOutput.datum is TransactionDatum.Inline)
        assertEquals(Lovelace(3_000_000), preview.resultingSpendableBalance)
        assertEquals(
            100_000_000L - preview.amount.value - preview.actualFee.value,
            preview.sourceChange.value,
        )
        assertTrue(preview.ledgerMinAda.value > 0)
        assertEquals(1, vault.seedRequests)
        assertTrue(payload.signedTransaction.isEmpty())

        var stored = ChannelSnapshot(ChannelState.Absent)
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) {
                    stored = snapshot
                    trace += if ((snapshot.pending?.payload as? ChannelPayload.Transaction)?.signedTransaction?.isNotEmpty() == true) {
                        "journal-signed"
                    } else {
                        "journal-unsigned"
                    }
                }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = WriterLease(
                    "a".repeat(64), 1, "b".repeat(64), "c".repeat(64), 9_999,
                )
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) {
                    trace += if ((snapshot.pending?.payload as ChannelPayload.Transaction).signedTransaction.isEmpty()) {
                        "drive-unsigned"
                    } else {
                        "drive-signed"
                    }
                }
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) { trace += "drive-terminal" }
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
                    return ChannelRemoteResult(
                        operation.operationId,
                        operation.intentHash,
                        payload.expectedTransactionId,
                        ChannelState.Open(payload.expectedTransactionId),
                        OperationState.COMPLETED,
                        operation.keytag,
                    )
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
        assertEquals(ChannelState.Open(payload.expectedTransactionId), stored.state)
        assertEquals(null, stored.pending)
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
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        var stored = ChannelSnapshot(ChannelState.Opening(preview.operation.operationId), preview.operation)
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

        repository.reconcile(profile.id)

        assertEquals(seedRequests, vault.seedRequests)
        assertEquals(0, lookups)
        assertEquals(0, posts)
        assertEquals(ChannelState.Absent, stored.state)
        assertEquals(null, stored.pending)
    }

    @Test fun signedProposedRecoveryReplaysExactAuthorizationOnce() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val signed = transactions.sign(profile.id, preview.operation)
        val signedBytes = (signed.payload as ChannelPayload.Transaction).signedTransaction.copyOf()
        var stored = ChannelSnapshot(ChannelState.Opening(signed.operationId), signed)
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
                ChannelRemoteResult(
                    operation.operationId,
                    operation.intentHash,
                    (operation.payload as ChannelPayload.Transaction).expectedTransactionId,
                    ChannelState.Open((operation.payload as ChannelPayload.Transaction).expectedTransactionId),
                    OperationState.COMPLETED,
                    operation.keytag,
                )
            },
        )
        val seedRequests = vault.seedRequests

        repository.reconcile(profile.id)

        assertEquals(seedRequests, vault.seedRequests)
        assertEquals(1, posts)
        assertEquals(null, stored.pending)
        assertContentEquals(signedBytes, (signed.payload as ChannelPayload.Transaction).signedTransaction)
    }

    private fun recoveryRepository(
        transactions: OpenChannelTransactions,
        loadSnapshot: () -> ChannelSnapshot,
        saveSnapshot: (ChannelSnapshot) -> Unit,
        reconcileCall: suspend (WalletId, PreparedChannelOperation, WriterLease) -> ChannelRemoteResult?,
        mutateCall: suspend (WalletId, PreparedChannelOperation, WriterLease) -> ChannelRemoteResult,
    ) = ChannelRepository(
        WalletRepository(),
        object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = loadSnapshot()
            override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) = saveSnapshot(snapshot)
        },
        object : ChannelBackupProtocol {
            override suspend fun requireVerifiedWriter(walletId: WalletId) =
                WriterLease("a".repeat(64), 1, "b".repeat(64), "c".repeat(64), 9_999)
            override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
        },
        object : ChannelRemote {
            override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                mutateCall(walletId, operation, writer)
            override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                reconcileCall(walletId, operation, writer)
        },
        transactions = transactions,
    )

    @Test fun staleOrTamperedPreviewFailsBeforeTransactionSigning() = runBlocking {
        val trace = mutableListOf<String>()
        val vault = TestVault(profile, entropy, trace)
        val transactions = transactions(vault)
        val preview = transactions.preview(
            profile.id,
            Lovelace(5_000_000),
            "00000000-0000-4000-8000-000000000018",
        )
        val requestsBefore = vault.seedRequests
        assertFailsWith<IllegalArgumentException> {
            transactions.validatePreview(profile.id, preview.copy(actualFee = Lovelace(preview.actualFee.value + 1)))
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

    private fun transactions(vault: TestVault) = OpenChannelTransactions(
        vault,
        engine,
        loadLedger = { ledger },
        loadInfo = { info },
        verificationKey = {
            val account = Account.createFromMnemonic(
                Networks.mainnet(),
                MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" "),
            )
            vault.notePublicDerivation()
            HexUtil.encodeHexString(account.publicKeyBytes())
        },
        availability = {},
        newTag = { ByteArray(32) { 1 } },
        nowEpochMillis = { 123 },
    )

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
