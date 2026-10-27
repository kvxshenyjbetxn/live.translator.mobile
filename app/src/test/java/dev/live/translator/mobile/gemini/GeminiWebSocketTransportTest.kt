package dev.live.translator.mobile.gemini

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.startCoroutine
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.ByteString

class GeminiWebSocketTransportTest {
    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return result!!.getOrThrow()
    }
    @Test fun incomingOverflowPublishesTerminalError() {
        val queue = IncomingEventQueue(2)
        assertTrue(queue.offer(TranslationAudioEvent.Audio(shortArrayOf(1))))
        assertTrue(queue.offer(TranslationAudioEvent.Audio(shortArrayOf(2))))
        assertFalse(queue.offer(TranslationAudioEvent.Audio(shortArrayOf(3))))
        assertTrue(queue.isTerminal())
        assertEquals(ProtocolFailure.QueueOverflow, queue.take())
    }

    @Test fun receiveWaitsForHealthySilenceUntilCancellation() {
        val queue = IncomingEventQueue()
        val completed = CountDownLatch(1)
        var result: Any? = null
        Thread {
            result = try { queue.take() } catch (_: InterruptedException) { null }
            completed.countDown()
        }.start()
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        queue.terminate(ProtocolFailure.Cancelled)
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(ProtocolFailure.Cancelled, result)
    }

    @Test fun mockSocketCancelStopsConnectionAndBackpressureIsTerminal() {
        val socket = FakeWebSocket()
        val queue = IncomingEventQueue()
        val connection = SocketLiveConnection(socket, queue)
        repeat(20) {
            try { connection.sendPcm16k(ShortArray(1600)) } catch (_: ProtocolFailure) { return@repeat }
        }
        connection.cancel()
        assertTrue(socket.cancelled)
        assertEquals(ProtocolFailure.Backpressure, queue.take())
    }

    @Test fun setupFailureCancelsUnsuccessfulSocketOpen() {
        val socket = FakeWebSocket()
        val transport = GeminiWebSocketTransport(factory = { request, listener ->
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build()
            listener.onOpen(socket, response)
            listener.onMessage(socket, "{\"error\":{\"code\":403}}")
            socket
        })
        try {
            runSuspend { transport.open("test-key") }
            kotlin.test.fail("expected credentials failure")
        } catch (failure: ProtocolFailure.Credentials) {
            assertTrue(socket.cancelled)
        }
    }

    @Test fun additiveControlBetweenAudioFramesKeepsConnectionUsable() {
        val socket = FakeWebSocket()
        lateinit var listener: okhttp3.WebSocketListener
        val transport = GeminiWebSocketTransport(factory = { request, created ->
            listener = created
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build()
            created.onOpen(socket, response)
            created.onMessage(socket, "{\"setupComplete\":{}}")
            socket
        })
        val connection = runSuspend { transport.open("test-key") }
        val audio = "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AAA=\"}}]}}}"
        listener.onMessage(socket, audio)
        assertTrue(connection.receive() is TranslationAudioEvent.Audio)
        listener.onMessage(socket, "{\"additiveControl\":{}}")
        listener.onMessage(socket, audio)
        assertTrue(connection.receive() is TranslationAudioEvent.Audio)
        assertFalse(socket.cancelled)
        connection.cancel()
    }

    @Test fun afterAudioParserRejectReportsSubreasonWithoutPayload() {
        val socket = FakeWebSocket()
        lateinit var listener: okhttp3.WebSocketListener
        val transport = GeminiWebSocketTransport(factory = { request, created ->
            listener = created
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build()
            created.onOpen(socket, response)
            created.onMessage(socket, "{\"setupComplete\":{}}")
            socket
        })
        val reasons = mutableListOf<ProtocolRejectReason>()
        val connection = runSuspend { transport.open("test-key", "uk", CancellationSignal(reasons::add)) }
        listener.onMessage(socket, "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AAA=\"}}]}}}")
        assertTrue(connection.receive() is TranslationAudioEvent.Audio)
        listener.onMessage(socket, "{\"serverContent\":false}")
        kotlin.test.assertFailsWith<ProtocolFailure.Protocol> { connection.receive() }
        assertEquals(listOf(ProtocolRejectReason.INVALID_SERVER_CONTENT), reasons)
    }

    @Test fun websocketClose1007HasDistinctSubreason() {
        val socket = FakeWebSocket()
        lateinit var listener: okhttp3.WebSocketListener
        val transport = GeminiWebSocketTransport(factory = { request, created ->
            listener = created
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build()
            created.onOpen(socket, response)
            created.onMessage(socket, "{\"setupComplete\":{}}")
            socket
        })
        val reasons = mutableListOf<ProtocolRejectReason>()
        val connection = runSuspend { transport.open("test-key", "uk", CancellationSignal(reasons::add)) }
        listener.onClosing(socket, 1007, "private-reason")
        kotlin.test.assertFailsWith<ProtocolFailure.Protocol> { connection.receive() }
        assertEquals(listOf(ProtocolRejectReason.WEBSOCKET_CLOSE_1007), reasons)
    }

    @Test fun outboundBudgetAllowsNeverEmptyProgressingQueue() {
        var now = 0L
        val budget = OutboundBudget(maxBytes = 100, maxAgeMs = 10) { now }
        assertTrue(budget.reserve(20, 20))
        now = 8
        assertTrue(budget.reserve(20, 19))
        now = 16
        assertTrue(budget.reserve(20, 19))
    }

    @Test fun outboundBudgetRejectsOldStalledQueue() {
        var now = 0L
        val budget = OutboundBudget(maxBytes = 100, maxAgeMs = 10) { now }
        assertTrue(budget.reserve(80, 0))
        now = 11
        assertFalse(budget.reserve(1, 80))
    }

    private class FakeWebSocket : okhttp3.WebSocket {
        var cancelled = false
        private var queued = 0L
        override fun request(): Request = Request.Builder().url("ws://localhost").build()
        override fun queueSize(): Long = queued
        override fun send(text: String): Boolean { queued += text.length; return !cancelled }
        override fun send(bytes: ByteString): Boolean { queued += bytes.size; return !cancelled }
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true }
    }
}
