package dev.live.translator.mobile.ui

import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.CaptureTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetSectionsTest {
    private val targets = listOf(
        CaptureTarget("a.zeta", "Zeta", 1),
        CaptureTarget("a.alpha", "alpha", 2),
        CaptureTarget("a.game", "Game", 3, isMedia = true),
        CaptureTarget("a.music", "Music", 4, isMedia = true),
        CaptureTarget("a.pinmedia", "Pinned Media", 5, isMedia = true),
        CaptureTarget("a.recent", "Recent", 6),
        CaptureTarget("a.gone", "Gone", -1, available = false),
    )

    @Test fun ordersSectionsWithoutDuplicates() {
        val settings = AppSettings(
            pinnedPackages = listOf("a.zeta", "a.pinmedia"),
            recentPackages = listOf("a.recent", "a.zeta", "a.music", "a.missing"),
            selectedPackage = "a.gone",
        )
        val sections = TargetSections.build(targets, settings)
        assertEquals(listOf(TargetSections.PINNED, TargetSections.RECENT, TargetSections.MEDIA, TargetSections.ALL), sections.map { it.title })
        assertEquals(listOf("a.pinmedia", "a.zeta"), sections[0].targets.map { it.packageName })
        assertEquals(listOf("a.recent", "a.music"), sections[1].targets.map { it.packageName })
        assertEquals(listOf("a.game"), sections[2].targets.map { it.packageName })
        assertEquals(listOf("a.alpha", "a.gone"), sections[3].targets.map { it.packageName })
        assertEquals(targets.size, sections.sumOf { it.targets.size })
    }

    @Test fun omitsEmptySections() {
        val sections = TargetSections.build(listOf(CaptureTarget("a.b", "B", 1)), AppSettings())
        assertEquals(listOf(TargetSections.ALL), sections.map { it.title })
    }

    @Test fun filtersByLabelOrPackageCaseInsensitive() {
        val sections = TargetSections.build(targets, AppSettings())
        assertEquals(listOf("a.music"), TargetSections.filter(sections, " MUS ").flatMap { it.targets }.map { it.packageName })
        assertEquals(listOf("a.zeta"), TargetSections.filter(sections, "a.ZET").flatMap { it.targets }.map { it.packageName })
        assertEquals(emptyList(), TargetSections.filter(sections, "nothing"))
    }
}
