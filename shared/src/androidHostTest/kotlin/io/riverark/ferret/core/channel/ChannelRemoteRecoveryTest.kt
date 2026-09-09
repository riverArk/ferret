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

class ChannelRemoteRecoveryTest {
    private val walletId = WalletId("preprod-${"0".repeat(56)}")
    private val operationId = "00000000-0000-4000-8000-000000000000"
    private val transactionId = "1".repeat(64)
    private val keytag = "2".repeat(64) + "00"
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
            assertEquals(OperationState.SUBMITTED, remote(presentClient).reconcile(walletId, operation(), writer)?.status)
        } finally {
            presentClient.close()
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

    @Test fun absentReplayUsesStableTransactionAndSubmittedDoesNotPost() = runBlocking {
        val signed = byteArrayOf(1, 2, 3)
        val responses = ArrayDeque<Any>(listOf(
            Reply(HttpStatusCode.NotFound, "missing"),
            Reply(HttpStatusCode.OK, operationJson(operationId, transactionId)),
            Reply(HttpStatusCode.OK, operationJson(operationId, transactionId)),
            Reply(HttpStatusCode.NotFound, "missing"),
        ))
        val engine = ScriptedEngine(responses)
        val client = ferretHttpClient(engine)
        try {
            var stored = ChannelSnapshot(
                ChannelState.Opening(operationId),
                operation().copy(state = OperationState.WRITE_AHEAD_VERIFIED),
            )
            val repository = repository({ stored }, { stored = it }, remote(client))
            repository.load(walletId)

            repository.reconcile(walletId)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), engine.requests.map { it.method })
            assertEquals(operationId, stored.pending?.operationId)
            assertEquals(
                """{"operation_id":"$operationId","expected_transaction_id":"$transactionId","transaction":"010203"}""",
                engine.requestBodies.single().decodeToString(),
            )
            assertEquals(keytag, engine.requests[1].headers["KONDUIT"])
            assertEquals(writer.token, engine.requests[1].headers["FERRET-SESSION"])
            assertContentEquals(signed, (stored.pending?.payload as ChannelPayload.Transaction).signedTransaction)

            repository.reconcile(walletId)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Get), engine.requests.map { it.method })

            stored = stored.copy(pending = stored.pending?.copy(state = OperationState.SUBMITTED))
            val submitted = repository({ stored }, { stored = it }, remote(client))
            submitted.load(walletId)
            submitted.reconcile(walletId)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), engine.requests.map { it.method })
        } finally {
            client.close()
        }
    }

    private fun operation() = PreparedChannelOperation(
        operationId,
        "6".repeat(64),
        ChannelAction.Open(3_000_000),
        preparedAtEpochMillis = 123,
        payload = ChannelPayload.Transaction(byteArrayOf(9), transactionId, byteArrayOf(1, 2, 3)),
        keytag = keytag,
    )

    private fun remote(client: io.ktor.client.HttpClient) =
        AdaptorChannelRemote({ AdaptorClient(client, PREPROD, crypto) }, crypto)

    private fun repository(
        load: () -> ChannelSnapshot,
        save: (ChannelSnapshot) -> Unit,
        remote: ChannelRemote,
    ) = ChannelRepository(
        WalletRepository(),
        object : ChannelJournal {
            override suspend fun load(walletId: WalletId) = load()
            override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) = save(snapshot)
        },
        object : ChannelBackupProtocol {
            override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
            override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
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
