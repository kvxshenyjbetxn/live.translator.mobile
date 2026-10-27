package dev.live.translator.mobile.settings

import dev.live.translator.mobile.model.AccentPreset
import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.ThemeMode
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed class SaveResult {
    data object Saved : SaveResult()
    data class Failed(val category: FailureCategory) : SaveResult()
}

enum class FailureCategory { INVALID, IO, CORRUPT, OVERSIZED }

class SettingsException(val category: FailureCategory) : Exception()

class SettingsRepository(
    private val directory: File,
    private val maxBytes: Long = 16 * 1024,
) {
    private val lock = Any()
    private val settingsFile get() = File(directory, FILE_NAME)
    private val temporaryFile get() = File(directory, "$FILE_NAME.tmp")
    @Volatile private var current: AppSettings? = null

    suspend fun load(): AppSettings = synchronized(lock) {
        val parsed = readFromDisk()
        current = parsed
        parsed
    }

    /** Synchronous read for theming before the first frame; falls back to defaults if unreadable. */
    fun peek(): AppSettings = synchronized(lock) {
        current ?: try { readFromDisk().also { current = it } } catch (_: SettingsException) { AppSettings() }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings): SaveResult = synchronized(lock) {
        val base = current ?: try { readFromDisk() } catch (error: SettingsException) {
            return@synchronized SaveResult.Failed(error.category)
        }
        val next = try { SettingsValidator.validate(transform(base)) } catch (_: SettingsValidationError) {
            return@synchronized SaveResult.Failed(FailureCategory.INVALID)
        }
        val result = write(next)
        if (result is SaveResult.Saved) current = next
        result
    }

    suspend fun flush(): SaveResult = synchronized(lock) {
        val snapshot = current ?: return@synchronized SaveResult.Saved
        val result = write(snapshot)
        if (result is SaveResult.Saved) current = snapshot
        result
    }

    private fun readFromDisk(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        if (settingsFile.length() > maxBytes) throw SettingsException(FailureCategory.OVERSIZED)
        return try {
            parse(settingsFile.readText(Charsets.UTF_8))
        } catch (_: SettingsValidationError) {
            throw SettingsException(FailureCategory.INVALID)
        } catch (_: Exception) {
            throw SettingsException(FailureCategory.CORRUPT)
        }
    }

    private fun write(settings: AppSettings): SaveResult {
        val json = encode(settings)
        if (json.toByteArray(Charsets.UTF_8).size > maxBytes) return SaveResult.Failed(FailureCategory.OVERSIZED)
        return try {
            directory.mkdirs()
            FileOutputStream(temporaryFile).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporaryFile.toPath(), settingsFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            SaveResult.Saved
        } catch (_: Exception) {
            temporaryFile.delete()
            SaveResult.Failed(FailureCategory.IO)
        }
    }

    private fun parse(text: String): AppSettings {
        val json = org.json.JSONObject(text)
        val allowed = setOf("schemaVersion", "apiKey", "selectedPackage", "selectedLabel", "targetLanguage", "pinnedPackages", "recentPackages", "translationBoostDb", "theme", "accent")
        json.keys().asSequence().forEach { if (it !in allowed) throw SettingsValidationError.UnsupportedSchema }
        val settings = AppSettings(
            schemaVersion = json.getInt("schemaVersion"),
            apiKey = json.getString("apiKey"),
            selectedPackage = if (json.isNull("selectedPackage")) null else json.getString("selectedPackage"),
            selectedLabel = if (json.isNull("selectedLabel")) null else json.getString("selectedLabel"),
            targetLanguage = json.getString("targetLanguage"),
            pinnedPackages = readList(json, "pinnedPackages"),
            recentPackages = readList(json, "recentPackages"),
            translationBoostDb = json.optInt("translationBoostDb", AppSettings.DEFAULT_TRANSLATION_BOOST_DB),
            theme = ThemeMode.fromName(json.optString("theme", "")),
            accent = AccentPreset.fromName(json.optString("accent", "")),
        )
        return SettingsValidator.validate(settings)
    }

    private fun readList(json: org.json.JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.getString(it) }
    }

    private fun encode(settings: AppSettings): String = org.json.JSONObject()
        .put("schemaVersion", settings.schemaVersion)
        .put("apiKey", settings.apiKey)
        .put("selectedPackage", settings.selectedPackage)
        .put("selectedLabel", settings.selectedLabel)
        .put("targetLanguage", settings.targetLanguage)
        .put("pinnedPackages", org.json.JSONArray(settings.pinnedPackages))
        .put("recentPackages", org.json.JSONArray(settings.recentPackages))
        .put("translationBoostDb", settings.translationBoostDb)
        .put("theme", settings.theme.name)
        .put("accent", settings.accent.name)
        .toString()

    companion object {
        private const val FILE_NAME = "settings.json"
        private val sharedRepositories = HashMap<String, SettingsRepository>()

        fun shared(directory: File): SettingsRepository = synchronized(sharedRepositories) {
            val key = directory.absoluteFile.normalize().path
            sharedRepositories.getOrPut(key) { SettingsRepository(directory) }
        }
    }
}
