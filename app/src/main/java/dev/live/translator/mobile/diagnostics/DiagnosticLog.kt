package dev.live.translator.mobile.diagnostics

import android.util.Log

class DiagnosticLog(private val sink: (String) -> Unit = { Log.i(TAG, it) }) {
    fun event(name: String, fields: Map<String, String> = emptyMap()) {
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "-").take(80)
        val suffix = fields.toSortedMap().entries.joinToString(" ") { (key, value) ->
            "${key.replace(Regex("[^a-zA-Z0-9._-]"), "-")}=${redact(value)}"
        }
        sink(if (suffix.isEmpty()) "event=$safeName" else "event=$safeName $suffix")
    }

    private fun redact(value: String): String {
        if (value.length > 160 || value.contains("AIza") || value.contains("wss://") ||
            value.contains("settings.json") || looksLikeBase64(value)) return "[redacted]"
        return value.replace(Regex("[\\r\\n\\t]"), " ")
    }

    private fun looksLikeBase64(value: String): Boolean = value.length >= 24 &&
        value.matches(Regex("[A-Za-z0-9+/=]+"))

    companion object { const val TAG = "LiveTranslator" }
}
