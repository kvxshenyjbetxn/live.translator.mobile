package dev.live.translator.mobile.audio

class UnsupportedSampleRate(message: String) : Exception(message)

/** Streaming 48 kHz -> 16 kHz conversion. It intentionally refuses unsupported rates. */
class StreamingResampler(inputRate: Int) {
    private val factor: Int
    private var phase = 0

    init {
        if (inputRate != 48_000) throw UnsupportedSampleRate("Підтримується лише 48 кГц")
        factor = inputRate / OUTPUT_RATE
    }

    fun accept(samples: ShortArray, count: Int = samples.size): ShortArray {
        require(count in 0..samples.size)
        val output = ShortArray((count + factor - 1) / factor)
        var size = 0
        for (index in 0 until count) {
            if (phase == 0) output[size++] = samples[index]
            phase = (phase + 1) % factor
        }
        return output.copyOf(size)
    }

    companion object { const val OUTPUT_RATE = 16_000 }
}

class PcmPacketizer(private val packetSize: Int = PACKET_SAMPLES) {
    private val pending = ArrayList<Short>(packetSize)

    fun add(samples: ShortArray): List<ShortArray> {
        val packets = mutableListOf<ShortArray>()
        samples.forEach { sample ->
            pending += sample
            if (pending.size == packetSize) {
                packets += pending.toShortArray()
                pending.clear()
            }
        }
        return packets
    }

    fun clear() { pending.clear() }

    companion object {
        const val PACKET_SAMPLES = 1600
        const val PACKET_SECONDS = PACKET_SAMPLES / 16_000.0
    }
}
