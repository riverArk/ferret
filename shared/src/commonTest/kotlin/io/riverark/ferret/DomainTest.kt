package io.riverark.ferret

import io.riverark.ferret.core.model.Lovelace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainTest {
    @Test fun lovelaceArithmeticIsChecked() {
        assertEquals(Lovelace(5), Lovelace(2) + Lovelace(3))
        assertFailsWith<IllegalArgumentException> { Lovelace(2) - Lovelace(3) }
        assertFailsWith<IllegalArgumentException> { Lovelace(Long.MAX_VALUE) + Lovelace(1) }
    }
}
