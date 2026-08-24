package io.riverark.ferret

import io.riverark.ferret.core.security.FiveMinuteAppLifecycle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityTest {
    @Test fun locksAfterFiveContinuousBackgroundMinutes() {
        val lifecycle = FiveMinuteAppLifecycle()
        lifecycle.onBackground(1_000)
        assertFalse(lifecycle.shouldLock(300_999))
        assertTrue(lifecycle.shouldLock(301_000))
    }
}
