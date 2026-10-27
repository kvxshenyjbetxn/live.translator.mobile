package dev.live.translator.mobile.settings

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsAutosaveControllerTest {
    private fun <T> runSuspend(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return value!!.getOrThrow()
    }

    @Test fun flushPersistsLatestTextOnNavigation() {
        val directory = Files.createTempDirectory("mobile-autosave").toFile()
        val repository = SettingsRepository.shared(directory)
        val autosave = SettingsAutosaveController(repository, debounceMs = 60)
        autosave.initialize("")
        autosave.textChanged("AIza-first")
        autosave.textChanged("AIza-latest")
        autosave.flush()
        autosave.close()
        assertEquals("AIza-latest", runSuspend { repository.load() }.apiKey)
    }

    @Test fun deletionCannotBeResurrectedBySourceUpdate() {
        val directory = Files.createTempDirectory("mobile-autosave-delete").toFile()
        val repository = SettingsRepository.shared(directory)
        val autosave = SettingsAutosaveController(repository, debounceMs = 60)
        autosave.initialize("")
        autosave.textChanged("AIza-old")
        autosave.flush()
        autosave.textChanged("")
        autosave.flush()
        runSuspend { repository.update { it.copy(selectedPackage = "com.video") } }
        autosave.close()
        val settings = runSuspend { repository.load() }
        assertEquals("", settings.apiKey)
        assertEquals("com.video", settings.selectedPackage)
    }

    @Test fun deletionDuringInFlightWriteIsPersistedByDebounce() {
        deletionDuringInFlightWrite(flush = false)
    }

    @Test fun deletionDuringInFlightWriteIsPersistedByNavigationFlush() {
        deletionDuringInFlightWrite(flush = true)
    }

    private fun deletionDuringInFlightWrite(flush: Boolean) {
        val directory = Files.createTempDirectory("mobile-autosave-inflight").toFile()
        val repository = SettingsRepository(directory)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val flushQueued = CountDownLatch(1)
        val scheduled = AtomicInteger()
        val executor = object : ScheduledThreadPoolExecutor(1) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                val future = super.schedule(command, delay, unit)
                if (scheduled.incrementAndGet() == 3) flushQueued.countDown()
                return future
            }

            override fun beforeExecute(thread: Thread, task: Runnable) {
                super.beforeExecute(thread, task)
                started.countDown()
            }
        }
        val blocker = thread {
            runSuspend { repository.update { locked.countDown(); release.await(); it } }
        }
        val autosave = SettingsAutosaveController(repository, debounceMs = 0, executor = executor)
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            autosave.initialize("")
            autosave.textChanged("AIza-inflight")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            autosave.textChanged("")
            val navigation = if (flush) thread { autosave.flush() } else null
            if (flush) assertTrue(flushQueued.await(2, TimeUnit.SECONDS))
            release.countDown()
            blocker.join(2000)
            navigation?.join(2000)
            if (navigation != null) assertTrue(!navigation.isAlive)
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals("", runSuspend { repository.load() }.apiKey)
        } finally {
            release.countDown()
            blocker.join(2000)
            autosave.close()
        }
    }

    @Test fun preInitializationEditCannotOverwriteLoadedValue() {
        val directory = Files.createTempDirectory("mobile-autosave-preload").toFile()
        val repository = SettingsRepository.shared(directory)
        runSuspend { repository.update { it.copy(apiKey = "AIza-loaded") } }
        val autosave = SettingsAutosaveController(repository)
        autosave.textChanged("AIza-early")
        autosave.initialize("AIza-loaded")
        assertEquals(null, autosave.flush())
        autosave.close()
        assertEquals("AIza-loaded", runSuspend { repository.load() }.apiKey)
    }

    @Test fun existingKeyAndNoEditsDoesNotFlushAsDeletion() {
        val directory = Files.createTempDirectory("mobile-autosave-clean").toFile()
        val repository = SettingsRepository.shared(directory)
        runSuspend { repository.update { it.copy(apiKey = "AIza-existing") } }
        val autosave = SettingsAutosaveController(repository)
        autosave.initialize("AIza-existing")
        assertEquals(null, autosave.flush())
        autosave.close()
        assertEquals("AIza-existing", runSuspend { repository.load() }.apiKey)
    }
}
