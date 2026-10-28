package dev.live.translator.mobile.settings

import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.Languages

sealed class SettingsValidationError : Exception() {
    data object UnsupportedSchema : SettingsValidationError()
    data object InvalidApiKey : SettingsValidationError()
    data object InvalidPackage : SettingsValidationError()
    data object InvalidLabel : SettingsValidationError()
    data object UnsupportedLanguage : SettingsValidationError()
    data object InvalidBoost : SettingsValidationError()
}

object SettingsValidator {
    private const val MAX_KEY_BYTES = 4096
    private const val MAX_PACKAGE_LENGTH = 256
    private const val MAX_LABEL_LENGTH = 512
    private val keyPattern = Regex("[A-Za-z0-9._-]+")

    const val MAX_PINNED = 50
    const val MAX_RECENT = 5

    private fun cleanPackages(values: List<String>, max: Int): List<String> =
        values.filter { it.isNotBlank() && it.length <= MAX_PACKAGE_LENGTH && keyPattern.matches(it) }.distinct().take(max)

    fun validate(settings: AppSettings): AppSettings {
        if (settings.schemaVersion != AppSettings.CURRENT_SCHEMA) throw SettingsValidationError.UnsupportedSchema
        if (settings.apiKey.isNotEmpty() &&
            (settings.apiKey.toByteArray(Charsets.UTF_8).size > MAX_KEY_BYTES ||
                !keyPattern.matches(settings.apiKey))
        ) throw SettingsValidationError.InvalidApiKey
        if (settings.selectedPackage != null &&
            (settings.selectedPackage.isBlank() || settings.selectedPackage.length > MAX_PACKAGE_LENGTH ||
                !settings.selectedPackage.matches(Regex("[A-Za-z0-9._-]+")))) {
            throw SettingsValidationError.InvalidPackage
        }
        if (settings.selectedLabel != null &&
            (settings.selectedLabel.isBlank() || settings.selectedLabel.length > MAX_LABEL_LENGTH)) {
            throw SettingsValidationError.InvalidLabel
        }
        if (!Languages.isSupported(settings.targetLanguage)) throw SettingsValidationError.UnsupportedLanguage
        if (settings.translationBoostDb !in 0..AppSettings.MAX_TRANSLATION_BOOST_DB) throw SettingsValidationError.InvalidBoost
        val language = Languages.normalize(settings.targetLanguage)
        val pinned = cleanPackages(settings.pinnedPackages, MAX_PINNED)
        val recent = cleanPackages(settings.recentPackages, MAX_RECENT)
        return if (language == settings.targetLanguage && pinned == settings.pinnedPackages && recent == settings.recentPackages) settings
        else settings.copy(targetLanguage = language, pinnedPackages = pinned, recentPackages = recent)
    }
}
