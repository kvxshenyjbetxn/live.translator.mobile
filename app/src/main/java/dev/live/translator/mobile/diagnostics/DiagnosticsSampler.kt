package dev.live.translator.mobile.diagnostics

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DiagnosticsSampler(
    private val sendingSeconds: () -> Double,
    private val playbackSeconds: () -> Double,
    private val probeRttMs: () -> Double?,
    private val wantsSnapshots: () -> Boolean,
    private val onSnapshot: (DiagnosticsSnapshot) -> Unit,
    private val diagnostics: LatencyDiagnostics = LatencyDiagnostics(),
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val origin = nanoClock()
    @Volatile private var stopped = false
    private val executor = ScheduledThreadPoolExecutor(3) { Thread(it, "live-translator-diagnostics").apply { isDaemon = true } }

    private fun now() = (nanoClock() - origin) / 1e9
    private fun guarded(block: () -> Unit) = Runnable { if (!stopped) try { block() } catch (_: Exception) { } }

    fun start() {
        executor.scheduleAtFixedRate(guarded { diagnostics.observe(now(), sendingSeconds(), playbackSeconds()) }, 0, 100, TimeUnit.MILLISECONDS)
        executor.scheduleAtFixedRate(guarded { if (wantsSnapshots()) onSnapshot(diagnostics.snapshot(now())) }, 250, 250, TimeUnit.MILLISECONDS)
        var warmedUp = false
        // The first request may pay for DNS/TLS on a fresh pooled connection, so it only warms the connection and is not recorded.
        executor.scheduleWithFixedDelay(guarded {
            val rtt = probeRttMs()
            if (!warmedUp) warmedUp = true
            else if (rtt != null && !stopped) diagnostics.recordRtt(now(), rtt / 1000.0)
        }, 0, 4, TimeUnit.SECONDS)
    }

    fun stop() {
        stopped = true
        executor.shutdownNow()
    }
}
