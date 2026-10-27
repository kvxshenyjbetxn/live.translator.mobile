package dev.live.translator.mobile.diagnostics

import kotlin.math.max

data class DiagnosticSample(val elapsedSeconds: Double, val sendingQueueSeconds: Double, val playbackQueueSeconds: Double)
data class NetworkSample(val elapsedSeconds: Double, val rttMs: Double)
enum class DiagnosticIssue { NETWORK_UNSTABLE, SENDING_QUEUE, PLAYBACK_QUEUE }

data class DiagnosticsSnapshot(
    val elapsedSeconds: Double = 0.0,
    val samples: List<DiagnosticSample> = emptyList(),
    val networkSamples: List<NetworkSample> = emptyList(),
    val networkRttMs: Double? = null,
    val sendingQueueSeconds: Double = 0.0,
    val playbackQueueSeconds: Double = 0.0,
    val issues: List<DiagnosticIssue> = emptyList(),
)

class LatencyDiagnostics {
    private val samples = ArrayDeque<DiagnosticSample>()
    private val network = ArrayDeque<NetworkSample>()
    private var sendingSince: Double? = null
    private var playbackSince: Double? = null

    @Synchronized
    fun observe(now: Double, sending: Double, playback: Double) {
        val last = samples.lastOrNull()
        if (last != null && now - last.elapsedSeconds < MIN_INTERVAL) return
        // A sampling gap is not evidence that a queue remained high throughout it.
        if (last != null && now - last.elapsedSeconds > MAX_GAP) { sendingSince = null; playbackSince = null }
        sendingSince = track(sendingSince, now, sending >= SENDING_HIGH)
        playbackSince = track(playbackSince, now, playback >= PLAYBACK_HIGH)
        samples.addLast(DiagnosticSample(now, sending, playback))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        prune(now)
    }

    private fun track(since: Double?, now: Double, high: Boolean): Double? = if (high) since ?: now else null

    @Synchronized
    fun recordRtt(now: Double, rttSeconds: Double) {
        network.addLast(NetworkSample(now, rttSeconds * 1000.0))
        while (network.size > MAX_NETWORK) network.removeFirst()
        prune(now)
    }

    private fun prune(now: Double) {
        val cutoff = now - WINDOW
        while (samples.firstOrNull()?.let { it.elapsedSeconds < cutoff } == true) samples.removeFirst()
        if (samples.isEmpty()) { sendingSince = null; playbackSince = null }
    }

    @Synchronized
    fun snapshot(now: Double): DiagnosticsSnapshot {
        prune(now)
        val issues = mutableListOf<DiagnosticIssue>()
        val fresh = network.filter { now - it.elapsedSeconds <= WINDOW }
        if (fresh.size >= 3) {
            val rtts = fresh.map { it.rttMs }
            val spread = rtts.max() - rtts.min()
            if (rtts.average() >= 500.0 || spread >= 250.0) issues += DiagnosticIssue.NETWORK_UNSTABLE
        }
        val latest = samples.lastOrNull()
        if (latest != null && now - latest.elapsedSeconds <= MAX_GAP) {
            if (sendingSince?.let { max(now - it, 0.0) >= SUSTAINED } == true) issues += DiagnosticIssue.SENDING_QUEUE
            if (playbackSince?.let { max(now - it, 0.0) >= SUSTAINED } == true) issues += DiagnosticIssue.PLAYBACK_QUEUE
        }
        return DiagnosticsSnapshot(now, samples.toList(), network.toList(), fresh.lastOrNull()?.rttMs, latest?.sendingQueueSeconds ?: 0.0, latest?.playbackQueueSeconds ?: 0.0, issues)
    }

    companion object {
        const val WINDOW = 10.0
        private const val MIN_INTERVAL = 0.0999
        private const val MAX_GAP = 1.5
        private const val MAX_SAMPLES = 101
        private const val MAX_NETWORK = 10
        private const val SENDING_HIGH = 0.5
        private const val PLAYBACK_HIGH = 2.0
        private const val SUSTAINED = 2.0
    }
}

object DiagnosticTexts {
    const val NETWORK_UNKNOWN = "Відгук з’єднання ще невідомий: чекаємо відповіді на HTTPS-запит. Її відсутність не доводить проблему з інтернетом."
    const val NO_DATA = "Немає даних"
    private const val COLLECTING = "Збираємо вимірювання. Пауза в озвучці сама по собі не означає несправність."
    private const val ALL_CLEAR = "Значного накопичення в локальних чергах не виявлено. Ці вимірювання не визначають час роботи моделі."

    fun diagnosticMessages(snapshot: DiagnosticsSnapshot): List<String> {
        val issues = snapshot.issues.map {
            when (it) {
                DiagnosticIssue.NETWORK_UNSTABLE -> "Відгук з’єднання з Google високий або нестабільний. Це може додавати затримку, але не доводить низьку швидкість інтернету."
                DiagnosticIssue.SENDING_QUEUE -> "Захоплене аудіо накопичується: надсилання не встигає за джерелом. Причиною може бути з’єднання або навантаження програми."
                DiagnosticIssue.PLAYBACK_QUEUE -> "Отримана озвучка накопичується перед відтворенням. Програма плавно прискорює її, щоб зменшити чергу."
            }
        }
        if (issues.isNotEmpty()) return issues
        val s = snapshot.samples
        if (s.size < 2 || s.last().elapsedSeconds - s.first().elapsedSeconds < 2) return listOf(COLLECTING)
        return listOf(ALL_CLEAR)
    }
}
