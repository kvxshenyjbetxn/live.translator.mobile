package dev.live.translator.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ImageButton
import android.widget.ArrayAdapter
import android.widget.TextView
import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.CaptureTarget
import dev.live.translator.mobile.service.TranslationForegroundService
import dev.live.translator.mobile.service.SessionPhase
import dev.live.translator.mobile.service.SessionState
import dev.live.translator.mobile.settings.SettingsRepository
import dev.live.translator.mobile.source.SourceCatalog
import dev.live.translator.mobile.source.SourceUnavailable
import dev.live.translator.mobile.ui.MainViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class MainActivity : Activity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sourceCatalog: SourceCatalog
    private lateinit var sourceButton: Button
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var settings = AppSettings()
    private var targets = emptyList<CaptureTarget>()
    private var selectedTarget: CaptureTarget? = null
    private val consentGate = dev.live.translator.mobile.ui.ConsentRequestGate()
    private var service: TranslationForegroundService? = null
    private var servicePhase = SessionPhase.IDLE
    private var projectionRequestCode = REQUEST_PROJECTION_BASE
    private val bound = AtomicBoolean(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TranslationForegroundService.LocalBinder).service()
            service?.setStateListener { state -> runOnUiThread { applyState(state) } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound.set(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsRepository = SettingsRepository.shared(filesDir)
        sourceCatalog = SourceCatalog(this)
        sourceButton = findViewById(R.id.sourceButton)
        sourceButton.setOnClickListener { showSourcePicker() }
        status = findViewById(R.id.statusText)
        start = findViewById(R.id.startButton)
        stop = findViewById(R.id.stopButton)
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.refreshButton).setOnClickListener { refreshTargets() }
        start.setOnClickListener { beginStart() }
        stop.setOnClickListener { stopTranslation() }
        reloadSettings()
        refreshTargets()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, TranslationForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        bound.set(true)
    }

    override fun onStop() {
        if (bound.getAndSet(false)) unbindService(serviceConnection)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        reloadSettings()
    }

    override fun onDestroy() {
        consentGate.invalidate()
        super.onDestroy()
    }

    private fun reloadSettings() {
        Thread {
            val loaded = try { runSuspend { settingsRepository.load() } } catch (_: Exception) { settings }
            runOnUiThread {
                settings = loaded
                refreshTargets()
            }
        }.start()
    }

    private fun refreshTargets() {
        val visibleTargets = try { sourceCatalog.visibleCaptureTargets() } catch (_: Exception) { emptyList() }
        targets = MainViewModel.withRetainedSelection(visibleTargets, settings)
        selectedTarget = targets.firstOrNull { it.packageName == settings.selectedPackage }
        sourceButton.text = MainViewModel.sourceSummary(selectedTarget)
        updateStartEnabled()
    }

    private fun showSourcePicker() {
        refreshTargets()
        val choices = targets.toList()
        val labels = choices.map { target ->
            val status = when {
                !target.available -> " · недоступна"
                target.packageName == settings.selectedPackage -> " · вибрано"
                else -> ""
            }
            "${target.label} — ${target.packageName}$status"
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun isEnabled(position: Int): Boolean = choices[position].available
            override fun areAllItemsEnabled(): Boolean = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.source_title)
            .setNegativeButton(android.R.string.cancel, null)
        if (choices.isEmpty()) dialog.setMessage(R.string.no_source)
        else dialog.setAdapter(adapter) { _, position -> selectTarget(choices[position]) }
        dialog.show()
    }

    private fun selectTarget(target: CaptureTarget) {
        if (!target.available) return
        settings = settings.copy(selectedPackage = target.packageName, selectedLabel = target.label)
        Thread {
            runSuspend {
                settingsRepository.update { it.copy(selectedPackage = target.packageName, selectedLabel = target.label) }
            }
            val latest = try { runSuspend { settingsRepository.load() } } catch (_: Exception) { settings }
            runOnUiThread {
                settings = latest
                refreshTargets()
            }
        }.start()
    }

    private fun beginStart() {
        val target = selectedTarget
        if (servicePhase != SessionPhase.IDLE && servicePhase != SessionPhase.ERROR) {
            status.text = "Переклад уже запускається або працює."
            return
        }
        if (consentGate.current() != null) {
            status.text = "Дозвіл на захоплення вже очікується."
            return
        }
        if (target == null || !target.available || settings.apiKey.isBlank()) {
            status.text = "Додайте API-ключ і виберіть доступну програму."
            return
        }
        val resolved = try { sourceCatalog.resolveStartTarget(target.packageName) } catch (_: SourceUnavailable) {
            status.text = "Вибрана програма більше недоступна. Оновіть список."
            refreshTargets()
            return
        }
        val request = consentGate.begin(resolved.packageName)
        if (request == null) {
            status.text = "Дозвіл на захоплення вже очікується."
            return
        }
        projectionRequestCode = REQUEST_PROJECTION_BASE + (request.generation % 1000).toInt()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } else requestProjection()
    }

    private fun requestProjection() {
        if (consentGate.current() == null) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        startActivityForResult(intent, projectionRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (consentGate.current() == null) return
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjection()
            else {
                consentGate.invalidate()
                status.text = "Потрібен дозвіл на аудіо для захоплення вибраної програми."
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequestCode) return
        val request = consentGate.consume()
        if (request == null || resultCode != RESULT_OK || data == null) {
            consentGate.invalidate()
            status.text = "Дозвіл на захоплення скасовано. Переклад не запущено."
            return
        }
        val resolved = try { sourceCatalog.resolveStartTarget(request.packageName) } catch (_: SourceUnavailable) {
            status.text = "Вибрана програма більше недоступна. Оновіть список."
            refreshTargets()
            return
        }
        val latestSettings = try { runSuspend { settingsRepository.load() } } catch (_: Exception) { settings }
        if (latestSettings.selectedPackage != request.packageName || latestSettings.apiKey.isBlank()) {
            status.text = "Налаштування джерела або API-ключ змінилися. Почніть знову."
            settings = latestSettings
            refreshTargets()
            return
        }
        settings = latestSettings
        val intent = Intent(this, TranslationForegroundService::class.java)
            .putExtra(TranslationForegroundService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationForegroundService.EXTRA_PROJECTION_DATA, data)
            .putExtra(TranslationForegroundService.EXTRA_API_KEY, latestSettings.apiKey)
            .putExtra(TranslationForegroundService.EXTRA_SELECTED_UID, resolved.uid)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Запуск перекладу…"
        openTarget(resolved.packageName)
    }

    private fun openTarget(packageName: String) {
        try { packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) } } catch (_: Exception) { }
    }

    private fun stopTranslation() {
        consentGate.invalidate()
        startService(Intent(this, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_STOP))
    }

    private fun applyState(state: SessionState) {
        status.text = state.message
        servicePhase = state.phase
        if (state.phase == SessionPhase.IDLE || state.phase == SessionPhase.ERROR) updateStartEnabled()
        else start.isEnabled = false
        stop.isEnabled = state.phase == SessionPhase.STARTING || state.phase == SessionPhase.RUNNING
    }

    private fun updateStartEnabled() {
        start.isEnabled = servicePhase != SessionPhase.STARTING && servicePhase != SessionPhase.RUNNING &&
            consentGate.current() == null && settings.apiKey.isNotBlank() && selectedTarget?.available == true
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return result!!.getOrThrow()
    }

    companion object {
        private const val REQUEST_AUDIO = 10
        private const val REQUEST_PROJECTION_BASE = 11
    }
}
