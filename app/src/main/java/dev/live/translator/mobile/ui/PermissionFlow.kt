package dev.live.translator.mobile.ui

class ConsentRequestGate {
    data class Request(val generation: Long, val packageName: String)
    private var generation = 0L
    private var pending: Request? = null

    @Synchronized
    fun begin(packageName: String): Request? {
        if (pending != null) return null
        val request = Request(++generation, packageName)
        pending = request
        return request
    }

    @Synchronized
    fun current(): Request? = pending

    @Synchronized
    fun consume(): Request? = pending.also { pending = null }

    @Synchronized
    fun invalidate() {
        generation++
        pending = null
    }

    @Synchronized
    fun isCurrent(request: Request): Boolean = pending == request && request.generation == generation
}
