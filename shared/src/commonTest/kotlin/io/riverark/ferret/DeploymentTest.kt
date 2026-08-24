package io.riverark.ferret

import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.network.MAINNET
import io.riverark.ferret.core.network.PREPROD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentTest {
    @Test fun deploymentsAreNetworkBound() {
        assertEquals(CardanoNetwork.PREPROD, PREPROD.network)
        assertTrue(PREPROD.scriptDeploymentAddress.startsWith("addr_test1"))
        assertEquals(CardanoNetwork.MAINNET, MAINNET.network)
        assertTrue(MAINNET.scriptDeploymentAddress.startsWith("addr1"))
    }
}
