package dev.live.translator.mobile.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionGenerationGateTest {
    @Test fun stopInvalidatesPendingStartupAndRejectsDuplicate() {
        val gate = SessionGenerationGate()
        val first = gate.begin()
        assertNotNull(first)
        assertNull(gate.begin())
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        val second = gate.begin()
        assertTrue(second != first)
    }

    @Test fun completionOnlyAcceptsCurrentGeneration() {
        val gate = SessionGenerationGate()
        val first = gate.begin()!!
        gate.invalidate()
        assertFalse(gate.complete(first))
        val second = gate.begin()!!
        assertTrue(gate.complete(second))
        assertNotNull(gate.begin())
    }
}
