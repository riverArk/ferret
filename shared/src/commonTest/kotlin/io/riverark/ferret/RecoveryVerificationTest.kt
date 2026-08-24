package io.riverark.ferret

import io.riverark.ferret.feature.wallet.recoveryVerificationIndexes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryVerificationTest {
    @Test
    fun selectsThreeDistinctRandomWordPositions() {
        val selections = (0 until 20).map { recoveryVerificationIndexes(24, Random(it)) }

        selections.forEach { indexes ->
            assertEquals(3, indexes.size)
            assertEquals(indexes.sorted(), indexes)
            assertEquals(3, indexes.distinct().size)
            assertTrue(indexes.all { it in 0 until 24 })
        }
        assertTrue(selections.distinct().size > 1)
    }
}
