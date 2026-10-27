package dev.live.translator.mobile.audio

/** Lifecycle slot used by capture resources so Stop and failed initialization release exactly once. */
class CloseOnceResource<T>(private val release: (T) -> Unit) {
    private var resource: T? = null
    private var closed = false

    @Synchronized
    fun publish(candidate: T): Boolean {
        if (closed) {
            release(candidate)
            return false
        }
        check(resource == null) { "Resource already published" }
        resource = candidate
        return true
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        val current = resource
        resource = null
        current?.let(release)
    }

    @Synchronized
    fun isClosed(): Boolean = closed
}
