package io.riverark.ferret

import io.riverark.ferret.core.model.CardanoNetwork
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
        val operation = Json.decodeFromString<L1OperationDto>(
            """{"operation_id":"00000000-0000-4000-8000-000000000000","expected_transaction_id":"${"c".repeat(64)}","status":"accepted","depth":0}""",
        )

        assertTrue(info.assetCatalogDigest != null)
        assertTrue(utxo.ledger().scriptRefHex == MAINNET.validatorHashHex)
        assertTrue(operation.status == "accepted")
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
    fun sessionClaimSignatureBindsAdaptorIdentity() {
        val message = sessionClaimMessage(MAINNET.adaptorIdentityHex, 3, "a".repeat(64), "b".repeat(64), 42)

        assertTrue(message.decodeToString().startsWith("${MAINNET.adaptorIdentityHex}\n3\n"))
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
