package dev.live.translator.mobile.diagnostics

import dev.live.translator.mobile.diagnostics.DiagnosticsPresentation.Measurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsPresentationTest {
    private val thin = " "

    @Test fun formatsLikeUkUa() {
        assertEquals("120 мс", formatMeasurement(120.4, "мс"))
        assertEquals("1${thin}000 мс", formatMeasurement(1000.0, "мс"))
        assertEquals("0,25 с", formatMeasurement(0.25, "с"))
        assertEquals("3 с", formatMeasurement(3.0, "с"))
        assertEquals("1,5 с", formatMeasurement(1.504, "с"))
        assertEquals("12${thin}345 мс", formatMeasurement(12345.0, "мс"))
    }

    @Test fun scaleGrowsInFloorSteps() {
        assertEquals(1000.0, DiagnosticsPresentation.scaleMax(listOf(10.0, 900.0), 1000.0))
        assertEquals(2000.0, DiagnosticsPresentation.scaleMax(listOf(1001.0), 1000.0))
        assertEquals(3.0, DiagnosticsPresentation.scaleMax(emptyList(), 3.0))
        assertEquals(6.0, DiagnosticsPresentation.scaleMax(listOf(3.5), 3.0))
    }

    @Test fun projectHistoryBreaksLineOnGapsAndDropsOutOfWindowSamples() {
        val samples = listOf(Measurement(0.0, 1.0), Measurement(5.0, 0.5), Measurement(5.1, 0.5), Measurement(5.2, 0.0), Measurement(5.7, 1.0), Measurement(20.0, 1.0), Measurement(9.0, null))
        val graph = DiagnosticsPresentation.projectHistory(samples, 10.0, 1.0)
        assertEquals(listOf(1, 3, 1), graph.lines.map { it.size })
        assertEquals(5, graph.points.size)
        assertEquals(0.5f, graph.lines[1][0].x)
        assertEquals(0.5f, graph.lines[1][0].y)
        assertEquals(1f, graph.lines[1][2].y)
        assertTrue(DiagnosticsPresentation.projectHistory(samples, 10.0, 0.0).lines.isEmpty())
    }

    @Test fun rowsForIdleAndRunning() {
        val idle = DiagnosticsPresentation.rows(DiagnosticsSnapshot())
        assertEquals(listOf("Немає даних"), idle.map { it.display }.distinct())
        assertEquals(listOf("Шкала: 0–1${thin}000 мс", "Шкала: 0–1 с", "Шкала: 0–3 с"), idle.map { it.scale })
        val snap = DiagnosticsSnapshot(
            elapsedSeconds = 10.0,
            samples = listOf(DiagnosticSample(9.9, 0.25, 3.5), DiagnosticSample(10.0, 0.25, 3.5)),
            networkSamples = listOf(NetworkSample(8.0, 120.0), NetworkSample(9.0, 1200.0)),
            networkRttMs = 1200.0, sendingQueueSeconds = 0.25, playbackQueueSeconds = 3.5,
        )
        val rows = DiagnosticsPresentation.rows(snap)
        assertEquals("1${thin}200 мс", rows[0].display)
        assertEquals("Шкала: 0–2${thin}000 мс", rows[0].scale)
        assertEquals(2, rows[0].graph.points.size)
        assertEquals(0.95f, rows[0].graph.points[1].x, 1e-6f)
        assertEquals("0,25 с", rows[1].display)
        assertEquals("Шкала: 0–6 с", rows[2].scale)
    }

    @Test fun staleQueuesShowNoData() {
        val snap = DiagnosticsSnapshot(elapsedSeconds = 20.0, samples = listOf(DiagnosticSample(15.0, 0.2, 0.2)), sendingQueueSeconds = 0.2)
        assertEquals("Немає даних", DiagnosticsPresentation.rows(snap)[1].display)
    }
}
