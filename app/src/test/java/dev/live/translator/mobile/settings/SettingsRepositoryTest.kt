package dev.live.translator.mobile.settings

import dev.live.translator.mobile.model.AppSettings
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    private fun <T> runSuspend(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return value!!.getOrThrow()
    }

    @Test fun roundTripsAndFlushesValidatedSettings() {
        val directory = Files.createTempDirectory("mobile-settings").toFile()
        val repository = SettingsRepository(directory)
        assertEquals(AppSettings(), runSuspend { repository.load() })
        val result = runSuspend {
            repository.update { it.copy(apiKey = "AIza-test-only", selectedPackage = "com.example.video", selectedLabel = "Video") }
        }
        assertEquals(SaveResult.Saved, result)
        val loaded = runSuspend { SettingsRepository(directory).load() }
        assertEquals("AIza-test-only", loaded.apiKey)
        assertEquals("com.example.video", loaded.selectedPackage)
        assertTrue(java.io.File(directory, "settings.json").readText().contains("AIza-test-only"))
    }

    @Test fun sharedClientsApplyFieldTransformsWithoutResurrectingDeletedKey() {
        val directory = Files.createTempDirectory("mobile-settings-shared").toFile()
        val first = SettingsRepository.shared(directory)
        val second = SettingsRepository.shared(directory)
        assertTrue(first === second)
        runSuspend { first.update { it.copy(apiKey = "AIza-test-only") } }
        runSuspend { second.update { it.copy(selectedPackage = "com.video", selectedLabel = "Video") } }
        runSuspend { first.update { it.copy(apiKey = "") } }
        runSuspend { second.update { it.copy(selectedPackage = "com.music", selectedLabel = "Music") } }
        val loaded = runSuspend { first.load() }
        assertEquals("", loaded.apiKey)
        assertEquals("com.music", loaded.selectedPackage)
    }

    @Test fun corruptFileIsRejected() {
        val directory = Files.createTempDirectory("mobile-settings-corrupt").toFile()
        java.io.File(directory, "settings.json").writeText("not-json")
        val corrupt = try { runSuspend { SettingsRepository(directory).load() }; null } catch (error: SettingsException) { error }
        assertEquals(FailureCategory.CORRUPT, corrupt?.category)
    }
}
