package dev.live.translator.mobile.service

/** Single-start generation gate; stale async startup completions cannot install a session. */
class SessionGenerationGate {
    private var generation = 0L
    private var active: Long? = null

    @Synchronized
    fun begin(): Long? {
        if (active != null) return null
        generation++
        active = generation
        return active
    }

    @Synchronized
    fun isCurrent(id: Long): Boolean = active == id && generation == id

    @Synchronized
    fun complete(id: Long): Boolean {
        if (!isCurrent(id)) return false
        active = null
        return true
    }

    @Synchronized
    fun invalidate() {
        generation++
        active = null
    }

    @Synchronized
    fun clearIf(id: Long) {
        if (active == id) active = null
    }
}
