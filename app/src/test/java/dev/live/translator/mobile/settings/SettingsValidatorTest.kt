package dev.live.translator.mobile.settings

import dev.live.translator.mobile.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SettingsValidatorTest {
    @Test fun rejectsUnsupportedSchemaAndLanguage() {
        assertFailsWith<SettingsValidationError.UnsupportedSchema> {
            SettingsValidator.validate(AppSettings(schemaVersion = 2))
        }
        assertFailsWith<SettingsValidationError.UnsupportedLanguage> {
            SettingsValidator.validate(AppSettings(targetLanguage = "fr"))
        }
        assertFailsWith<SettingsValidationError.UnsupportedLanguage> {
            SettingsValidator.validate(AppSettings(targetLanguage = ""))
        }
    }

    @Test fun acceptsAllSupportedLanguages() {
        listOf("uk", "en", "pl", "de").forEach { assertEquals(it, SettingsValidator.validate(AppSettings(targetLanguage = it)).targetLanguage) }
    }

    @Test fun rejectsInvalidKeysAndPackages() {
        assertFailsWith<SettingsValidationError.InvalidApiKey> {
            SettingsValidator.validate(AppSettings(apiKey = "bad key"))
        }
        assertFailsWith<SettingsValidationError.InvalidPackage> {
            SettingsValidator.validate(AppSettings(selectedPackage = " "))
        }
    }

    @Test fun rejectsOutOfRangeBoost() {
        listOf(-1, 21, 100).forEach {
            assertFailsWith<SettingsValidationError.InvalidBoost> { SettingsValidator.validate(AppSettings(translationBoostDb = it)) }
        }
        listOf(0, 6, 20).forEach { assertEquals(it, SettingsValidator.validate(AppSettings(translationBoostDb = it)).translationBoostDb) }
    }

    @Test fun acceptsEmptyAndValidSettings() {
        val settings = AppSettings(apiKey = "AIza-test_only-123", selectedPackage = "com.example.player")
        assertSame(settings, SettingsValidator.validate(settings))
    }

    @Test fun trimsListsInsteadOfFailing() {
        val many = (1..60).map { "com.app$it" }
        val result = SettingsValidator.validate(AppSettings(pinnedPackages = many + "bad name", recentPackages = many.take(8) + many.take(2)))
        assertEquals(50, result.pinnedPackages.size)
        assertEquals(many.take(5), result.recentPackages)
    }
}
