package dev.live.translator.mobile.session

import dev.live.translator.mobile.audio.CaptureReader
import dev.live.translator.mobile.audio.PcmPacketizer
import dev.live.translator.mobile.audio.StreamingResampler
import dev.live.translator.mobile.audio.TranslationPlayer
import dev.live.translator.mobile.diagnostics.SessionEvent
import dev.live.translator.mobile.diagnostics.SessionTrace
import dev.live.translator.mobile.diagnostics.TerminalCause
import dev.live.translator.mobile.gemini.LiveConnection
import dev.live.translator.mobile.gemini.ProtocolFailure
import dev.live.translator.mobile.gemini.TranslationAudioEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class LiveSession(
    private val connection: LiveConnection,
    private val captureReader: CaptureReader,
    private val player: TranslationPlayer,
    private val trace: SessionTrace? = null,
    private val onFailure: (ProtocolFailure) -> Unit = {},
) {
    private val cancelled = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)
    private val inputQueue = ArrayBlockingQueue<ShortArray>(8)
    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null

    fun start() {
        if (cancelled.get()) return
        try {
            player.start {
                trace?.event(SessionEvent.PLAYBACK_WRITE_FAILURE)
                fail(ProtocolFailure.Playback)
            }
            trace?.event(SessionEvent.PLAYER_STARTED)
            val resampler = StreamingResampler(48_000)
            val packetizer = PcmPacketizer()
            sendThread = Thread({ sendLoop() }, "live-translator-send").also { it.start() }
            captureReader.start(
                onSamples = { samples, count ->
                    if (!cancelled.get()) {
                        packetizer.add(resampler.accept(samples, count)).forEach { packet ->
                            if (!inputQueue.offer(packet)) {
                                trace?.event(SessionEvent.INPUT_QUEUE_FULL)
                                fail(ProtocolFailure.Backpressure)
                            } else trace?.event(SessionEvent.INPUT_QUEUED)
                        }
                    }
                },
                onFailure = { fail(ProtocolFailure.Network) },
            )
            if (cancelled.get()) return
            receiveThread = Thread({ receiveLoop() }, "live-translator-receive").also { it.start() }
        } catch (_: Exception) {
            fail(ProtocolFailure.Playback)
        }
    }

    private fun sendLoop() {
        try {
            while (!cancelled.get()) {
                connection.sendPcm16k(inputQueue.take())
                trace?.event(SessionEvent.INPUT_SENT)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: ProtocolFailure) {
            fail(failure)
        } finally {
            if (!cancelled.get()) fail(ProtocolFailure.Network)
        }
    }

    private fun receiveLoop() {
        try {
            while (!cancelled.get()) {
                when (val event = connection.receive()) {
                    is TranslationAudioEvent.Audio -> {
                        trace?.event(SessionEvent.AUDIO_RECEIVED)
                        try {
                            player.playPcm24k(event.pcm)
                            trace?.event(SessionEvent.PLAYBACK_QUEUED)
                        } catch (_: Exception) {
                            trace?.event(SessionEvent.PLAYBACK_ENQUEUE_FAILURE)
                            fail(ProtocolFailure.Playback)
                        }
                    }
                    TranslationAudioEvent.Ready -> Unit
                    TranslationAudioEvent.Closed -> fail(ProtocolFailure.Closed)
                }
            }
        } catch (failure: ProtocolFailure) {
            fail(failure)
        } finally {
            if (!cancelled.get()) fail(ProtocolFailure.Network)
        }
    }

    fun pendingSendSeconds(): Double = inputQueue.size * PcmPacketizer.PACKET_SECONDS + connection.pendingSendSeconds()

    fun queuedPlaybackSeconds(): Double = player.queuedSeconds()

    fun cancel() {
        if (!cancelled.getAndSet(true)) cleanup()
    }

    fun awaitCompletion(timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        listOfNotNull(sendThread, receiveThread).forEach { thread ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) thread.join(remaining)
        }
        return cleaned.get()
    }

    private fun fail(failure: ProtocolFailure) {
        if (cancelled.compareAndSet(false, true)) {
            trace?.terminal(failure.terminalCause())
            onFailure(failure)
            cleanup()
        }
    }

    private fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        inputQueue.clear()
        connection.cancel()
        captureReader.stop()
        player.stopAndRelease()
        sendThread?.interrupt()
        receiveThread?.interrupt()
    }
}

internal fun ProtocolFailure.terminalCause(): TerminalCause = when (this) {
    ProtocolFailure.Credentials -> TerminalCause.CREDENTIALS
    ProtocolFailure.Quota -> TerminalCause.QUOTA
    is ProtocolFailure.ServiceUnavailable -> TerminalCause.SERVICE_UNAVAILABLE
    ProtocolFailure.Closed -> TerminalCause.CLOSED
    ProtocolFailure.Protocol -> TerminalCause.PROTOCOL
    ProtocolFailure.Network -> TerminalCause.NETWORK
    ProtocolFailure.Timeout -> TerminalCause.TIMEOUT
    ProtocolFailure.Cancelled -> TerminalCause.CANCELLED
    ProtocolFailure.QueueOverflow -> TerminalCause.INCOMING_QUEUE_FULL
    ProtocolFailure.Backpressure -> TerminalCause.BACKPRESSURE
    ProtocolFailure.Playback -> TerminalCause.PLAYBACK
}
