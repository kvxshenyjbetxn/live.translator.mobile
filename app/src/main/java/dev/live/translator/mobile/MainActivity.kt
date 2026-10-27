package dev.live.translator.mobile

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.os.IBinder
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import dev.live.translator.mobile.audio.TranslationVolume
import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.diagnostics.DiagnosticTexts
import dev.live.translator.mobile.diagnostics.DiagnosticsPresentation
import dev.live.translator.mobile.diagnostics.DiagnosticsRow
import dev.live.translator.mobile.diagnostics.DiagnosticsSnapshot
import dev.live.translator.mobile.model.CaptureTarget
import dev.live.translator.mobile.model.Language
import dev.live.translator.mobile.model.Languages
import dev.live.translator.mobile.ui.DiagnosticsChartView
import android.widget.LinearLayout
import dev.live.translator.mobile.service.TranslationForegroundService
import dev.live.translator.mobile.service.SessionPhase
import dev.live.translator.mobile.service.SessionState
import dev.live.translator.mobile.settings.SettingsRepository
import dev.live.translator.mobile.source.SourceCatalog
import dev.live.translator.mobile.source.SourceUnavailable
import dev.live.translator.mobile.settings.SettingsValidator
import dev.live.translator.mobile.ui.AppPickerAdapter
import dev.live.translator.mobile.ui.MainViewModel
import dev.live.translator.mobile.ui.TargetSections
import dev.live.translator.mobile.ui.applySystemBarInsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class MainActivity : Activity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sourceCatalog: SourceCatalog
    private lateinit var sourceButton: Button
    private lateinit var languageButton: Button
    private lateinit var diagMessages: TextView
    private lateinit var diagRows: List<View>
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var settings = AppSettings()
    private var targets = emptyList<CaptureTarget>()
    private var selectedTarget: CaptureTarget? = null
    private var pickerRender: (() -> Unit)? = null
    private val consentGate = dev.live.translator.mobile.ui.ConsentRequestGate()
    private var service: TranslationForegroundService? = null
    private var servicePhase = SessionPhase.IDLE
    private var projectionRequestCode = REQUEST_PROJECTION_BASE
    private val bound = AtomicBoolean(false)
    private var notificationPermissionAsked = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TranslationForegroundService.LocalBinder).service()
            service?.setStateListener { state -> runOnUiThread { applyState(state) } }
            service?.setDiagnosticsListener { snapshot -> runOnUiThread { renderDiagnostics(snapshot) } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound.set(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.pageRoot).applySystemBarInsets()
        settingsRepository = SettingsRepository.shared(filesDir)
        sourceCatalog = SourceCatalog(this)
        sourceButton = findViewById(R.id.sourceButton)
        sourceButton.setOnClickListener { showSourcePicker() }
        languageButton = findViewById(R.id.languageButton)
        languageButton.setOnClickListener { showLanguagePicker() }
        diagMessages = findViewById(R.id.diagMessages)
        diagRows = listOf(R.id.diagNetwork, R.id.diagSending, R.id.diagPlayback).map { findViewById<View>(it) }
        val subtitles = listOf("Останні 10 вимірювань", null, null)
        val colors = listOf(R.color.diag_network, R.color.diag_sending, R.color.diag_playback)
        diagRows.forEachIndexed { i, row ->
            row.findViewById<DiagnosticsChartView>(R.id.diagChart).configure(getColor(colors[i]), bars = i == 0)
            subtitles[i]?.let { row.findViewById<TextView>(R.id.diagSubtitle).apply { text = it; visibility = View.VISIBLE } }
        }
        renderDiagnostics(DiagnosticsSnapshot())
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
        service?.setDiagnosticsListener(null)
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
            TranslationVolume.boostDb = loaded.translationBoostDb
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
        languageButton.text = Languages.nameOf(settings.targetLanguage) + " ▾"
        updateStartEnabled()
    }

    private fun showSourcePicker() {
        refreshTargets()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_source_picker)
        val search = dialog.findViewById<EditText>(R.id.pickerSearch)
        val list = dialog.findViewById<ListView>(R.id.pickerList)
        val empty = dialog.findViewById<TextView>(R.id.pickerEmpty)
        val adapter = AppPickerAdapter(packageManager, { target -> dialog.dismiss(); selectTarget(target) }, { target -> togglePin(target) })
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> adapter.onItemClick(position) }
        val render = {
            val sections = TargetSections.filter(TargetSections.build(targets, settings), search.text.toString())
            adapter.submit(sections, settings.selectedPackage, settings.pinnedPackages)
            empty.visibility = if (sections.isEmpty()) View.VISIBLE else View.GONE
        }
        pickerRender = render
        dialog.setOnDismissListener { pickerRender = null }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { render() }
        })
        dialog.findViewById<Button>(R.id.pickerClose).setOnClickListener { dialog.dismiss() }
        render()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        val metrics = resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        dialog.window?.setLayout(metrics.widthPixels - 2 * margin, (metrics.heightPixels * 0.8f).toInt())
    }

    private fun showLanguagePicker() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_language_picker)
        val list = dialog.findViewById<LinearLayout>(R.id.languageList)
        val density = resources.displayMetrics.density
        Languages.all.forEach { language ->
            val item = Button(this).apply {
                text = language.name
                textSize = 16f
                isAllCaps = false
                setTextColor(getColor(R.color.text))
                setBackgroundResource(R.drawable.button_background)
                minHeight = (52 * density).toInt()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                isSelected = language.code == settings.targetLanguage
                setOnClickListener { dialog.dismiss(); selectLanguage(language) }
            }
            list.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
        }
        dialog.findViewById<Button>(R.id.languageClose).setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val metrics = resources.displayMetrics
        dialog.window?.setLayout(metrics.widthPixels - 2 * (16 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun selectLanguage(language: Language) {
        if (servicePhase != SessionPhase.IDLE && servicePhase != SessionPhase.ERROR) return
        val transform = { it: AppSettings -> it.copy(targetLanguage = language.code) }
        settings = transform(settings)
        languageButton.text = language.name + " ▾"
        Thread { try { runSuspend { settingsRepository.update(transform) } } catch (_: Exception) { } }.start()
    }

    private fun renderDiagnostics(snapshot: DiagnosticsSnapshot) {
        val live = if (servicePhase == SessionPhase.RUNNING) snapshot else DiagnosticsSnapshot()
        val rows = DiagnosticsPresentation.rows(live)
        val colors = listOf(R.color.diag_network, R.color.diag_sending, R.color.diag_playback)
        rows.forEachIndexed { i, row -> bindDiagnosticsRow(diagRows[i], row, getColor(colors[i])) }
        diagMessages.text = (DiagnosticTexts.diagnosticMessages(live) + listOfNotNull(DiagnosticTexts.NETWORK_UNKNOWN.takeIf { live.networkRttMs == null })).joinToString("\n\n")
        diagMessages.setTextColor(getColor(if (live.issues.isEmpty()) R.color.muted else R.color.diag_playback))
    }

    private fun bindDiagnosticsRow(view: View, row: DiagnosticsRow, color: Int) {
        view.findViewById<TextView>(R.id.diagTitle).text = row.label
        view.findViewById<TextView>(R.id.diagScale).text = row.scale
        view.findViewById<TextView>(R.id.diagValue).apply { text = row.display; setTextColor(if (row.value == null) getColor(R.color.diag_nodata) else color) }
        view.findViewById<DiagnosticsChartView>(R.id.diagChart).submit(row.graph)
    }

    private fun togglePin(target: CaptureTarget) {
        val pin = target.packageName !in settings.pinnedPackages
        val transform = { it: AppSettings ->
            it.copy(pinnedPackages = if (pin) (it.pinnedPackages - target.packageName + target.packageName).takeLast(SettingsValidator.MAX_PINNED) else it.pinnedPackages - target.packageName)
        }
        settings = transform(settings)
        pickerRender?.invoke()
        Thread { try { runSuspend { settingsRepository.update(transform) } } catch (_: Exception) { } }.start()
    }

    private fun selectTarget(target: CaptureTarget) {
        if (!target.available) return
        val transform = { it: AppSettings ->
            it.copy(
                selectedPackage = target.packageName,
                selectedLabel = target.label,
                recentPackages = (listOf(target.packageName) + it.recentPackages.filter { p -> p != target.packageName }).take(SettingsValidator.MAX_RECENT),
            )
        }
        settings = transform(settings)
        Thread {
            runSuspend { settingsRepository.update(transform) }
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
        val missing = runtimePermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.RECORD_AUDIO in missing || (missing.isNotEmpty() && !notificationPermissionAsked)) {
            notificationPermissionAsked = true
            requestPermissions(missing.toTypedArray(), REQUEST_AUDIO)
        } else requestProjection()
    }

    // Notifications are optional: without them the session still runs, the user just loses the status and stop shortcut.
    private fun runtimePermissions(): List<String> =
        listOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

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
            val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioGranted) requestProjection()
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
        TranslationVolume.boostDb = latestSettings.translationBoostDb
        val intent = Intent(this, TranslationForegroundService::class.java)
            .putExtra(TranslationForegroundService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationForegroundService.EXTRA_PROJECTION_DATA, data)
            .putExtra(TranslationForegroundService.EXTRA_API_KEY, latestSettings.apiKey)
            .putExtra(TranslationForegroundService.EXTRA_SELECTED_UID, resolved.uid)
            .putExtra(TranslationForegroundService.EXTRA_TARGET_LANGUAGE, Languages.normalize(latestSettings.targetLanguage))
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
        languageButton.isEnabled = state.phase == SessionPhase.IDLE || state.phase == SessionPhase.ERROR
        if (state.phase != SessionPhase.RUNNING) renderDiagnostics(DiagnosticsSnapshot())
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
