package dev.live.translator.mobile.source

import dev.live.translator.mobile.model.CaptureTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceCatalogTest {
    @Test fun filtersOwnUidAndDeduplicatesPackages() {
        val result = SourceCatalog.filterTargets(
            listOf(
                CaptureTarget("dev.live.translator.mobile", "Translator", 42),
                CaptureTarget("com.video", "Video", 50),
                CaptureTarget("com.video", "Video duplicate", 50),
                CaptureTarget("com.music", "Music", 51),
                CaptureTarget("com.other", "Own uid", 42),
            ),
            ownPackage = "dev.live.translator.mobile",
            ownUid = 42,
        )
        assertEquals(listOf("com.music", "com.video"), result.map { it.packageName })
    }
}
