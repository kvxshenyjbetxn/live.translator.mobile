package dev.live.translator.mobile.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloseOnceResourceTest {
    @Test fun stopBeforePublicationReleasesLateRecordExactlyOnce() {
        var releases = 0
        val slot = CloseOnceResource<Int> { releases++ }
        slot.close()
        assertFalse(slot.publish(1))
        slot.close()
        assertTrue(releases == 1)
    }

    @Test fun repeatedStopReleasesPublishedRecordExactlyOnce() {
        var releases = 0
        val slot = CloseOnceResource<Int> { releases++ }
        assertTrue(slot.publish(1))
        slot.close()
        slot.close()
        assertTrue(releases == 1)
    }
}
