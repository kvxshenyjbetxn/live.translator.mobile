package dev.live.translator.mobile.settings

import dev.live.translator.mobile.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SettingsValidatorTest {
    @Test fun rejectsUnsupportedSchemaAndLanguage() {
        assertFailsWith<SettingsValidationError.UnsupportedSchema> {
            SettingsValidator.validate(AppSettings(schemaVersion = 2))
        }
        assertFailsWith<SettingsValidationError.UnsupportedLanguage> {
            SettingsValidator.validate(AppSettings(targetLanguage = "en"))
        }
    }

    @Test fun rejectsInvalidKeysAndPackages() {
        assertFailsWith<SettingsValidationError.InvalidApiKey> {
            SettingsValidator.validate(AppSettings(apiKey = "bad key"))
        }
        assertFailsWith<SettingsValidationError.InvalidPackage> {
            SettingsValidator.validate(AppSettings(selectedPackage = " "))
        }
    }

    @Test fun acceptsEmptyAndValidSettings() {
        val settings = AppSettings(apiKey = "AIza-test_only-123", selectedPackage = "com.example.player")
        assertSame(settings, SettingsValidator.validate(settings))
    }
}
