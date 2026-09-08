package io.riverark.ferret

import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.channel.sessionClaimMessage
import io.riverark.ferret.core.network.AdaptorChannelParametersDto
import io.riverark.ferret.core.network.AdaptorClosePeriodDto
import io.riverark.ferret.core.network.AdaptorInfoDto
import io.riverark.ferret.core.network.AdaptorTermsDto
import io.riverark.ferret.core.network.AdaptorTransactionHelpDto
import io.riverark.ferret.core.network.ConnectorUtxoDto
import io.riverark.ferret.core.network.L1OperationDto
import io.riverark.ferret.core.network.MAINNET
import io.riverark.ferret.core.network.HealthDto
import io.riverark.ferret.core.network.NetworkDto
import io.riverark.ferret.core.network.PREPROD
import io.riverark.ferret.core.network.L1SubmitRequest
import io.riverark.ferret.core.network.SessionClaimResponse
import io.riverark.ferret.core.network.SubmitResponse
import io.riverark.ferret.core.network.requireBoundedResponse
import io.riverark.ferret.core.network.decodeBoundedJson
import io.riverark.ferret.core.network.RefreshCoordinator
import io.riverark.ferret.core.security.ForegroundLockPolicy
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshCoordinatorTest {
    private val profile = WalletProfile(
        WalletId("preprod-${"0".repeat(56)}"),
        "Wallet",
        CardanoNetwork.PREPROD,
        "addr_test1wallet",
        "stake_test1wallet",
    )

    @Test
    fun foregroundLockUsesExactFiveMinuteBoundary() {
        var now = 0L
        val policy = ForegroundLockPolicy({ now })

        policy.backgrounded()
        now = ForegroundLockPolicy.FIVE_MINUTES_MILLIS - 1_000
        assertFalse(policy.shouldLock())
        assertFalse(policy.foregrounded())

        policy.backgrounded()
        now += ForegroundLockPolicy.FIVE_MINUTES_MILLIS
        assertTrue(policy.shouldLock())
        assertTrue(policy.foregrounded())
    }
    @Test
    fun adaptorInfoMatchesLiveStrictSchema() {
        val info = Json.decodeFromString<AdaptorInfoDto>(
            """{"tos":{"flat_fee":14},"channel_parameters":{"adaptor_key":"${PREPROD.adaptorIdentityHex}","close_period":{"secs":300,"nanos":0},"tag_length":32},"tx_help":{"host_address":"${PREPROD.scriptDeploymentAddress}","validator":"${PREPROD.validatorHashHex}"}}""",
        )

        assertTrue(info.channelParameters.adaptorKeyHex == PREPROD.adaptorIdentityHex)
    }

    @Test
    fun mainnetContractsMatchDeployedStrictSchema() = runBlocking {
        val info = Json.decodeFromString<AdaptorInfoDto>(
            """{"tos":{"flat_fee":1414},"channel_parameters":{"adaptor_key":"${MAINNET.adaptorIdentityHex}","close_period":{"secs":1800,"nanos":0},"tag_length":32},"tx_help":{"host_address":"${MAINNET.scriptDeploymentAddress}","validator":"${MAINNET.validatorHashHex}"},"asset_catalog_digest":"${"a".repeat(64)}"}""",
        )
        val utxo = Json.decodeFromString<ConnectorUtxoDto>(
            """{"transaction_id":"${"b".repeat(64)}","output_index":0,"address":"${MAINNET.scriptDeploymentAddress}","value":[{"unit":"lovelace","quantity":"1"}],"reference_script_hash":"${MAINNET.validatorHashHex}","reference_script_version":3,"reference_script":"00"}""",
        )
        Json.decodeFromString<L1OperationDto>(
            """{"operation_id":"00000000-0000-4000-8000-000000000000","expected_transaction_id":"${"c".repeat(64)}","transaction_id":"${"c".repeat(64)}","status":"accepted","depth":0}""",
        )

        assertTrue(info.assetCatalogDigest != null)
        assertTrue(utxo.ledger().scriptRefHex == "00")
        assertTrue(utxo.ledger().scriptRefHashHex == MAINNET.validatorHashHex)
        RefreshCoordinator(
            MAINNET,
            { HealthDto("ok") },
            { NetworkDto("mainnet") },
            { info },
        ).validate(
            WalletProfile(
                WalletId("mainnet-${"0".repeat(56)}"),
                "Mainnet",
                CardanoNetwork.MAINNET,
                MAINNET.scriptDeploymentAddress,
                "stake1wallet",
            ),
        )
    }

    @Test
    fun connectorUtxoProtectionMetadataFailsClosed() {
        val address = "addr1wallet"
        val prefix = """{"transaction_id":"${"a".repeat(64)}","output_index":0,"address":"$address","value":[{"unit":"lovelace","quantity":"1"}]"""
        fun decode(extra: String = "") =
            Json.decodeFromString<ConnectorUtxoDto>("$prefix$extra}").ledger()

        assertTrue(decode().isSpendableBy(address))
        assertTrue(decode(""","datum_hash":null""").isSpendableBy(address))
        listOf("a", "A".repeat(64)).forEach { datumHash ->
            assertFailsWith<IllegalArgumentException> { decode(""","datum_hash":"$datumHash"""") }
        }
        assertFailsWith<IllegalArgumentException> { decode(""","reference_script_version":3""") }
        assertFailsWith<IllegalArgumentException> { decode(""","reference_script":"00"""") }
        assertFalse(decode(""","reference_script_hash":"${"b".repeat(56)}"""").isSpendableBy(address))
        assertFailsWith<IllegalArgumentException> {
            LedgerUtxo("a".repeat(64), 0, address, Lovelace(1), datumHashHex = "bad")
        }
    }

    @Test
    fun operationResponsesRequireConsistentFinalityEvidence() {
        val identity = """"operation_id":"00000000-0000-4000-8000-000000000001","expected_transaction_id":"${"c".repeat(64)}""""
        fun decode(status: String, depth: String?, transaction: Boolean = true, extra: String = ""): L1OperationDto =
            decodeBoundedJson(
                """{$identity,"status":"$status"${depth?.let { ""","depth":$it""" } ?: ""}${if (transaction) ""","transaction_id":"${"c".repeat(64)}"""" else ""}$extra}"""
                    .encodeToByteArray(),
            )

        listOf("pending" to 0L, "rejected" to 0L, "accepted" to 0L, "accepted" to 4L,
            "confirmed" to 5L, "confirmed" to 2_159L, "settled" to 2_160L, "settled" to Long.MAX_VALUE)
            .forEach { (status, depth) -> decode(status, depth.toString()) }
        decode("pending", "0", transaction = false)
        decode("rejected", "0", transaction = false)
        listOf(null, "null").forEach { depth ->
            assertFailsWith<kotlinx.serialization.SerializationException> { decode("pending", depth) }
        }
        listOf("pending" to -1, "pending" to 1, "rejected" to 1, "accepted" to 5,
            "confirmed" to 4, "confirmed" to 2_160, "settled" to 2_159, "unknown" to 0)
            .forEach { (status, depth) ->
                assertFailsWith<IllegalArgumentException> { decode(status, depth.toString()) }
            }
        listOf("accepted" to 0, "confirmed" to 5, "settled" to 2_160).forEach { (status, depth) ->
            assertFailsWith<IllegalArgumentException> { decode(status, depth.toString(), transaction = false) }
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            decode("accepted", "0", extra = ""","unexpected":true""")
        }
    }

    @Test
    fun sessionClaimSignatureBindsAdaptorIdentity() {
        val message = sessionClaimMessage(MAINNET.adaptorIdentityHex, 3, "a".repeat(64), "b".repeat(64), 42)

        assertTrue(message.decodeToString().startsWith("${MAINNET.adaptorIdentityHex}\n3\n"))
    }
    @Test
    fun networkResponsesAreStrictAndByteBounded() {
        assertFailsWith<IllegalArgumentException> { SubmitResponse("not-a-transaction-id") }
        assertFailsWith<IllegalArgumentException> { SessionClaimResponse("bad lease", 1) }
        assertFailsWith<IllegalArgumentException> { AdaptorTermsDto(-1) }
        assertFailsWith<IllegalArgumentException> {
            AdaptorChannelParametersDto("a".repeat(64), AdaptorClosePeriodDto(0, 0), 32)
        }
        assertFailsWith<IllegalArgumentException> {
            L1SubmitRequest(
                "00000000-0000-4000-8000-000000000000",
                "a".repeat(64),
                "aa".repeat(524_289),
            )
        }
        assertFailsWith<IllegalArgumentException> { requireBoundedResponse(ByteArray(1_048_577)) }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            decodeBoundedJson<HealthDto>("""{"status":"ok","unexpected":true}""".encodeToByteArray())
        }
    }



    @Test
    fun deploymentMustMatchConnectorAndAdaptor() = runBlocking {
        validCoordinator().validate(profile)

        assertFailsWith<IllegalArgumentException> {
            validCoordinator(network = "mainnet").validate(profile)
        }
        assertFailsWith<IllegalArgumentException> {
            validCoordinator(identity = "1".repeat(64)).validate(profile)
        }
        Unit
    }

    @Test
    fun cancellingActiveWorkStopsRefresh() = runBlocking {
        val coordinator = validCoordinator()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val refresh = launch {
            coordinator.refresh {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }

        started.await()
        coordinator.cancelActiveWork()
        stopped.await()
        refresh.join()

        assertTrue(refresh.isCancelled)
    }

    private fun validCoordinator(
        network: String = "preprod",
        identity: String = PREPROD.adaptorIdentityHex,
    ) = RefreshCoordinator(
        PREPROD,
        { HealthDto("ok") },
        { NetworkDto(network) },
        {
            AdaptorInfoDto(
                AdaptorTermsDto(14),
                AdaptorChannelParametersDto(identity, AdaptorClosePeriodDto(300, 0), 32),
                AdaptorTransactionHelpDto(PREPROD.scriptDeploymentAddress, PREPROD.validatorHashHex),
            )
        },
    )
}
