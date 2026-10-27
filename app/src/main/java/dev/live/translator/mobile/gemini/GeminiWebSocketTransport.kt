package dev.live.translator.mobile.gemini

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class CancellationSignal(private val onProtocolReject: (ProtocolRejectReason) -> Unit = {}) {
    fun protocolReject(reason: ProtocolRejectReason) = onProtocolReject(reason)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get()
}

interface GeminiLiveTransport {
    suspend fun checkConnection(apiKey: String): ConnectionReport
    suspend fun open(apiKey: String, targetLanguage: String = "uk"): LiveConnection
    suspend fun open(apiKey: String, targetLanguage: String, cancellation: CancellationSignal): LiveConnection =
        open(apiKey, targetLanguage)
}

data class ConnectionReport(val model: String, val elapsedMs: Long)

interface LiveConnection : AutoCloseable {
    fun sendPcm16k(packet: ShortArray)
    fun receive(): TranslationAudioEvent
    fun cancel()
    override fun close() = cancel()
}

/** Bounded incoming queue with a reserved terminal outcome: overflow is never silently dropped. */
class IncomingEventQueue(private val capacity: Int = 32) {
    private val queue = ArrayBlockingQueue<Any>(capacity)
    @Volatile private var terminal = false

    fun offer(event: Any): Boolean {
        synchronized(this) {
            if (terminal) return false
            if (queue.offer(event)) return true
            terminal = true
            queue.clear()
            queue.offer(ProtocolFailure.QueueOverflow)
            return false
        }
    }

    fun terminate(failure: ProtocolFailure) {
        synchronized(this) {
            if (terminal) return
            terminal = true
            queue.clear()
            queue.offer(failure)
        }
    }

    fun take(): Any = queue.take()
    fun isTerminal(): Boolean = terminal
}

/** Tracks a small byte/time budget for OkHttp's asynchronous outbound queue. */
class OutboundBudget(
    private val maxBytes: Long = 64 * 1024,
    private val maxAgeMs: Long = 2_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var previousObservedBytes = 0L
    private var lastProgressAt = clockMs()
    private var observed = false

    @Synchronized
    fun reserve(bytes: Int, socketQueueBytes: Long): Boolean {
        val now = clockMs()
        if (observed && socketQueueBytes < previousObservedBytes) lastProgressAt = now
        if (socketQueueBytes == 0L) lastProgressAt = now
        if (socketQueueBytes > maxBytes || socketQueueBytes + bytes > maxBytes) return false
        if (socketQueueBytes > 0 && now - lastProgressAt > maxAgeMs) return false
        previousObservedBytes = socketQueueBytes + bytes
        observed = true
        return true
    }
}

class GeminiWebSocketTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val factory: (Request, WebSocketListener) -> WebSocket = { request, listener -> client.newWebSocket(request, listener) },
) : GeminiLiveTransport {
    override suspend fun checkConnection(apiKey: String): ConnectionReport {
        val started = System.nanoTime()
        val connection = open(apiKey)
        connection.cancel()
        return ConnectionReport(GEMINI_MODEL.removePrefix("models/"), elapsedMs = (System.nanoTime() - started) / 1_000_000)
    }

    override suspend fun open(apiKey: String, targetLanguage: String): LiveConnection =
        openInternal(apiKey, targetLanguage, CancellationSignal())

    override suspend fun open(apiKey: String, targetLanguage: String, cancellation: CancellationSignal): LiveConnection =
        openInternal(apiKey, targetLanguage, cancellation)

    private suspend fun openInternal(apiKey: String, targetLanguage: String, cancellation: CancellationSignal): LiveConnection {
        if (apiKey.isBlank()) throw ProtocolFailure.Credentials
        if (cancellation.isCancelled()) throw ProtocolFailure.Cancelled
        val request = Request.Builder().url(endpoint(apiKey)).build()
        val ready = ArrayBlockingQueue<Result<Unit>>(1)
        val events = IncomingEventQueue()
        var socket: WebSocket? = null
        try {
            socket = factory(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (cancellation.isCancelled()) {
                        webSocket.cancel()
                        return
                    }
                    if (!webSocket.send(GeminiProtocol.setup(targetLanguage))) {
                        fail(webSocket, ProtocolFailure.Network, ready, events)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) = onFrame(webSocket, text.toByteArray(), ready, events, cancellation)
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = onFrame(webSocket, bytes.toByteArray(), ready, events, cancellation)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val failure = if (response != null) httpFailure(response.code) else ProtocolFailure.Network
                    fail(webSocket, failure, ready, events)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 1007) cancellation.protocolReject(ProtocolRejectReason.WEBSOCKET_CLOSE_1007)
                    val failure = GeminiProtocol.closeFailure(code)
                    ready.offer(Result.failure(failure))
                    events.terminate(failure)
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!events.isTerminal()) {
                        if (code == 1007) cancellation.protocolReject(ProtocolRejectReason.WEBSOCKET_CLOSE_1007)
                        events.terminate(GeminiProtocol.closeFailure(code))
                    }
                }
            })
            var result: Result<Unit>? = null
            val deadline = System.currentTimeMillis() + 15_000
            while (result == null && System.currentTimeMillis() < deadline) {
                if (cancellation.isCancelled()) throw ProtocolFailure.Cancelled
                result = ready.poll(100, TimeUnit.MILLISECONDS)
            }
            result ?: throw ProtocolFailure.Timeout
            result.getOrThrow()
            return SocketLiveConnection(socket ?: throw ProtocolFailure.Network, events)
        } catch (failure: Exception) {
            socket?.cancel()
            if (failure is ProtocolFailure) throw failure
            throw ProtocolFailure.Network
        }
    }

    private fun onFrame(
        socket: WebSocket,
        bytes: ByteArray,
        ready: ArrayBlockingQueue<Result<Unit>>,
        events: IncomingEventQueue,
        cancellation: CancellationSignal,
    ) {
        try {
            GeminiProtocol.parseFrame(bytes, cancellation::protocolReject).forEach { event ->
                if (event is TranslationAudioEvent.Ready) ready.offer(Result.success(Unit))
                else if (!events.offer(event)) socket.cancel()
            }
        } catch (failure: ProtocolFailure) {
            fail(socket, failure, ready, events)
        }
    }

    private fun fail(
        socket: WebSocket,
        failure: ProtocolFailure,
        ready: ArrayBlockingQueue<Result<Unit>>,
        events: IncomingEventQueue,
    ) {
        ready.offer(Result.failure(failure))
        events.terminate(failure)
        socket.cancel()
    }

    private fun httpFailure(code: Int): ProtocolFailure = when (code) {
        400, 401, 403 -> ProtocolFailure.Credentials
        429 -> ProtocolFailure.Quota
        in 500..599 -> ProtocolFailure.ServiceUnavailable(code)
        else -> ProtocolFailure.Network
    }

    private fun endpoint(apiKey: String): String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
}

internal class SocketLiveConnection(
    private val socket: WebSocket,
    private val events: IncomingEventQueue,
) : LiveConnection {
    private val budget = OutboundBudget()

    override fun sendPcm16k(packet: ShortArray) {
        val message = GeminiProtocol.audioMessage(packet)
        val bytes = message.toByteArray(Charsets.UTF_8).size
        if (!budget.reserve(bytes, socket.queueSize())) {
            events.terminate(ProtocolFailure.Backpressure)
            socket.cancel()
            throw ProtocolFailure.Backpressure
        }
        if (!socket.send(message)) {
            events.terminate(ProtocolFailure.Network)
            socket.cancel()
            throw ProtocolFailure.Network
        }
    }

    override fun receive(): TranslationAudioEvent {
        return when (val item = events.take()) {
            is TranslationAudioEvent -> item
            is ProtocolFailure -> throw item
            else -> throw ProtocolFailure.Protocol
        }
    }

    override fun cancel() {
        socket.cancel()
        events.terminate(ProtocolFailure.Cancelled)
    }
}
