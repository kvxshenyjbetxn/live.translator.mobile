package dev.live.translator.mobile.model

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguagesTest {
    @Test fun catalogHasExactOrderedCodesAndReadableUniqueNames() {
        val expected = """uk af ak sq am ar hy az eu be bn bg my ca zh-Hans zh-Hant hr cs da nl en et fil fi fr gl ka de el gu ha he hi hu is id it ja jv kn kk km rw ko lo lv lt mk ms ml mr mn ne no fa pl pt-BR pt-PT pa ro ru sr sd si sk sl es su sw sv ta te th tr ur uz vi zu""".split(" ")
        val codes = Languages.all.map { it.code }
        assertEquals(78, codes.size)
        assertEquals(expected, codes)
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(Languages.all.all { it.name.isNotBlank() })
        assertEquals("Українська", Languages.nameOf("uk"))
        assertEquals("English", Languages.nameOf("en"))
        assertEquals("Polski", Languages.nameOf("pl"))
        assertEquals("Deutsch", Languages.nameOf("de"))
        assertEquals("uk", AppSettings.TARGET_LANGUAGE)
    }

    @Test fun normalizesNorwegianAliasAndUnsupportedCodes() {
        assertTrue(Languages.isSupported("no"))
        assertTrue(Languages.isSupported("nb"))
        assertFalse(Languages.isSupported("xx"))
        assertEquals("no", Languages.normalize("nb"))
        assertEquals("no", Languages.normalize("no"))
        assertEquals(Languages.nameOf("no"), Languages.nameOf("nb"))
        assertEquals("uk", Languages.normalize(null))
        assertEquals("uk", Languages.normalize("xx"))
        listOf("zh-Hans", "zh-Hant", "pt-BR", "pt-PT").forEach { assertEquals(it, Languages.normalize(it)) }
    }

    @Test fun pickerKeepsTitleAndCloseOutsideWeightedScrollViewport() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(File("src/main/res/layout/dialog_language_picker.xml")).documentElement
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        assertEquals(listOf("TextView", "ScrollView", "Button"), children.map { it.tagName })
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("match_parent", root.getAttributeNS(android, "layout_height"))
        assertEquals("0dp", children[1].getAttributeNS(android, "layout_height"))
        assertEquals("1", children[1].getAttributeNS(android, "layout_weight"))
        assertEquals("@+id/languageList", (children[1].firstChild.nextSibling as org.w3c.dom.Element).getAttributeNS(android, "id"))
        assertEquals("@+id/languageClose", children[2].getAttributeNS(android, "id"))
    }
}
