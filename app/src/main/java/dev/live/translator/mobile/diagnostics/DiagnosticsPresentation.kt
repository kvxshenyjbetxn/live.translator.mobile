package dev.live.translator.mobile.diagnostics

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class ChartPoint(val x: Float, val y: Float)
/** Coordinates normalized to 0..1; y = 0 is the top of the chart. */
data class ChartGraph(val lines: List<List<ChartPoint>> = emptyList(), val points: List<ChartPoint> = emptyList())
data class DiagnosticsRow(val id: String, val label: String, val unit: String, val floor: Double, val max: Double, val value: Double?, val graph: ChartGraph, val bars: Boolean) {
    val display: String get() = value?.let { formatMeasurement(it, unit) } ?: DiagnosticTexts.NO_DATA
    val scale: String get() = "Шкала: 0–" + formatMeasurement(max, unit)
}

private const val THIN_SPACE = " "

fun formatMeasurement(value: Double, unit: String): String {
    val decimals = if (unit == "мс") 0 else 2
    val text = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    val negative = text.startsWith("-")
    val parts = text.removePrefix("-").split('.')
    val whole = parts[0].reversed().chunked(3).joinToString(THIN_SPACE).reversed()
    return (if (negative) "-" else "") + whole + (if (parts.size > 1) "," + parts[1] else "") + " " + unit
}

object DiagnosticsPresentation {
    class Measurement(val time: Double, val value: Double?)

    fun projectHistory(samples: List<Measurement>, now: Double, maximum: Double): ChartGraph {
        if (!now.isFinite() || !maximum.isFinite() || maximum <= 0) return ChartGraph()
        val lines = mutableListOf<List<ChartPoint>>()
        val points = mutableListOf<ChartPoint>()
        var segment = mutableListOf<ChartPoint>()
        var previous: Double? = null
        fun flush() { if (segment.isNotEmpty()) lines += segment; segment = mutableListOf(); previous = null }
        for (s in samples) {
            val v = s.value
            if (!s.time.isFinite() || s.time < now - 10 || s.time > now || v == null || !v.isFinite() || v < 0) { flush(); continue }
            val prev = previous
            if (prev != null && (s.time - prev > 0.4 || s.time <= prev)) flush()
            val p = ChartPoint(((s.time - now + 10) / 10).toFloat(), (1 - min(v / maximum, 1.0)).toFloat())
            points += p
            segment += p
            previous = s.time
        }
        flush()
        return ChartGraph(lines, points)
    }

    fun scaleMax(values: List<Double>, floor: Double): Double = values.fold(floor) { acc, v -> max(acc, ceil(v / floor) * floor) }

    fun rows(snapshot: DiagnosticsSnapshot): List<DiagnosticsRow> {
        val now = snapshot.elapsedSeconds
        val freshQueues = snapshot.samples.lastOrNull()?.let { now - it.elapsedSeconds <= 1.5 } == true
        val queueSamples = snapshot.samples.filter { it.elapsedSeconds >= now - 10 && it.elapsedSeconds <= now }.takeLast(101)

        val net = snapshot.networkSamples.filter { it.rttMs.isFinite() }.takeLast(10)
        val netMax = scaleMax(net.map { it.rttMs }, 1000.0)
        val bars = net.mapIndexed { i, s -> ChartPoint((10 - net.size + i + 0.5f) / 10f, (1 - min(s.rttMs / netMax, 1.0)).toFloat()) }

        fun queue(id: String, label: String, floor: Double, value: Double, pick: (DiagnosticSample) -> Double): DiagnosticsRow {
            val maximum = scaleMax(queueSamples.map(pick), floor)
            val graph = projectHistory(queueSamples.map { Measurement(it.elapsedSeconds, pick(it)) }, now, maximum)
            return DiagnosticsRow(id, label, "с", floor, maximum, if (freshQueues) value else null, graph, false)
        }
        return listOf(
            DiagnosticsRow("network", "Відгук Google", "мс", 1000.0, netMax, snapshot.networkRttMs, ChartGraph(points = bars), true),
            queue("sending", "Черга надсилання", 1.0, snapshot.sendingQueueSeconds) { it.sendingQueueSeconds },
            queue("playback", "Черга озвучення", 3.0, snapshot.playbackQueueSeconds) { it.playbackQueueSeconds },
        )
    }
}
