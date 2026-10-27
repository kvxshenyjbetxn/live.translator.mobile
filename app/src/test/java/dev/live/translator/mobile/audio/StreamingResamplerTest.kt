package dev.live.translator.mobile.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingResamplerTest {
    @Test fun constantSignalMaintainsStreamingRatioAndPacketBoundaries() {
        val resampler = StreamingResampler(48_000)
        val packetizer = PcmPacketizer()
        var packetCount = 0
        repeat(10) { packetCount += packetizer.add(resampler.accept(ShortArray(4_800) { 123 })).size }
        assertEquals(10, packetCount)
    }

    @Test fun phaseContinuesAcrossReads() {
        val resampler = StreamingResampler(48_000)
        assertEquals(listOf<Short>(1), resampler.accept(shortArrayOf(1, 2)).toList())
        assertEquals(listOf<Short>(4), resampler.accept(shortArrayOf(3, 4, 5)).toList())
    }

    @Test fun unsupportedRateFailsExplicitly() {
        assertFailsWith<UnsupportedSampleRate> { StreamingResampler(44_100) }
    }
}
