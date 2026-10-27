package dev.live.translator.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionFlowTest {
    @Test fun staleConsentCannotBeConsumedAfterInvalidation() {
        val gate = ConsentRequestGate()
        val request = gate.begin("com.video")!!
        assertTrue(gate.isCurrent(request))
        gate.invalidate()
        assertFalse(gate.isCurrent(request))
        assertNull(gate.consume())
    }

    @Test fun duplicateBeginIsRejectedAndKeepsOriginalRequest() {
        val gate = ConsentRequestGate()
        val first = gate.begin("com.video")!!
        val second = gate.begin("com.music")
        assertTrue(gate.isCurrent(first))
        assertNull(second)
        assertEquals("com.video", gate.consume()?.packageName)
    }
}
