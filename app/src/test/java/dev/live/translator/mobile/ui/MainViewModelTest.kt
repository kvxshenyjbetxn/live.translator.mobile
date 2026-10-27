package dev.live.translator.mobile.ui

import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.CaptureTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainViewModelTest {
    @Test fun sourceSummaryShowsSelectedUnavailableAndEmptyStates() {
        assertEquals("Виберіть програму ▾", MainViewModel.sourceSummary(null))
        assertEquals("Video ▾", MainViewModel.sourceSummary(CaptureTarget("com.video", "Video", 10)))
        assertEquals("Video (недоступна) ▾", MainViewModel.sourceSummary(CaptureTarget("com.video", "Video", -1, available = false)))
    }

    @Test fun retainedSelectionRemainsVisibleAsUnavailable() {
        val displayed = MainViewModel.withRetainedSelection(
            listOf(CaptureTarget("com.video", "Video", 10)),
            AppSettings(selectedPackage = "com.missing", selectedLabel = "Missing"),
        )
        assertTrue(displayed.last().packageName == "com.missing")
        assertFalse(displayed.last().available)
    }

    @Test fun startRequiresKeyAndAvailableSelectedTarget() {
        val viewModel = MainViewModel()
        val target = CaptureTarget("com.video", "Video", 10)
        viewModel.setTargets(listOf(target))
        assertFalse(viewModel.canStart())
        viewModel.selectTarget(target)
        assertFalse(viewModel.canStart())
        viewModel.setApiKey("AIza-test-only")
        assertTrue(viewModel.canStart())
    }
}
