package dev.live.translator.mobile.gemini

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

const val GEMINI_MODEL = "models/gemini-3.5-live-translate-preview"

sealed class ProtocolFailure : Exception() {
    data object Credentials : ProtocolFailure()
    data object Quota : ProtocolFailure()
    data class ServiceUnavailable(val code: Int) : ProtocolFailure()
    data object Closed : ProtocolFailure()
    data object Protocol : ProtocolFailure()
    data object Network : ProtocolFailure()
    data object Timeout : ProtocolFailure()
    data object Cancelled : ProtocolFailure()
    data object QueueOverflow : ProtocolFailure()
    data object Backpressure : ProtocolFailure()
    data object Playback : ProtocolFailure()
}

enum class ProtocolRejectReason {
    FRAME_TOO_LARGE, INVALID_JSON, INVALID_ERROR, INVALID_GO_AWAY,
    INVALID_SERVER_CONTENT, INVALID_INTERRUPTED, INVALID_SETUP, INVALID_MODEL_TURN,
    INVALID_PARTS, INVALID_PART, INVALID_INLINE_DATA, INVALID_MIME, INVALID_BASE64,
    ODD_AUDIO_BYTES, AUDIO_TOO_LARGE, WEBSOCKET_CLOSE_1007,
}

sealed class TranslationAudioEvent {
    data object Ready : TranslationAudioEvent()
    data class Audio(val pcm: ShortArray) : TranslationAudioEvent()
    data object Closed : TranslationAudioEvent()
}

object GeminiProtocol {
    fun setup(targetLanguage: String = "uk"): String {
        require(targetLanguage == "uk") { "Unsupported target language" }
        return JSONObject().put("setup", JSONObject()
            .put("model", GEMINI_MODEL)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("translationConfig", JSONObject()
                    .put("targetLanguageCode", targetLanguage)
                    .put("echoTargetLanguage", false))))
            .toString()
    }

    fun audioMessage(packet: ShortArray): String {
        require(packet.size == 1600) { "Audio packets must contain exactly 1600 samples" }
        val bytes = ByteArray(packet.size * 2)
        packet.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return JSONObject().put("realtimeInput", JSONObject()
            .put("audio", JSONObject()
                .put("data", Base64.getEncoder().encodeToString(bytes))
                .put("mimeType", "audio/pcm;rate=16000"))).toString()
    }

    fun parseFrame(frame: ByteArray, onReject: (ProtocolRejectReason) -> Unit = {}): List<TranslationAudioEvent> {
        fun reject(reason: ProtocolRejectReason): Nothing {
            onReject(reason)
            throw ProtocolFailure.Protocol
        }
        if (frame.size > MAX_FRAME_BYTES) reject(ProtocolRejectReason.FRAME_TOO_LARGE)
        val root = try { JSONObject(String(frame, Charsets.UTF_8)) } catch (_: Exception) {
            reject(ProtocolRejectReason.INVALID_JSON)
        }
        if (root.has("error")) {
            val error = root.optJSONObject("error") ?: reject(ProtocolRejectReason.INVALID_ERROR)
            throw classifyError(error.optInt("code", -1))
        }
        if (root.has("goAway") && root.optJSONObject("goAway") == null) reject(ProtocolRejectReason.INVALID_GO_AWAY)
        if (root.has("goAway")) throw ProtocolFailure.Closed
        val serverContent = if (root.has("serverContent")) {
            root.optJSONObject("serverContent") ?: reject(ProtocolRejectReason.INVALID_SERVER_CONTENT)
        } else null
        if (serverContent?.has("interrupted") == true) {
            if (serverContent.opt("interrupted") !is Boolean) reject(ProtocolRejectReason.INVALID_INTERRUPTED)
            if (serverContent.optBoolean("interrupted")) throw ProtocolFailure.Closed
        }
        val events = mutableListOf<TranslationAudioEvent>()
        if (root.optJSONObject("setupComplete") != null || root.optJSONObject("setup_complete") != null) {
            events += TranslationAudioEvent.Ready
        } else if (root.has("setupComplete") || root.has("setup_complete")) {
            reject(ProtocolRejectReason.INVALID_SETUP)
        }
        val parts = serverContent?.let { content ->
            if (!content.has("modelTurn")) return@let null
            val modelTurn = content.optJSONObject("modelTurn") ?: reject(ProtocolRejectReason.INVALID_MODEL_TURN)
            if (!modelTurn.has("parts")) reject(ProtocolRejectReason.INVALID_PARTS)
            modelTurn.optJSONArray("parts") ?: reject(ProtocolRejectReason.INVALID_PARTS)
        }
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: reject(ProtocolRejectReason.INVALID_PART)
                if (!part.has("inlineData")) continue
                val inline = part.optJSONObject("inlineData") ?: reject(ProtocolRejectReason.INVALID_INLINE_DATA)
                val mime = inline.optString("mimeType", "")
                if (!mime.split(';').map(String::trim).contains("audio/pcm") ||
                    !mime.split(';').map(String::trim).contains("rate=24000")) reject(ProtocolRejectReason.INVALID_MIME)
                val encoded = inline.optString("data", "")
                val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: Exception) {
                    reject(ProtocolRejectReason.INVALID_BASE64)
                }
                if (decoded.size % 2 != 0) reject(ProtocolRejectReason.ODD_AUDIO_BYTES)
                if (decoded.size > MAX_AUDIO_BYTES) reject(ProtocolRejectReason.AUDIO_TOO_LARGE)
                if (decoded.isNotEmpty()) {
                    val pcm = ShortArray(decoded.size / 2) { offset ->
                        ((decoded[offset * 2].toInt() and 0xff) or (decoded[offset * 2 + 1].toInt() shl 8)).toShort()
                    }
                    events += TranslationAudioEvent.Audio(pcm)
                }
            }
        }
        return events
    }

    fun closeFailure(code: Int): ProtocolFailure = when (code) {
        1007 -> ProtocolFailure.Protocol
        1008 -> ProtocolFailure.Credentials
        1011, 1013 -> ProtocolFailure.ServiceUnavailable(code)
        else -> ProtocolFailure.Closed
    }

    private fun classifyError(code: Int): ProtocolFailure = when (code) {
        400, 401, 403 -> ProtocolFailure.Credentials
        429 -> ProtocolFailure.Quota
        in 500..599 -> ProtocolFailure.ServiceUnavailable(code)
        else -> ProtocolFailure.Network
    }

    private const val MAX_FRAME_BYTES = 1_048_576
    private const val MAX_AUDIO_BYTES = 1_048_576
}
