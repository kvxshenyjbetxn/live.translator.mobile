package dev.live.translator.mobile.model

data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val apiKey: String = "",
    val selectedPackage: String? = null,
    val selectedLabel: String? = null,
    val targetLanguage: String = TARGET_LANGUAGE,
    val pinnedPackages: List<String> = emptyList(),
    val recentPackages: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
        const val TARGET_LANGUAGE = "uk"
    }
}
