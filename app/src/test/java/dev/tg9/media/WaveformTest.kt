package dev.tg9.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformTest {
    @Test
    fun packRoundTrips() {
        val values = IntArray(100) { (it * 7) % 32 }
        val packed = Waveform.pack(values)
        assertEquals(63, packed.size)
        assertArrayEquals(values, Waveform.unpack(packed, values.size))
    }

    @Test
    fun encodeNormalizesToFiveBits() {
        val encoded = Waveform.encode(List(250) { if (it == 120) 32000 else 1000 })
        val samples = Waveform.unpack(encoded, Waveform.SAMPLES)
        assertEquals(31, samples.max())
        assertEquals(Waveform.SAMPLES, samples.size)
    }

    @Test
    fun silenceAndEmpty() {
        assertEquals(0, Waveform.encode(emptyList()).size)
        assertEquals(0, Waveform.unpack(Waveform.encode(List(10) { 0 }), 10).max())
    }
}
