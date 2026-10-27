package dev.live.translator.mobile.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.live.translator.mobile.MainActivity
import dev.live.translator.mobile.audio.PlaybackCaptureReader
import dev.live.translator.mobile.audio.TranslatedAudioPlayer
import dev.live.translator.mobile.diagnostics.SessionEvent
import dev.live.translator.mobile.diagnostics.SessionTrace
import dev.live.translator.mobile.diagnostics.TerminalCause
import dev.live.translator.mobile.gemini.CancellationSignal
import dev.live.translator.mobile.gemini.GeminiLiveTransport
import dev.live.translator.mobile.gemini.GeminiWebSocketTransport
import dev.live.translator.mobile.gemini.LiveConnection
import dev.live.translator.mobile.gemini.ProtocolFailure
import dev.live.translator.mobile.session.LiveSession
import dev.live.translator.mobile.session.terminalCause
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class TranslationForegroundService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): TranslationForegroundService = this@TranslationForegroundService
    }

    private data class Startup(
        val id: Long,
        val trace: SessionTrace,
        val token: CancellationSignal,
        val projection: MediaProjection,
        val reader: PlaybackCaptureReader,
        val player: TranslatedAudioPlayer,
    )

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private val sessionGate = SessionGenerationGate()
    private var destroyed = false
    private var listener: ((SessionState) -> Unit)? = null
    private var state = SessionState()
    private var startup: Startup? = null
    private var session: LiveSession? = null
    private var trace: SessionTrace? = null
    private var transport: GeminiLiveTransport = GeminiWebSocketTransport()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun setStateListener(newListener: ((SessionState) -> Unit)?) {
        listener = newListener
        notifyState()
    }

    fun currentState(): SessionState = state

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            trace?.event(SessionEvent.USER_STOP)
            trace?.terminal(TerminalCause.USER_STOP)
            stopSession("Зупинено користувачем.")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val projectionData = intent?.projectionData()
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY).orEmpty()
        val selectedUid = intent?.getIntExtra(EXTRA_SELECTED_UID, -1) ?: -1
        if (resultCode != RESULT_OK || projectionData == null || apiKey.isBlank() || selectedUid < 0 || selectedUid == applicationInfo.uid) {
            fail("Не отримано всі дозволи або джерело недоступне.")
            return START_NOT_STICKY
        }
        startSession(resultCode, projectionData, apiKey, selectedUid)
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int, projectionData: Intent, apiKey: String, selectedUid: Int) {
        val id: Long
        val resources: Startup
        synchronized(lifecycleLock) {
            if (destroyed || startup != null || session != null) return
            id = sessionGate.begin() ?: return
            val sessionTrace = SessionTrace()
            trace = sessionTrace
            sessionTrace.event(SessionEvent.START_REQUESTED)
            state = SessionState(SessionPhase.STARTING, "Підготовка перекладу…")
            notifyState()
            var projection: MediaProjection? = null
            try {
                val notification = buildNotification("Переклад активний")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else startForeground(NOTIFICATION_ID, notification)
                projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .getMediaProjection(resultCode, projectionData)
                    ?: throw IllegalStateException("Система не надала MediaProjection")
                sessionTrace.event(SessionEvent.PROJECTION_READY)
                val token = CancellationSignal(sessionTrace::protocolReject)
                val reader = PlaybackCaptureReader(
                    projection,
                    selectedUid,
                    applicationInfo.uid,
                    mainHandler,
                    { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                    {
                        sessionTrace.terminal(TerminalCause.PROJECTION_REVOKED)
                        token.cancel()
                        mainHandler.post { stopSession("Доступ до проєкції відкликано.") }
                    },
                    sessionTrace,
                )
                resources = Startup(id, sessionTrace, token, projection, reader, TranslatedAudioPlayer())
                startup = resources
            } catch (_: Exception) {
                sessionTrace.terminal(TerminalCause.OTHER)
                try { projection?.stop() } catch (_: Exception) { }
                fail("Не вдалося запустити захоплення.")
                return
            }
        }

        Thread({
            var connection: LiveConnection? = null
            try {
                connection = runSuspend { transport.open(apiKey, "uk", resources.token) }
                val sessionTrace = resources.trace
                sessionTrace.event(SessionEvent.TRANSPORT_READY)
                synchronized(lifecycleLock) {
                    val active = !destroyed && startup?.id == id && sessionGate.isCurrent(id) && !resources.token.isCancelled()
                    if (!active) {
                        cleanupAbandoned(resources, connection)
                        return@synchronized
                    }
                    startup = null
                    sessionGate.complete(id)
                    val liveSession = LiveSession(connection, resources.reader, resources.player, sessionTrace) { failure ->
                        mainHandler.post { stopSession(failureMessage(failure)) }
                    }
                    session = liveSession
                    state = SessionState(SessionPhase.RUNNING, "Переклад працює. Можна перейти до вибраної програми.")
                    notifyState()
                    liveSession.start()
                }
            } catch (failure: Exception) {
                if (!resources.token.isCancelled()) resources.trace.terminal((failure as? ProtocolFailure)?.terminalCause() ?: TerminalCause.OTHER)
                val wasCancelled = resources.token.isCancelled()
                val wasCurrent = synchronized(lifecycleLock) {
                    !destroyed && startup?.id == id && sessionGate.isCurrent(id)
                }
                cleanupAbandoned(resources, connection)
                synchronized(lifecycleLock) {
                    if (startup?.id == id) startup = null
                    sessionGate.clearIf(id)
                    if (wasCurrent && !wasCancelled && !destroyed) {
                        state = SessionState(SessionPhase.ERROR, failureMessage(failure))
                        notifyState()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    if (startup?.id == id) startup = null
                    sessionGate.clearIf(id)
                }
            }
        }, "live-translator-session-$id").start()
    }

    private fun cleanupAbandoned(resources: Startup, connection: LiveConnection?) {
        try { connection?.cancel() } catch (_: Exception) { }
        resources.reader.stop()
        resources.player.stopAndRelease()
    }

    private fun stopSession(message: String) {
        val activeStartup: Startup?
        val activeSession: LiveSession?
        synchronized(lifecycleLock) {
            sessionGate.invalidate()
            activeStartup = startup
            activeSession = session
            activeStartup?.token?.cancel()
            session = null
            state = SessionState(SessionPhase.STOPPING, message)
            notifyState()
        }
        activeSession?.cancel()
        activeStartup?.let {
            it.reader.stop()
            it.player.stopAndRelease()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        synchronized(lifecycleLock) {
            state = SessionState(SessionPhase.IDLE, message)
            notifyState()
        }
    }

    private fun fail(message: String) {
        synchronized(lifecycleLock) {
            sessionGate.invalidate()
            state = SessionState(SessionPhase.ERROR, message)
            notifyState()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        trace?.event(SessionEvent.SERVICE_DESTROYED)
        val activeStartup: Startup?
        val activeSession: LiveSession?
        synchronized(lifecycleLock) {
            destroyed = true
            sessionGate.invalidate()
            activeStartup = startup
            activeSession = session
            activeStartup?.token?.cancel()
            startup = null
            session = null
            listener = null
        }
        activeSession?.cancel()
        activeStartup?.let {
            it.reader.stop()
            it.player.stopAndRelease()
        }
        super.onDestroy()
    }

    private fun notifyState() {
        val snapshot = state
        mainHandler.post { listener?.invoke(snapshot) }
    }

    private fun failureMessage(failure: Exception): String = when (failure) {
        ProtocolFailure.Credentials -> "Перевірте API-ключ і доступ проєкту до Gemini."
        ProtocolFailure.Quota -> "Вичерпано квоту Gemini. Перевірте ліміти пізніше."
        is ProtocolFailure.ServiceUnavailable -> "Google тимчасово недоступний (код ${failure.code})."
        ProtocolFailure.Closed -> "Сесію Gemini завершено. Натисніть «Почати» знову."
        ProtocolFailure.Timeout -> "Google не відповів вчасно."
        ProtocolFailure.Cancelled -> "Запуск скасовано."
        ProtocolFailure.QueueOverflow, ProtocolFailure.Backpressure -> "Мережа не встигає обробляти аудіо."
        ProtocolFailure.Playback -> "Не вдалося відтворити перекладене аудіо."
        else -> "З'єднання з Google втрачено. Перевірте мережу."
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live Translator", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslationForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Live Translator")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Зупинити", stop)
            .setOngoing(true)
            .build()
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return result!!.getOrThrow()
    }

    @Suppress("DEPRECATION")
    private fun Intent.projectionData(): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    } else getParcelableExtra(EXTRA_PROJECTION_DATA)

    companion object {
        const val ACTION_STOP = "dev.live.translator.mobile.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_SELECTED_UID = "selected_uid"
        private const val CHANNEL_ID = "live_translator"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_OK = -1
    }
}
