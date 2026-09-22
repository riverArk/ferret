package io.riverark.ferret.core.channel

import com.bloxbean.cardano.client.crypto.KeyGenUtil
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.riverark.ferret.core.cardano.AndroidProtocolCrypto
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.network.AdaptorClient
import io.riverark.ferret.core.network.PREPROD
import io.riverark.ferret.core.network.ferretHttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StablecoinPaymentTest {
    private val digest = "a".repeat(64)
    private val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
    private val usda = ChannelAsset("usda", "1".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
    private val usdcx = ChannelAsset("usdcx", "2".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
    private val usdm = ChannelAsset("usdm", "3".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
    private val catalog = AssetCatalog(listOf(ada, usda, usdcx, usdm), digest, emptyMap())
    private val privateKey = ByteArray(32) { it.toByte() }
    private val publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(privateKey).hex()
    private val tag = ProtocolTag("ab".repeat(20))
    private val usdmKeytag = ProtocolKeytag.from(publicKey, tag, 20)
    private val adaKeytag = ProtocolKeytag(publicKey + "ac".repeat(20))
    private val usdcxKeytag = ProtocolKeytag(publicKey + "ad".repeat(20))
    private val walletId = WalletId("mainnet-${"0".repeat(56)}")
    private val writer = WriterLease("e".repeat(64), 1, "f".repeat(64), "0".repeat(64), Long.MAX_VALUE)
    private val signer = object : ProtocolSigner {
        override suspend fun verificationKeyHex() = publicKey
        override suspend fun sign(message: ByteArray) = EdDSASigningProvider().sign(message, privateKey)
    }

    @Test fun nativePaymentInitializesQuotesSignsSettlesAndIsolatesSelectedChannel() = runBlocking {
        val secret = "preimage".encodeToByteArray().copyOf(32)
        val hash = AndroidProtocolCrypto.sha256(secret).hex()
        val squashBody = SquashBodyWire(0, 0, emptyList())
        val squash = SignedSquashWire(squashBody, sign(squashBody.taggedCbor(tag)))
        val initialized = ProtocolReceipt(squash, emptyList())
        val chequeBody = ChequeBodyWire(1, 1_250, ProtocolDurationWire.fromMillis(1_100), Hex32(hash))
        val unlockedBody = chequeBody.copy(latch = Hex32(secret.hex()))
        val unlocked = SignedChequeWire(unlockedBody, sign(chequeBody.taggedCbor(tag)))
        val completed = ProtocolReceipt(squash, listOf(ProtocolCheque.Unlocked(unlocked)))
        val quoteWire = ProtocolQuote(1, 1_250, 1_000, 10_000, hash, 10_000, 1_000, 200, 50, 10_000)
        val info = infoJson(digest)
        val engine = ScriptedEngine(ArrayDeque(listOf(
            Reply(info), Reply("null"), Reply(Json.encodeToString<ProtocolSquashStatus>(ProtocolSquashStatus.Complete)), Reply(Json.encodeToString(initialized)),
            Reply(info), Reply(Json.encodeToString(initialized)), Reply(Json.encodeToString(quoteWire)),
            Reply(info), Reply(Json.encodeToString<ProtocolSquashStatus>(ProtocolSquashStatus.Complete)), Reply(Json.encodeToString(completed)),
        )))
        val client = ferretHttpClient(engine)
        try {
            var stored = collection()
            val before = stored
            var operation = 0
            var writeAheads = 0
            var commits = 0
            val journal = journal({ stored }, { stored = it })
            val repository = ChannelRepository(
                WalletRepository(),
                journal,
                object : ChannelBackupProtocol {
                    override suspend fun requireVerifiedWriter(walletId: WalletId) = writer
                    override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) { writeAheads++ }
                    override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) { commits++ }
                },
                AdaptorChannelRemote({ AdaptorClient(client, PREPROD, AndroidProtocolCrypto) }, AndroidProtocolCrypto),
                newOperationId = { "00000000-0000-4000-8000-${(++operation).toString().padStart(12, '0')}" },
            )
            repository.load(walletId)
            val gateway = gateway(repository, client)

            withTimeout(5_000) { repository.initializePayment(walletId, usdmKeytag, gateway, 100) }
            val quote = gateway.quote(walletId, usdmKeytag, "lnbc1fixture", hash, 10_000)
            val result = repository.submitPayment(walletId, usdmKeytag, "lnbc1fixture", quote, gateway, 100)

            assertEquals(OperationState.COMPLETED, result.status)
            assertEquals(28_750, stored.channels.getValue(usdmKeytag.value).spendableBalance.baseUnits)
            assertEquals(before.channels.getValue(adaKeytag.value), stored.channels.getValue(adaKeytag.value))
            assertEquals(before.channels.getValue(usdcxKeytag.value), stored.channels.getValue(usdcxKeytag.value))
            assertEquals(setOf(hash), stored.paidHashes)
            assertNull(stored.channels.getValue(usdmKeytag.value).pending)
            val receipt = stored.channels.getValue(usdmKeytag.value).payments.receipts.single().receipt
            assertTrue(receipt.verified)
            assertEquals(AssetAmount(usdm, 1_000), receipt.amount)
            assertEquals(AssetAmount(usdm, 250), receipt.fee)
            assertEquals(usdmKeytag, receipt.keytag)
            completed.requireValidSignatures(publicKey, tag, AndroidProtocolCrypto)
            assertContentEquals(AndroidProtocolCrypto.sha256(unlockedBody.latch.value.hexBytes()), hash.hexBytes())
            val payRequest = Json.decodeFromString<AdaptorPayRequest>(engine.captured.single { it.path.endsWith("/ch/pay") }.body!!.decodeToString())
            assertTrue(AndroidProtocolCrypto.verify(publicKey.hexBytes(), payRequest.chequeBody.taggedCbor(tag), payRequest.signature.hexBytes()))
            val history = VaultPaymentStore(journal).history(walletId).single()
            assertEquals(listOf(AssetAmount(usdm, 1_000)), history.amounts)
            assertEquals(AssetAmount(usdm, 250), history.fee)
            assertEquals(usdmKeytag, history.channelKeytag)
            assertEquals(2, writeAheads)
            assertEquals(2, commits)
        } finally {
            client.close()
        }
    }

    @Test fun gatewayAndPredicateRejectChangedIdentityCapacityAndUnknownAssetsBeforePay() = runBlocking {
        val native = collection().channels.getValue(usdmKeytag.value)
        assertTrue(native.isEligibleForPayment(catalog))
        assertTrue(ChannelSnapshot(usdaKeytag(), usda, ChannelState.Open("usda"), spendableBalance = AssetAmount(usda, 1)).isEligibleForPayment(catalog))
        assertFalse(native.copy(payments = PaymentJournalV2(pending = PendingPayment("op", "1".repeat(64), quote(usdmKeytag), 0))).isEligibleForPayment(catalog))
        val unknown = ChannelAsset("other", "4".repeat(56), "", 6, AssetPricing.USD_PEG, digest)
        assertFalse(native.copy(asset = unknown, spendableBalance = AssetAmount(unknown, 1)).isEligibleForPayment(catalog))

        suspend fun rejects(selected: ChannelSnapshot, replies: List<Reply> = emptyList(), action: suspend (DefaultPaymentGateway) -> Unit) {
            val engine = ScriptedEngine(ArrayDeque(replies))
            val client = ferretHttpClient(engine)
            try {
                assertFailsWith<IllegalArgumentException> { action(gateway({ _, _ -> selected }, client)) }
                assertTrue(engine.captured.none { it.path.endsWith("/ch/pay") })
            } finally {
                client.close()
            }
        }
        rejects(native.copy(keytag = adaKeytag)) { it.prepareInitialization(walletId, usdmKeytag, "op", 0) }
        rejects(native.copy(asset = unknown, spendableBalance = AssetAmount(unknown, 30_000))) { it.prepareInitialization(walletId, usdmKeytag, "op", 0) }
        rejects(native.copy(spendableBalance = AssetAmount(usdm, 1_000)), listOf(Reply(infoJson(digest)))) {
            it.prepare(walletId, usdmKeytag, "op", "intent", "lnbc1fixture", quote(usdmKeytag), 100)
        }
        rejects(native, listOf(Reply(infoJson("b".repeat(64))))) {
            it.quote(walletId, usdmKeytag, "lnbc1fixture", "1".repeat(64), 10_000)
        }
    }

    private fun gateway(repository: ChannelRepository, client: io.ktor.client.HttpClient) = gateway(
        { id, keytag -> repository.snapshots.value.getValue(id).channels.getValue(keytag.value) },
        client,
    )

    private fun gateway(selected: suspend (WalletId, ProtocolKeytag) -> ChannelSnapshot, client: io.ktor.client.HttpClient) =
        DefaultPaymentGateway(
            { AdaptorClient(client, PREPROD, AndroidProtocolCrypto) }, selected, { writer }, { signer },
            { CardanoNetwork.MAINNET }, "mainnet", AndroidProtocolCrypto, catalog,
        )

    private fun collection(): ChannelCollectionV4 {
        val entries = listOf(
            ChannelSnapshot(adaKeytag, ada, ChannelState.Open("ada"), spendableBalance = AssetAmount(ada, 20_000)),
            ChannelSnapshot(usdmKeytag, usdm, ChannelState.Open("usdm"), spendableBalance = AssetAmount(usdm, 30_000)),
            ChannelSnapshot(usdcxKeytag, usdcx, ChannelState.Open("usdcx"), spendableBalance = AssetAmount(usdcx, 40_000)),
        )
        return ChannelCollectionV4(walletId = walletId, catalogDigest = digest, channels = entries.associateBy { it.keytag.value })
    }

    private fun quote(keytag: ProtocolKeytag) = PaymentQuote(
        "quote", keytag, AssetAmount(usdm, 1_000), 10_000, AssetAmount(usdm, 200), AssetAmount(usdm, 50),
        10_000, "1".repeat(64), protocolIndex = 1, relativeTimeoutMillis = 1_000, bindingVersion = 2,
    )

    private fun journal(load: () -> ChannelCollectionV4, save: (ChannelCollectionV4) -> Unit) = object : ChannelJournal {
        override suspend fun load(walletId: WalletId) = load()
        override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) = save(collection)
    }

    private fun usdaKeytag() = ProtocolKeytag(publicKey + "ae".repeat(20))
    private fun sign(message: ByteArray) = EdDSASigningProvider().sign(message, privateKey).hex()

    private fun infoJson(catalogDigest: String) =
        """{"tos":{"flat_fee":0},"channel_parameters":{"adaptor_key":"${"0".repeat(64)}","close_period":{"secs":1,"nanos":0},"tag_length":20},"tx_help":{"host_address":"addr","validator":"${"0".repeat(56)}"},"asset_catalog_digest":"$catalogDigest"}"""

    private data class Reply(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)
    private data class Captured(val method: HttpMethod, val path: String, val body: ByteArray?)

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class ScriptedEngine(private val replies: ArrayDeque<Reply>) : HttpClientEngineBase("stablecoin-payment-test") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities = setOf(HttpTimeoutCapability)
        val captured = mutableListOf<Captured>()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val body = (data.body as? OutgoingContent.ByteArrayContent)?.bytes()
            captured += Captured(data.method, data.url.encodedPath, body)
            val reply = replies.removeFirst()
            return HttpResponseData(
                reply.status, GMTDate(), Headers.Empty, HttpProtocolVersion.HTTP_1_1,
                ByteReadChannel(reply.body.encodeToByteArray()), currentCoroutineContext() + Job(),
            )
        }
    }
}

private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
