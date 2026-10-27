package dev.live.translator.mobile.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatencyDiagnosticsTest {
    @Test fun boundedHistoryExpiresWithoutFabricatingNetworkSamples() {
        val d = LatencyDiagnostics()
        assertNull(d.snapshot(0.0).networkRttMs)
        d.recordRtt(0.0, 0.08)
        for (i in 0..2000) d.observe(i / 100.0, 0.05, 0.1)
        val snapshot = d.snapshot(20.0)
        assertTrue(snapshot.samples.size <= 101)
        assertTrue(snapshot.samples.all { it.elapsedSeconds >= 10.0 })
        assertEquals(1, snapshot.networkSamples.size)
        assertNull(snapshot.networkRttMs)
        assertTrue(snapshot.issues.isEmpty())
        d.recordRtt(20.0, 0.1)
        d.observe(20.1, 0.0, 0.0)
        assertEquals(2, d.snapshot(20.1).networkSamples.size)
        assertTrue(LatencyDiagnostics().snapshot(0.0).samples.isEmpty())
    }

    @Test fun transientQueuesDoNotWarnButSustainedSimultaneousQueuesDo() {
        val d = LatencyDiagnostics()
        d.observe(0.0, 0.6, 2.5)
        assertTrue(d.snapshot(0.0).issues.isEmpty())
        d.observe(1.0, 0.0, 0.0)
        d.observe(2.0, 0.6, 2.5)
        d.observe(3.0, 0.6, 2.5)
        d.observe(4.0, 0.6, 2.5)
        val issues = d.snapshot(4.0).issues
        assertTrue(DiagnosticIssue.SENDING_QUEUE in issues)
        assertTrue(DiagnosticIssue.PLAYBACK_QUEUE in issues)
        d.observe(4.2, 0.0, 0.0)
        assertTrue(d.snapshot(4.2).issues.isEmpty())
    }

    @Test fun networkWarningNeedsThreeFreshRepliesAndExpires() {
        val d = LatencyDiagnostics()
        d.recordRtt(0.0, 0.1)
        d.recordRtt(5.0, 0.9)
        assertTrue(d.snapshot(5.0).issues.isEmpty())
        d.recordRtt(10.0, 0.2)
        assertTrue(DiagnosticIssue.NETWORK_UNSTABLE in d.snapshot(10.0).issues)
        assertFalse(DiagnosticIssue.NETWORK_UNSTABLE in d.snapshot(10.1).issues)
        assertNull(d.snapshot(21.0).networkRttMs)
    }

    @Test fun prunedHistoryCannotEstablishContinuityAfterUnobservedGap() {
        for (pruneViaRtt in listOf(false, true)) {
            val d = LatencyDiagnostics()
            d.observe(0.0, 0.8, 3.0)
            if (pruneViaRtt) d.recordRtt(11.0, 0.08) else assertTrue(d.snapshot(11.0).samples.isEmpty())
            d.observe(11.1, 0.8, 3.0)
            assertTrue(d.snapshot(11.1).issues.isEmpty(), "old warnings survived pruning (RTT=$pruneViaRtt)")
            d.observe(12.1, 0.8, 3.0)
            d.observe(13.1, 0.8, 3.0)
            val issues = d.snapshot(13.1).issues
            assertTrue(DiagnosticIssue.SENDING_QUEUE in issues)
            assertTrue(DiagnosticIssue.PLAYBACK_QUEUE in issues)
        }
    }

    @Test fun pingHistoryRetainsTenRealMeasurementsWithoutStaleCurrentValues() {
        val d = LatencyDiagnostics()
        for (i in 0 until 15) d.recordRtt(i * 4.0, 0.1)
        val s = d.snapshot(100.0)
        assertEquals(10, s.networkSamples.size)
        assertEquals(20.0, s.networkSamples[0].elapsedSeconds)
        assertEquals(56.0, s.networkSamples[9].elapsedSeconds)
        assertNull(s.networkRttMs)
        assertTrue(s.issues.isEmpty())
    }

    @Test fun samplingIntervalAndGapRules() {
        val d = LatencyDiagnostics()
        d.observe(0.0, 0.0, 0.0)
        d.observe(0.05, 0.0, 0.0)
        assertEquals(1, d.snapshot(0.05).samples.size)
        d.observe(0.1, 0.0, 0.0)
        assertEquals(2, d.snapshot(0.1).samples.size)
        d.observe(5.0, 0.9, 0.0)
        d.observe(6.9, 0.9, 0.0)
        assertTrue(d.snapshot(6.9).issues.isEmpty())
        d.observe(7.0, 0.9, 0.0)
        d.observe(8.0, 0.9, 0.0)
        d.observe(9.0, 0.9, 0.0)
        assertTrue(DiagnosticIssue.SENDING_QUEUE in d.snapshot(9.0).issues)
        assertTrue(d.snapshot(11.0).issues.isEmpty())
    }

    @Test fun messagesFollowDesktopTexts() {
        val empty = DiagnosticsSnapshot()
        assertEquals(listOf("Збираємо вимірювання. Пауза в озвучці сама по собі не означає несправність."), DiagnosticTexts.diagnosticMessages(empty))
        val quiet = empty.copy(samples = listOf(DiagnosticSample(0.0, 0.0, 0.0), DiagnosticSample(2.0, 0.0, 0.0)))
        assertEquals(listOf("Значного накопичення в локальних чергах не виявлено. Ці вимірювання не визначають час роботи моделі."), DiagnosticTexts.diagnosticMessages(quiet))
        val all = DiagnosticTexts.diagnosticMessages(quiet.copy(issues = DiagnosticIssue.entries))
        assertEquals(3, all.size)
        assertTrue(all[0].startsWith("Відгук з’єднання з Google високий"))
        assertTrue(all[1].startsWith("Захоплене аудіо накопичується"))
        assertTrue(all[2].startsWith("Отримана озвучка накопичується"))
    }
}
