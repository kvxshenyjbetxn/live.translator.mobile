package dev.live.translator.mobile.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PlaybackFormatException(message: String) : Exception(message)

interface TranslationPlayer {
    fun start(onFailure: (Exception) -> Unit = {})
    fun playPcm24k(pcm: ShortArray)
    fun stopAndRelease()
    fun queuedSeconds(): Double = 0.0
}

class TranslatedAudioPlayer(private val queueCapacity: Int = 8) : TranslationPlayer {
    private val queue = ArrayBlockingQueue<ShortArray>(queueCapacity)
    private val running = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    private val queuedSamples = AtomicInteger()
    @Volatile private var writtenFrames = 0L
    private var thread: Thread? = null

    override fun start(onFailure: (Exception) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) throw PlaybackFormatException("Формат відтворення 24 кГц недоступний")
            val created = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuffer, OUTPUT_RATE / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                throw PlaybackFormatException("AudioTrack не ініціалізовано")
            }
            track = created
            created.play()
            thread = Thread({ playbackLoop(created, onFailure) }, "live-translator-playback").also { it.start() }
        } catch (error: Exception) {
            running.set(false)
            throw error
        }
    }

    override fun playPcm24k(pcm: ShortArray) {
        require(running.get()) { "Player is not running" }
        if (!queue.offer(pcm.copyOf())) throw IllegalStateException("Черга відтворення переповнена")
        queuedSamples.addAndGet(pcm.size)
    }

    override fun queuedSeconds(): Double {
        val queued = queuedSamples.get().coerceAtLeast(0)
        val inTrack = try {
            track?.let { t -> (writtenFrames - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).coerceIn(0, t.bufferSizeInFrames.toLong()) } ?: 0L
        } catch (_: Exception) { 0L }
        return (queued + inTrack) / OUTPUT_RATE.toDouble()
    }

    private fun playbackLoop(created: AudioTrack, onFailure: (Exception) -> Unit) {
        while (running.get()) {
            val pcm = queue.poll()
            if (pcm == null) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                continue
            }
            var offset = 0
            while (running.get() && offset < pcm.size) {
                val written = created.write(pcm, offset, pcm.size - offset)
                if (written < 0) {
                    running.set(false)
                    onFailure(PlaybackFormatException("Помилка запису AudioTrack"))
                    break
                }
                offset += written
                writtenFrames += written
                queuedSamples.addAndGet(-written)
            }
        }
    }

    override fun stopAndRelease() {
        if (!running.getAndSet(false) && track == null) return
        queue.clear()
        queuedSamples.set(0)
        thread?.interrupt()
        thread = null
        track?.let {
            try { it.pause() } catch (_: IllegalStateException) { }
            it.flush()
            it.release()
        }
        track = null
    }

    companion object { const val OUTPUT_RATE = 24_000 }
}
