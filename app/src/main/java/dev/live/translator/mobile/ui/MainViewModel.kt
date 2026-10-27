package dev.live.translator.mobile.ui

import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.CaptureTarget

class MainViewModel {
    var settings: AppSettings = AppSettings()
        private set
    var targets: List<CaptureTarget> = emptyList()
        private set

    fun setTargets(value: List<CaptureTarget>) { targets = value }
    fun setApiKey(value: String) { settings = settings.copy(apiKey = value) }
    fun selectTarget(target: CaptureTarget) { settings = settings.copy(selectedPackage = target.packageName, selectedLabel = target.label) }
    fun canStart(): Boolean = settings.apiKey.isNotBlank() &&
        targets.any { it.packageName == settings.selectedPackage && it.available }

    companion object {
        fun sourceSummary(target: CaptureTarget?): String = when {
            target == null -> "Джерело: виберіть програму ▾"
            !target.available -> "Джерело: ${target.label} (недоступна) ▾"
            else -> "Джерело: ${target.label} ▾"
        }

        fun withRetainedSelection(visible: List<CaptureTarget>, settings: AppSettings): List<CaptureTarget> {
            val retained = settings.selectedPackage?.takeIf { packageName -> visible.none { it.packageName == packageName } }?.let {
                CaptureTarget(it, settings.selectedLabel ?: it, -1, available = false)
            }
            return visible + listOfNotNull(retained)
        }
    }
}
