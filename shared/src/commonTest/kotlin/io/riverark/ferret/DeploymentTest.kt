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
        assertEquals("https://konduit.crustypants.com", MAINNET.adaptor.value)
        assertEquals("https://konduit-cardano.crustypants.com", MAINNET.connector.value)
        assertEquals("57da4bfac4a2d097c8b2f391ea5a0772b6db8a28bfbadd5cd72fb5b138d5a07c", MAINNET.adaptorIdentityHex)
        assertEquals("addr1wxcrrmk4g6ta93942evluyw6c2ffy2xanpl6lc43tyzvupqswlfa5", MAINNET.validatorAddress)
        assertEquals("b031eed54697d2c4b55659fe11dac2929228dd987fafe2b15904ce04", MAINNET.validatorHashHex)
    }
}
