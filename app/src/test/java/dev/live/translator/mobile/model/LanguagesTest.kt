package dev.live.translator.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguagesTest {
    @Test fun listsDesktopLanguagesAndNormalizes() {
        assertEquals(listOf("uk", "en", "pl", "de"), Languages.all.map { it.code })
        assertEquals("Deutsch", Languages.nameOf("de"))
        assertEquals("uk", Languages.normalize(null))
        assertEquals("uk", Languages.normalize("fr"))
        assertEquals("pl", Languages.normalize("pl"))
    }
}
