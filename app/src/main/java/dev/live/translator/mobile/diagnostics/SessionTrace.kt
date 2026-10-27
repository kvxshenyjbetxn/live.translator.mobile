package dev.live.translator.mobile.diagnostics

import dev.live.translator.mobile.gemini.ProtocolRejectReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionEvent {
    START_REQUESTED, PROJECTION_READY, TRANSPORT_READY, PLAYER_STARTED, CAPTURE_STARTED,
    CAPTURE_SAMPLES, INPUT_QUEUED, INPUT_SENT, AUDIO_RECEIVED, PLAYBACK_QUEUED,
    CAPTURE_START_FAILURE, CAPTURE_READ_FAILURE, PLAYBACK_ENQUEUE_FAILURE, PLAYBACK_WRITE_FAILURE,
    INPUT_QUEUE_FULL, PROJECTION_REVOKED, USER_STOP, SERVICE_DESTROYED,
}

enum class TerminalCause {
    CREDENTIALS, QUOTA, SERVICE_UNAVAILABLE, CLOSED, PROTOCOL, NETWORK, TIMEOUT,
    CANCELLED, INCOMING_QUEUE_FULL, BACKPRESSURE, PLAYBACK, OTHER, PROJECTION_REVOKED, USER_STOP,
}

/** One event of each kind per session; no payloads, identifiers, or exception messages. */
class SessionTrace(private val emit: (String) -> Unit = { DiagnosticLog().event(it) }) {
    private val events = ConcurrentHashMap.newKeySet<SessionEvent>()
    private val terminal = AtomicBoolean(false)
    private val protocolReason = AtomicBoolean(false)

    fun event(event: SessionEvent) {
        if (events.add(event)) emit("stage.${event.name.lowercase()}")
    }

    fun protocolReject(reason: ProtocolRejectReason) {
        if (protocolReason.compareAndSet(false, true)) emit("protocol.${reason.name.lowercase()}")
    }

    fun terminal(cause: TerminalCause) {
        if (terminal.compareAndSet(false, true)) emit("terminal.${cause.name.lowercase()}")
    }
}
