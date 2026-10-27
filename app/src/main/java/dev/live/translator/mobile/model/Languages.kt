package dev.live.translator.mobile.model

data class Language(val code: String, val name: String)

object Languages {
    val all = listOf(Language("uk", "Українська"), Language("en", "English"), Language("pl", "Polski"), Language("de", "Deutsch"))
    fun isSupported(code: String?): Boolean = all.any { it.code == code }
    fun normalize(code: String?): String = if (isSupported(code)) code!! else AppSettings.TARGET_LANGUAGE
    fun nameOf(code: String?): String = all.first { it.code == normalize(code) }.name
}
