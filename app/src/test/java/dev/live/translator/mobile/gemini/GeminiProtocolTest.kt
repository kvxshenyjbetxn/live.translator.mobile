package dev.live.translator.mobile.gemini

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiProtocolTest {
    @Test fun setupAndAudioUseDesktopFramingWithoutKey() {
        val setup = GeminiProtocol.setup()
        assertTrue(setup.contains(GEMINI_MODEL))
        assertTrue(setup.contains("responseModalities"))
        assertTrue(setup.contains("echoTargetLanguage"))
        assertFalse(setup.contains("AIza"))
        val audio = GeminiProtocol.audioMessage(shortArrayOf(0, -32768) + ShortArray(1598))
        assertTrue(audio.contains("audio/pcm;rate=16000"))
        assertEquals("AAAAgA==", Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 0x80.toByte())))
    }

    @Test fun rejectsWrongPacketLengths() {
        assertFailsWith<IllegalArgumentException> { GeminiProtocol.audioMessage(ShortArray(1599)) }
        assertFailsWith<IllegalArgumentException> { GeminiProtocol.audioMessage(ShortArray(1601)) }
    }

    @Test fun parsesReadyAndPcmAndMapsServerCategories() {
        assertEquals(listOf(TranslationAudioEvent.Ready), GeminiProtocol.parseFrame("{\"setupComplete\":{}}".toByteArray()))
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 0x80.toByte()))
        val events = GeminiProtocol.parseFrame("{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"$encoded\"}}]}}}".toByteArray())
        assertEquals(1, events.size)
        assertTrue(events.single() is TranslationAudioEvent.Audio)
        assertEquals(shortArrayOf(0, -32768).toList(), (events.single() as TranslationAudioEvent.Audio).pcm.toList())
        assertFailsWith<ProtocolFailure.Quota> { GeminiProtocol.parseFrame("{\"error\":{\"code\":429}}".toByteArray()) }
        assertFailsWith<ProtocolFailure.Credentials> { GeminiProtocol.parseFrame("{\"error\":{\"code\":403}}".toByteArray()) }
        assertFailsWith<ProtocolFailure.ServiceUnavailable> { GeminiProtocol.parseFrame("{\"error\":{\"code\":503}}".toByteArray()) }
    }

    @Test fun preservesCloseCodeCategories() {
        assertTrue(GeminiProtocol.closeFailure(1007) is ProtocolFailure.Protocol)
        assertTrue(GeminiProtocol.closeFailure(1008) is ProtocolFailure.Credentials)
        assertEquals(1013, (GeminiProtocol.closeFailure(1013) as ProtocolFailure.ServiceUnavailable).code)
        assertTrue(GeminiProtocol.closeFailure(1000) is ProtocolFailure.Closed)
    }

    @Test fun ignoresAdditiveRootFieldsWhileStillRejectingMalformedKnownAudio() {
        assertTrue(GeminiProtocol.parseFrame("{\"additiveControl\":{}}".toByteArray()).isEmpty())
        assertFailsWith<ProtocolFailure.Protocol> {
            GeminiProtocol.parseFrame("{\"additiveControl\":{},\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"AAA=\"}}]}}}".toByteArray())
        }
    }

    @Test fun malformedFramesReportOnlyEnumSubreason() {
        val cases = listOf(
            "bad" to ProtocolRejectReason.INVALID_JSON,
            "{\"error\":false}" to ProtocolRejectReason.INVALID_ERROR,
            "{\"goAway\":false}" to ProtocolRejectReason.INVALID_GO_AWAY,
            "{\"serverContent\":false}" to ProtocolRejectReason.INVALID_SERVER_CONTENT,
            "{\"serverContent\":{\"interrupted\":4}}" to ProtocolRejectReason.INVALID_INTERRUPTED,
            "{\"setupComplete\":null}" to ProtocolRejectReason.INVALID_SETUP,
            "{\"serverContent\":{\"modelTurn\":false}}" to ProtocolRejectReason.INVALID_MODEL_TURN,
            "{\"serverContent\":{\"modelTurn\":{}}}" to ProtocolRejectReason.INVALID_PARTS,
            "{\"serverContent\":{\"modelTurn\":{\"parts\":[null]}}}" to ProtocolRejectReason.INVALID_PART,
            frame("null") to ProtocolRejectReason.INVALID_INLINE_DATA,
            frame("{\"mimeType\":\"audio/pcm;rate=123\",\"data\":\"AAA=\"}") to ProtocolRejectReason.INVALID_MIME,
            frame("{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"!\"}") to ProtocolRejectReason.INVALID_BASE64,
            frame("{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AA==\"}") to ProtocolRejectReason.ODD_AUDIO_BYTES,
        )
        for ((input, expected) in cases) {
            val reasons = mutableListOf<ProtocolRejectReason>()
            assertFailsWith<ProtocolFailure.Protocol> { GeminiProtocol.parseFrame(input.toByteArray(), reasons::add) }
            assertEquals(listOf(expected), reasons)
        }
        val reasons = mutableListOf<ProtocolRejectReason>()
        assertFailsWith<ProtocolFailure.Protocol> { GeminiProtocol.parseFrame(ByteArray(1_048_577), reasons::add) }
        assertEquals(listOf(ProtocolRejectReason.FRAME_TOO_LARGE), reasons)
    }

    private fun frame(inline: String) = "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":$inline}]}}}"

    @Test fun rejectsMalformedAndWrongRateFrames() {
        assertFailsWith<ProtocolFailure.Protocol> { GeminiProtocol.parseFrame("bad".toByteArray()) }
        assertFailsWith<ProtocolFailure.Protocol> {
            GeminiProtocol.parseFrame("{\"setupComplete\":null}".toByteArray())
        }
        assertFailsWith<ProtocolFailure.Protocol> {
            GeminiProtocol.parseFrame("{\"serverContent\":false}".toByteArray())
        }
        assertFailsWith<ProtocolFailure.Protocol> {
            GeminiProtocol.parseFrame("{\"error\":false}".toByteArray())
        }
    }
}
