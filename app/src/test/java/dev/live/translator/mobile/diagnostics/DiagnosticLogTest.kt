package dev.live.translator.mobile.diagnostics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogTest {
    @Test fun redactsKeysPathsUrlsAndPayloads() {
        val lines = mutableListOf<String>()
        DiagnosticLog(lines::add).event(
            "session-start",
            mapOf(
                "key" to "AIza-test-only",
                "path" to "/private/settings.json",
                "url" to "wss://generativelanguage.googleapis.com/private",
                "payload" to "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=",
            ),
        )
        val output = lines.single()
        assertTrue(output.contains("event=session-start"))
        assertFalse(output.contains("AIza-test-only"))
        assertFalse(output.contains("settings.json"))
        assertFalse(output.contains("wss://"))
        assertFalse(output.contains("QUJD"))
    }
}
