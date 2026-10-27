package dev.live.translator.mobile.diagnostics

import dev.live.translator.mobile.gemini.ProtocolRejectReason
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTraceTest {
    @Test fun firstProtocolReasonOnlyIsLogged() {
        val output = mutableListOf<String>()
        val trace = SessionTrace { output.add(it) }
        trace.protocolReject(ProtocolRejectReason.INVALID_JSON)
        trace.protocolReject(ProtocolRejectReason.INVALID_MIME)
        assertEquals(listOf("protocol.invalid_json"), output)
    }

    @Test fun eventsAreEnumOnlyAndEmittedOnceAcrossThreads() {
        val output = Collections.synchronizedList(mutableListOf<String>())
        val trace = SessionTrace { output.add(it) }
        val threads = (1..20).map {
            thread {
                trace.event(SessionEvent.CAPTURE_SAMPLES)
                trace.terminal(TerminalCause.PLAYBACK)
            }
        }
        threads.forEach { it.join() }
        trace.terminal(TerminalCause.NETWORK)
        assertEquals(setOf("stage.capture_samples", "terminal.playback"), output.toSet())
        assertEquals(2, output.size)
    }
}
