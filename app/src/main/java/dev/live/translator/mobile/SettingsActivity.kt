package dev.live.translator.mobile

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import dev.live.translator.mobile.audio.TranslationVolume
import dev.live.translator.mobile.gemini.GeminiWebSocketTransport
import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.settings.SaveResult
import dev.live.translator.mobile.settings.SettingsAutosaveController
import dev.live.translator.mobile.settings.SettingsRepository
import dev.live.translator.mobile.ui.applySystemBarInsets
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class SettingsActivity : Activity() {
    private lateinit var repository: SettingsRepository
    private lateinit var autosave: SettingsAutosaveController
    private lateinit var keyInput: EditText
    private lateinit var status: TextView
    private lateinit var boostLabel: TextView
    private lateinit var boostSeekBar: SeekBar
    private var visible = false
    private var settings = AppSettings()
    private var suppressChanges = true
    @Volatile private var userEdited = false
    @Volatile private var latestKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.pageRoot).applySystemBarInsets()
        repository = SettingsRepository.shared(filesDir)
        autosave = SettingsAutosaveController(repository) { result ->
            runOnUiThread {
                status.text = if (result is SaveResult.Saved) getString(R.string.saved) else "Не вдалося зберегти налаштування."
            }
        }
        keyInput = findViewById(R.id.apiKeyInput)
        keyInput.isEnabled = false
        status = findViewById(R.id.settingsStatus)
        boostLabel = findViewById(R.id.boostLabel)
        boostSeekBar = findViewById(R.id.boostSeekBar)
        boostSeekBar.max = AppSettings.MAX_TRANSLATION_BOOST_DB
        boostSeekBar.isEnabled = false
        updateBoostLabel(TranslationVolume.boostDb)
        boostSeekBar.progress = TranslationVolume.boostDb
        boostSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateBoostLabel(progress)
                TranslationVolume.boostDb = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) { seekBar?.let { saveBoost(it.progress) } }
        })
        findViewById<Button>(R.id.toggleKeyButton).setOnClickListener { toggleVisibility() }
        findViewById<Button>(R.id.removeKeyButton).setOnClickListener { keyInput.setText("") }
        findViewById<Button>(R.id.checkButton).setOnClickListener { checkConnection() }
        keyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressChanges) return
                latestKey = s?.toString().orEmpty()
                userEdited = true
                autosave.textChanged(latestKey)
            }
        })
        Thread {
            val loaded = try { runSuspend { repository.load() } } catch (_: Exception) { AppSettings() }
            runOnUiThread {
                if (!userEdited) {
                    settings = loaded
                    latestKey = loaded.apiKey
                    autosave.initialize(loaded.apiKey)
                    suppressChanges = true
                    keyInput.setText(loaded.apiKey)
                    suppressChanges = false
                    keyInput.isEnabled = true
                    boostSeekBar.progress = loaded.translationBoostDb
                    updateBoostLabel(loaded.translationBoostDb)
                    TranslationVolume.boostDb = loaded.translationBoostDb
                    boostSeekBar.isEnabled = true
                    status.text = getString(R.string.saved)
                }
            }
        }.start()
    }

    override fun onPause() {
        autosave.flush()
        super.onPause()
    }

    override fun onDestroy() {
        autosave.close()
        super.onDestroy()
    }

    private fun updateBoostLabel(db: Int) {
        boostLabel.text = if (db > 0) getString(R.string.boost_label, db) else getString(R.string.boost_off)
    }

    private fun saveBoost(value: Int) {
        Thread {
            val result = try { runSuspend { repository.update { it.copy(translationBoostDb = value) } } } catch (_: Exception) { null }
            runOnUiThread {
                status.text = if (result is SaveResult.Saved) getString(R.string.saved) else "Не вдалося зберегти налаштування."
            }
        }.start()
    }

    private fun toggleVisibility() {
        visible = !visible
        keyInput.inputType = if (visible) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.setSelection(keyInput.length())
        findViewById<Button>(R.id.toggleKeyButton).text = if (visible) "Сховати ключ" else "Показати ключ"
    }

    private fun checkConnection() {
        val key = keyInput.text.toString()
        if (key.isBlank()) { status.text = "Введіть API-ключ."; return }
        findViewById<Button>(R.id.checkButton).isEnabled = false
        status.text = "Перевірка з’єднання…"
        Thread {
            val message = try {
                runSuspend { GeminiWebSocketTransport().checkConnection(key) }
                "З’єднання підтверджено. Це не гарантує квоту або тривалий доступ."
            } catch (_: Exception) {
                "Не вдалося перевірити з’єднання. Перевірте ключ, мережу та доступ проєкту."
            }
            runOnUiThread {
                status.text = message
                findViewById<Button>(R.id.checkButton).isEnabled = true
            }
        }.start()
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return result!!.getOrThrow()
    }
}
