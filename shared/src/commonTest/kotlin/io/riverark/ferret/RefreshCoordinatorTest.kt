package io.riverark.ferret

import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.network.AdaptorChannelParametersDto
import io.riverark.ferret.core.network.AdaptorClosePeriodDto
import io.riverark.ferret.core.network.AdaptorInfoDto
import io.riverark.ferret.core.network.AdaptorTermsDto
import io.riverark.ferret.core.network.AdaptorTransactionHelpDto
import io.riverark.ferret.core.network.HealthDto
import io.riverark.ferret.core.network.NetworkDto
import io.riverark.ferret.core.network.PREPROD
import io.riverark.ferret.core.network.RefreshCoordinator
import io.riverark.ferret.core.security.ForegroundLockPolicy
import kotlinx.serialization.json.Json
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
