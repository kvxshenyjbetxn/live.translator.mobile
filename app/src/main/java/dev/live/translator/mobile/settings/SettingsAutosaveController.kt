package dev.live.translator.mobile.settings

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import kotlin.coroutines.startCoroutine

class SettingsAutosaveController(
    private val repository: SettingsRepository,
    private val debounceMs: Long = 300,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val onResult: (SaveResult) -> Unit = {},
) {
    @Volatile private var latestValue = ""
    @Volatile private var initialized = false
    @Volatile private var dirty = false
    private var revision = 0L
    private var committedRevision = 0L
    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    fun initialize(value: String) {
        pending?.cancel(false)
        latestValue = value
        revision++
        committedRevision = revision
        dirty = false
        initialized = true
    }

    @Synchronized
    fun textChanged(value: String) {
        if (!initialized) return
        latestValue = value
        val editRevision = ++revision
        dirty = true
        pending?.cancel(false)
        pending = executor.schedule({ persist(value, editRevision) }, debounceMs, TimeUnit.MILLISECONDS)
    }

    fun latest(): String = latestValue

    fun flush(): SaveResult? {
        val result = arrayOfNulls<SaveResult>(1)
        val future: Future<*>
        synchronized(this) {
            pending?.cancel(false)
            if (!initialized || !dirty) return null
            val value = latestValue
            val editRevision = revision
            future = executor.submit { result[0] = persist(value, editRevision) }
        }
        try { future.get(2, TimeUnit.SECONDS) } catch (_: Exception) { return null }
        return result[0]
    }

    fun close() {
        flush()
        executor.shutdown()
    }

    private fun persist(value: String, editRevision: Long): SaveResult {
        val result = runSuspend { repository.update { it.copy(apiKey = value) } }
        if (result is SaveResult.Saved) {
            synchronized(this) {
                committedRevision = editRevision
                dirty = revision != committedRevision
            }
        }
        onResult(result)
        return result
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return result!!.getOrThrow()
    }
}
