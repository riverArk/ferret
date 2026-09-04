package io.riverark.ferret

import io.riverark.ferret.core.model.DiagnosticCode
import io.riverark.ferret.core.security.FiveMinuteAppLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityTest {
    @Test fun locksAfterFiveContinuousBackgroundMinutes() {
        val lifecycle = FiveMinuteAppLifecycle()
        lifecycle.onBackground(1_000)
        assertFalse(lifecycle.shouldLock(300_999))
        assertTrue(lifecycle.shouldLock(301_000))
    }

    @Test fun exposesStableBoundedDiagnosticCodes() {
        assertEquals(
            listOf("FRT-001", "FRT-002", "FRT-003", "FRT-004", "FRT-005", "FRT-006"),
            DiagnosticCode.entries.map(DiagnosticCode::value),
        )
    }
}
