package io.riverark.ferret.core.channel

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.AdaptorClient
import io.riverark.ferret.core.network.PREPROD
import io.riverark.ferret.core.network.ferretHttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ChannelRemoteRecoveryTest {
    private val walletId = WalletId("preprod-${"0".repeat(56)}")
    private val operationId = "00000000-0000-4000-8000-000000000000"
    private val transactionId = "1".repeat(64)
    private val keytag = ProtocolKeytag("2".repeat(64) + "00")
    private val otherKeytag = ProtocolKeytag("2".repeat(64) + "01")
    private val digest = "a".repeat(64)
    private val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
    private val usdm = ChannelAsset("usdm", "1".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
    private val writer = WriterLease("3".repeat(64), 1, "4".repeat(64), "5".repeat(64), 1_000)
    private val crypto = object : ProtocolCrypto {
        override fun verify(verificationKey: ByteArray, message: ByteArray, signature: ByteArray) = false
        override fun sha256(input: ByteArray) = ByteArray(32)
    }

    @Test fun onlyBounded404MeansOperationIsAbsent() = runBlocking {
        val accepted = operationJson(operationId, transactionId)
        val cases = listOf(
            Reply(HttpStatusCode.Unauthorized, "no") to "401",
            Reply(HttpStatusCode.Forbidden, "no") to "403",
            Reply(HttpStatusCode.Conflict, "no") to "409",
            Reply(HttpStatusCode.InternalServerError, "no") to "500",
            Reply(HttpStatusCode.Found, "redirect") to "302",
            Reply(HttpStatusCode.OK, "{") to "malformed",
            Reply(HttpStatusCode.OK, operationJson("00000000-0000-4000-8000-000000000001", transactionId)) to "mismatch",
            Reply(HttpStatusCode.NotFound, "x".repeat(1_048_577)) to "oversized",
        )
        for ((reply, label) in cases) {
            val engine = ScriptedEngine(ArrayDeque(listOf(reply)))
            val client = ferretHttpClient(engine)
            try {
                assertFails(label) { remote(client).reconcile(walletId, operation(), writer) }
                assertEquals(listOf(HttpMethod.Get), engine.requests.map { it.method }, label)
            } finally {
                client.close()
            }
        }

        val missingEngine = ScriptedEngine(ArrayDeque(listOf(Reply(HttpStatusCode.NotFound, "missing"))))
        val missingClient = ferretHttpClient(missingEngine)
        try {
            assertNull(remote(missingClient).reconcile(walletId, operation(), writer))
        } finally {
            missingClient.close()
        }

        val presentEngine = ScriptedEngine(ArrayDeque(listOf(Reply(HttpStatusCode.OK, accepted))))
        val presentClient = ferretHttpClient(presentEngine)
        try {
            val result = remote(presentClient).reconcile(walletId, operation(), writer)
            assertEquals(OperationState.SUBMITTED, result?.status)
            assertEquals(keytag, result?.keytag)
            assertEquals(ada, result?.asset)
        } finally {
            presentClient.close()
        }
    }

    @Test fun missingSubmittedPaymentIsFailedAfterServerCleanup() = runBlocking {
        val receipt = """{"squash":{"body":{"amount":0,"index":0,"exclude":[]},"signature":"${"a".repeat(128)}"},"cheques":[]}"""
        val engine = ScriptedEngine(ArrayDeque(listOf(Reply(HttpStatusCode.OK, receipt))))
        val client = ferretHttpClient(engine)
        val acceptingCrypto = object : ProtocolCrypto {
            override fun verify(verificationKey: ByteArray, message: ByteArray, signature: ByteArray) = true
            override fun sha256(input: ByteArray) = ByteArray(32)
        }
        try {
            val operation = paymentOperation(OperationState.SUBMITTED)

            val result = AdaptorChannelRemote(
                { AdaptorClient(client, PREPROD, acceptingCrypto) },
                acceptingCrypto,
            ).reconcile(walletId, operation, writer)
            assertEquals(OperationState.FAILED, result?.status)
            assertEquals(keytag, result?.keytag)
            assertNotNull(result?.protocolReceipt)
        } finally {
            client.close()
        }
        Unit
    }

    @Test fun terminalPaymentFailurePreservesSafeReason() = runBlocking {
        val engine = ScriptedEngine(ArrayDeque(listOf(
            Reply(HttpStatusCode.BadRequest, "data: Bln: Payment failed: FAILURE_REASON_NO_ROUTE"),
        )))
        val client = ferretHttpClient(engine)
        try {
            val result = remote(client).mutate(
                walletId,
                paymentOperation(OperationState.WRITE_AHEAD_VERIFIED),
                writer,
            )
            assertEquals(OperationState.FAILED, result.status)
            assertEquals("No Lightning route was available.", result.failureMessage)
            assertEquals(keytag, result.keytag)
            assertEquals(ada, result.asset)
        } finally {
            client.close()
        }
    }

    @Test fun transportFailureAndCancellationNeverAuthorizeReplay() = runBlocking {
        for (failure in listOf(IllegalStateException("timeout"), CancellationException("cancelled"))) {
            val engine = ScriptedEngine(ArrayDeque(listOf(failure)))
            val client = ferretHttpClient(engine)
            try {
                if (failure is CancellationException) {
                    assertFailsWith<CancellationException> { remote(client).reconcile(walletId, operation(), writer) }
                } else {
                    assertFailsWith<IllegalStateException> { remote(client).reconcile(walletId, operation(), writer) }
                }
                assertEquals(listOf(HttpMethod.Get), engine.requests.map { it.method })
            } finally {
                client.close()
            }
        }
    }

    @Test fun legacyTransactionCannotReplayAndSubmittedDoesNotPost() = runBlocking {
        val responses = ArrayDeque<Any>(listOf(
            Reply(HttpStatusCode.NotFound, "missing"),
            Reply(HttpStatusCode.NotFound, "missing"),
        ))
        val engine = ScriptedEngine(responses)
        val client = ferretHttpClient(engine)
        try {
            var stored = collection(
                ChannelSnapshot(
                    keytag,
                    ada,
                    ChannelState.Opening(operationId),
                    operation().copy(state = OperationState.WRITE_AHEAD_VERIFIED),
                ),
            )
            val repository = repository({ stored }, { stored = it }, remote(client))
            repository.load(walletId)

            assertFailsWith<IllegalArgumentException> { repository.reconcile(walletId, keytag) }
            assertEquals(listOf(HttpMethod.Get), engine.requests.map { it.method })
            val entry = stored.channels.getValue(keytag.value)
            assertEquals(operationId, entry.pending?.operationId)
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                (entry.pending?.payload as ChannelPayload.Transaction).signedTransaction,
            )

            stored = stored.copy(channels = stored.channels + (
                keytag.value to entry.copy(pending = entry.pending.copy(state = OperationState.SUBMITTED))
            ))
            val submitted = repository({ stored }, { stored = it }, remote(client))
            submitted.load(walletId)
            submitted.reconcile(walletId, keytag)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Get), engine.requests.map { it.method })
        } finally {
            client.close()
        }
    }

    @Test fun submittedPaymentReplaysStableAuthorizationToResolveItsSecret() = runBlocking {
        val operation = paymentOperation(OperationState.SUBMITTED, usdm)
        var stored = collection(
            paymentSnapshot(operation),
            ChannelSnapshot(otherKeytag, ada, ChannelState.Open("other"), spendableBalance = AssetAmount(ada, 9_000)),
        )
        val untouched = stored.channels.getValue(otherKeytag.value)
        repository({ stored }, { stored = it }, object : ChannelRemote {
            override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                error("recovery must run only after restart")
            override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease) =
                error("recovery must run only after restart")
        }).load(walletId)
        var mutations = 0
        var replayed: PreparedChannelOperation? = null
        val repository = repository(
            { stored },
            { stored = it },
            object : ChannelRemote {
                override suspend fun reconcile(
                    walletId: WalletId,
                    operation: PreparedChannelOperation,
                    writer: WriterLease,
                ) = ChannelRemoteResult(
                    operation.operationId,
                    operation.intentHash,
                    operation.keytag,
                    operation.asset,
                    state = ChannelState.Open("channel"),
                    status = OperationState.SUBMITTED,
                )

                override suspend fun mutate(
                    walletId: WalletId,
                    operation: PreparedChannelOperation,
                    writer: WriterLease,
                ): ChannelRemoteResult {
                    mutations++
                    replayed = operation
                    return ChannelRemoteResult(
                        operation.operationId,
                        operation.intentHash,
                        operation.keytag,
                        operation.asset,
                        state = ChannelState.Open("channel"),
                        status = OperationState.COMPLETED,
                    )
                }
            },
        )
        repository.load(walletId)

        repository.reconcile(walletId, keytag)
        assertNull(repository.reconcile(walletId, keytag))

        assertEquals(1, mutations)
        assertEquals(keytag, replayed?.keytag)
        assertEquals(usdm, replayed?.asset)
        assertContentEquals(
            (operation.payload as ChannelPayload.Payment).authorizationCbor,
            (replayed?.payload as ChannelPayload.Payment).authorizationCbor,
        )
        val entry = stored.channels.getValue(keytag.value)
        assertEquals(usdm, entry.asset)
        assertEquals(29_897, entry.spendableBalance.baseUnits)
        assertNull(entry.pending)
        assertEquals(setOf(operation.payload.invoiceHash), stored.paidHashes)
        assertEquals(1, entry.payments.receipts.size)
        assertEquals(untouched, stored.channels.getValue(otherKeytag.value))
    }

    private fun operation() = PreparedChannelOperation(
        operationId = operationId,
        intentHash = "6".repeat(64),
        keytag = keytag,
        asset = ada,
        action = ChannelAction.Open(AssetAmount(ada, 3_000_000)),
        preparedAtEpochMillis = 123,
        payload = ChannelPayload.Transaction(byteArrayOf(9), transactionId, byteArrayOf(1, 2, 3)),
    )

    private fun paymentOperation(state: OperationState, asset: ChannelAsset = ada): PreparedChannelOperation {
        val hash = "7".repeat(64)
        val quote = PaymentQuote(
            "quote",
            keytag,
            AssetAmount(asset, 100),
            1_000,
            AssetAmount(asset, 1),
            AssetAmount(asset, 2),
            1_000,
            hash,
            bindingVersion = 2,
        )
        return PreparedChannelOperation(
            operationId = operationId,
            intentHash = "6".repeat(64),
            keytag = keytag,
            asset = asset,
            action = ChannelAction.Pay(quote.id, hash),
            priorChannelIdentity = "channel",
            preparedAtEpochMillis = 1,
            payload = ChannelPayload.Payment(
                byteArrayOf(1),
                "lnbc1fixture",
                hash,
                quote.id,
                AdaptorPayRequest(
                    ChequeBodyWire(1, 103, ProtocolDurationWire(1, 0), Hex32(hash)),
                    "b".repeat(128),
                    "lnbc1fixture",
                ),
                quote,
            ),
            resultingSpendableBalance = AssetAmount(asset, 29_897),
            state = state,
        )
    }

    private fun paymentSnapshot(operation: PreparedChannelOperation): ChannelSnapshot {
        val payment = operation.payload as ChannelPayload.Payment
        return ChannelSnapshot(
            keytag,
            operation.asset,
            ChannelState.Open("channel"),
            operation,
            spendableBalance = AssetAmount(operation.asset, 30_000),
            payments = PaymentJournalV2(
                pending = PendingPayment(
                    operation.operationId,
                    payment.invoiceHash,
                    payment.quote,
                    operation.preparedAtEpochMillis,
                ),
            ),
        )
    }

    private fun remote(client: io.ktor.client.HttpClient) =
        AdaptorChannelRemote({ AdaptorClient(client, PREPROD, crypto) }, crypto)

    private fun collection(vararg entries: ChannelSnapshot) = ChannelCollectionV4(
        walletId = walletId,
        catalogDigest = digest,
        channels = entries.associateBy { it.keytag.value },
    )

    private fun repository(
        load: () -> ChannelCollectionV4,
        save: (ChannelCollectionV4) -> Unit,
        remote: ChannelRemote,
    ) = ChannelRepository(
        WalletRepository(),
        object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = load()
            override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) = save(collection)
        },
        object : ChannelBackupProtocol {
            override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
            override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) = Unit
            override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) = Unit
        },
        remote,
    )

    private fun operationJson(operationId: String, transactionId: String) =
        """{"operation_id":"$operationId","expected_transaction_id":"$transactionId","transaction_id":"$transactionId","status":"accepted","depth":0}"""

    private data class Reply(val status: HttpStatusCode, val body: String)

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class ScriptedEngine(private val replies: ArrayDeque<Any>) : HttpClientEngineBase("channel-recovery-test") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities = setOf(HttpTimeoutCapability)
        val requests = mutableListOf<HttpRequestData>()
        val requestBodies = mutableListOf<ByteArray>()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            requests += data
            (data.body as? OutgoingContent.ByteArrayContent)?.let { requestBodies += it.bytes() }
            return when (val reply = replies.removeFirst()) {
                is Throwable -> throw reply
                is Reply -> HttpResponseData(
                    reply.status,
                    GMTDate(),
                    Headers.Empty,
                    HttpProtocolVersion.HTTP_1_1,
                    ByteReadChannel(reply.body.encodeToByteArray()),
                    currentCoroutineContext() + Job(),
                )
                else -> error("unsupported scripted reply")
            }
        }
    }
}
