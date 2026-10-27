package dev.live.translator.mobile.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import dev.live.translator.mobile.diagnostics.SessionEvent
import dev.live.translator.mobile.diagnostics.SessionTrace

interface CaptureReader {
    fun start(onSamples: (ShortArray, Int) -> Unit, onFailure: (Exception) -> Unit)
    fun stop()
}

class PlaybackCaptureReader(
    private val projection: MediaProjection,
    private val selectedUid: Int,
    private val ownUid: Int,
    private val mainHandler: Handler,
    private val hasRecordPermission: () -> Boolean,
    private val onProjectionRevoked: () -> Unit = {},
    private val trace: SessionTrace? = null,
) : CaptureReader {
    private val lifecycleLock = Any()
    private val recordSlot = CloseOnceResource<AudioRecord> { record ->
        try { record.stop() } catch (_: IllegalStateException) { }
        record.release()
    }
    private var callback: MediaProjection.Callback? = null
    private var thread: Thread? = null
    private var failureHandler: ((Exception) -> Unit)? = null
    private var closed = false
    private var running = false

    init {
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                trace?.event(SessionEvent.PROJECTION_REVOKED)
                onProjectionRevoked()
                failureHandler?.invoke(IllegalStateException("Доступ до проєкції відкликано"))
                stop()
            }
        }
        synchronized(lifecycleLock) {
            callback = projectionCallback
            projection.registerCallback(projectionCallback, mainHandler)
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(onSamples: (ShortArray, Int) -> Unit, onFailure: (Exception) -> Unit) {
        var failure: Exception? = null
        synchronized(lifecycleLock) {
            check(!closed) { "Projection is already stopped" }
            check(selectedUid != ownUid) { "Власний застосунок не може бути джерелом" }
            check(hasRecordPermission()) { "Немає дозволу на захоплення аудіо" }
            check(!running) { "Capture already started" }
            running = true
            failureHandler = onFailure
            var localRecord: AudioRecord? = null
            var published = false
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(INPUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) throw IllegalStateException("Формат захоплення недоступний")
                localRecord = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, INPUT_RATE / 2))
                    .setAudioPlaybackCaptureConfig(buildCaptureConfig(projection, selectedUid, ownUid))
                    .build()
                if (localRecord.state != AudioRecord.STATE_INITIALIZED) {
                    localRecord.release()
                    localRecord = null
                    throw IllegalStateException("AudioRecord не ініціалізовано")
                }
                val activeRecord = localRecord ?: throw IllegalStateException("AudioRecord не ініціалізовано")
                if (!recordSlot.publish(activeRecord)) {
                    localRecord = null
                    throw IllegalStateException("Capture resource was closed")
                }
                published = true
                activeRecord.startRecording()
                trace?.event(SessionEvent.CAPTURE_STARTED)
                thread = Thread({ readLoop(activeRecord, onSamples, onFailure) }, "live-translator-capture").also { it.start() }
            } catch (error: Exception) {
                trace?.event(SessionEvent.CAPTURE_START_FAILURE)
                if (!published) localRecord?.release()
                running = false
                failure = error
            }
        }
        failure?.let {
            onFailure(it)
            stop()
        }
    }

    private fun readLoop(capture: AudioRecord, onSamples: (ShortArray, Int) -> Unit, onFailure: (Exception) -> Unit) {
        val buffer = ShortArray(BUFFER_SAMPLES)
        try {
            while (synchronized(lifecycleLock) { running && !closed } && !Thread.currentThread().isInterrupted) {
                val count = capture.read(buffer, 0, buffer.size)
                if (count < 0) throw IllegalStateException("AudioRecord read failed")
                if (count > 0) {
                    trace?.event(SessionEvent.CAPTURE_SAMPLES)
                    onSamples(buffer.copyOf(count), count)
                }
            }
        } catch (error: Exception) {
            val shouldReport = synchronized(lifecycleLock) { running && !closed }
            if (shouldReport) {
                trace?.event(SessionEvent.CAPTURE_READ_FAILURE)
                onFailure(error)
            }
        }
    }

    override fun stop() {
        val currentThread: Thread?
        val currentCallback: MediaProjection.Callback?
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            running = false
            currentThread = thread
            currentCallback = callback
            thread = null
            callback = null
            failureHandler = null
            recordSlot.close()
        }
        currentThread?.interrupt()
        currentCallback?.let {
            try { projection.unregisterCallback(it) } catch (_: Exception) { }
        }
        try { projection.stop() } catch (_: Exception) { }
    }

    companion object {
        const val INPUT_RATE = 48_000
        private const val BUFFER_SAMPLES = 4_800

        fun buildCaptureConfig(projection: MediaProjection, selectedUid: Int, ownUid: Int): AudioPlaybackCaptureConfiguration {
            require(selectedUid != ownUid) { "Власний застосунок не може бути джерелом" }
            return AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(selectedUid)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }
    }
}
