package dev.live.translator.mobile.session

import dev.live.translator.mobile.diagnostics.SessionTrace
import java.util.Collections
import dev.live.translator.mobile.audio.CaptureReader
import dev.live.translator.mobile.audio.TranslationPlayer
import dev.live.translator.mobile.gemini.LiveConnection
import dev.live.translator.mobile.gemini.ProtocolFailure
import dev.live.translator.mobile.gemini.TranslationAudioEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSessionTest {
    @Test fun cancellationStopsCapturePlayerAndConnection() {
        val capture = FakeCapture()
        val player = FakePlayer()
        val connection = BlockingConnection()
        val session = LiveSession(connection, capture, player)
        session.start()
        session.cancel()
        assertTrue(session.awaitCompletion())
        assertTrue(capture.stopped)
        assertTrue(player.stopped)
        assertTrue(connection.closed)
    }

    @Test fun playbackFailureCausesControlledCleanup() {
        val capture = FakeCapture()
        val player = FakePlayer(throwsOnPlay = true)
        val connection = SingleAudioConnection()
        var failure: ProtocolFailure? = null
        val events = Collections.synchronizedList(mutableListOf<String>())
        val session = LiveSession(connection, capture, player, SessionTrace { events.add(it) }) { failure = it }
        session.start()
        assertTrue(session.awaitCompletion())
        assertEquals(ProtocolFailure.Playback, failure)
        assertTrue(events.contains("stage.audio_received"))
        assertTrue(events.contains("stage.playback_enqueue_failure"))
        assertTrue(events.contains("terminal.playback"))
        assertTrue(capture.stopped && player.stopped && connection.closed)
    }

    @Test fun outboundQueueOverflowIsTerminalAndDoesNotDropSilently() {
        val capture = FakeCapture()
        val player = FakePlayer()
        val connection = BlockingConnection()
        var failure: ProtocolFailure? = null
        val session = LiveSession(connection, capture, player) { failure = it }
        session.start()
        repeat(20) { capture.emit(ShortArray(4_800) { 1 }) }
        assertTrue(session.awaitCompletion(5_000))
        assertEquals(ProtocolFailure.Backpressure, failure)
        assertTrue(connection.closed && player.stopped)
    }

    private class FakeCapture : CaptureReader {
        private var onSamples: ((ShortArray, Int) -> Unit)? = null
        var stopped = false
        override fun start(onSamples: (ShortArray, Int) -> Unit, onFailure: (Exception) -> Unit) { this.onSamples = onSamples }
        fun emit(samples: ShortArray) { onSamples?.invoke(samples, samples.size) }
        override fun stop() { stopped = true }
    }

    private class FakePlayer(private val throwsOnPlay: Boolean = false) : TranslationPlayer {
        var stopped = false
        private var failure: ((Exception) -> Unit)? = null
        override fun start(onFailure: (Exception) -> Unit) { failure = onFailure }
        override fun playPcm24k(pcm: ShortArray) {
            if (throwsOnPlay) throw IllegalStateException("blocked player")
        }
        override fun stopAndRelease() { stopped = true }
    }

    private open class BlockingConnection : LiveConnection {
        var closed = false
        private val release = CountDownLatch(1)
        override fun sendPcm16k(packet: ShortArray) { release.await() }
        override fun receive(): TranslationAudioEvent { release.await(); throw ProtocolFailure.Cancelled }
        override fun cancel() { closed = true; release.countDown() }
    }

    private class SingleAudioConnection : LiveConnection {
        var closed = false
        private var sent = false
        override fun sendPcm16k(packet: ShortArray) = Unit
        override fun receive(): TranslationAudioEvent {
            if (!sent) { sent = true; return TranslationAudioEvent.Audio(ShortArray(10)) }
            throw ProtocolFailure.Cancelled
        }
        override fun cancel() { closed = true }
    }
}
