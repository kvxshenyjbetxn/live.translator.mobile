package dev.live.translator.mobile.settings

import dev.live.translator.mobile.model.AppSettings

sealed class SettingsValidationError : Exception() {
    data object UnsupportedSchema : SettingsValidationError()
    data object InvalidApiKey : SettingsValidationError()
    data object InvalidPackage : SettingsValidationError()
    data object InvalidLabel : SettingsValidationError()
    data object UnsupportedLanguage : SettingsValidationError()
}

object SettingsValidator {
    private const val MAX_KEY_BYTES = 4096
    private const val MAX_PACKAGE_LENGTH = 256
    private const val MAX_LABEL_LENGTH = 512
    private val keyPattern = Regex("[A-Za-z0-9._-]+")

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
        if (settings.targetLanguage != AppSettings.TARGET_LANGUAGE) throw SettingsValidationError.UnsupportedLanguage
        return settings
    }
}
