package dev.live.translator.mobile.audio

import dev.live.translator.mobile.model.AppSettings
import java.util.concurrent.CopyOnWriteArraySet

object TranslationVolume {
    private val listeners = CopyOnWriteArraySet<(Int) -> Unit>()

    @Volatile var boostDb: Int = AppSettings.DEFAULT_TRANSLATION_BOOST_DB
        set(value) {
            val next = value.coerceIn(0, AppSettings.MAX_TRANSLATION_BOOST_DB)
            if (next == field) return
            field = next
            listeners.forEach { it(next) }
        }

    fun addListener(listener: (Int) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Int) -> Unit) { listeners.remove(listener) }
}
