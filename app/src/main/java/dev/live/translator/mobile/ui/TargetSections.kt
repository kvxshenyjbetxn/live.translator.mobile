package dev.live.translator.mobile.ui

import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.CaptureTarget

data class TargetSection(val title: String, val targets: List<CaptureTarget>)

object TargetSections {
    const val PINNED = "Закріплені"
    const val RECENT = "Нещодавні"
    const val MEDIA = "Медіа та ігри"
    const val ALL = "Усі програми"

    fun build(targets: List<CaptureTarget>, settings: AppSettings): List<TargetSection> {
        val byPackage = targets.associateBy { it.packageName }
        val alphabetical = compareBy<CaptureTarget, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
        val pinnedSet = settings.pinnedPackages.toSet()
        val pinned = targets.filter { it.packageName in pinnedSet }.sortedWith(alphabetical)
        val recent = settings.recentPackages.distinct().filter { it !in pinnedSet }.mapNotNull { byPackage[it] }
        val used = (pinned + recent).map { it.packageName }.toSet()
        val media = targets.filter { it.isMedia && it.packageName !in used }.sortedWith(alphabetical)
        val used2 = used + media.map { it.packageName }
        val rest = targets.filter { it.packageName !in used2 }.sortedWith(alphabetical)
        return listOf(TargetSection(PINNED, pinned), TargetSection(RECENT, recent), TargetSection(MEDIA, media), TargetSection(ALL, rest))
            .filter { it.targets.isNotEmpty() }
    }

    fun filter(sections: List<TargetSection>, query: String): List<TargetSection> {
        val q = query.trim()
        if (q.isEmpty()) return sections
        return sections.map { s -> s.copy(targets = s.targets.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }) }.filter { it.targets.isNotEmpty() }
    }
}
