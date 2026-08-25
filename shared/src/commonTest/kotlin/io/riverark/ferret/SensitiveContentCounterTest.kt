package io.riverark.ferret

import io.riverark.ferret.core.security.SensitiveContentCounter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveContentCounterTest {
    @Test
    fun remainsProtectedAcrossOverlappingSensitiveRoutes() {
        val counter = SensitiveContentCounter()

        assertTrue(counter.update(true))
        assertTrue(counter.update(true))
        assertTrue(counter.update(false))
        assertFalse(counter.update(false))
        assertFalse(counter.update(false))
    }
}
