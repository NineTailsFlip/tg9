package dev.tg9.media

/** Telegram's voice-message waveform: up to 100 samples of 5 bits each (0..31), packed
 *  little-endian, sample i at bit offset 5*i. It's what other clients draw as the bars of a voice
 *  message; TG9 draws none, but a message sent without one shows as a flat line everywhere else. */
object Waveform {
    const val SAMPLES = 100
    private const val MAX_VALUE = 31

    fun encode(amplitudes: List<Int>): ByteArray {
        if (amplitudes.isEmpty()) return ByteArray(0)
        val samples = resample(amplitudes, SAMPLES)
        val peak = samples.maxOrNull()?.takeIf { it > 0 } ?: return pack(IntArray(samples.size))
        return pack(IntArray(samples.size) { (samples[it].toLong() * MAX_VALUE / peak).toInt() })
    }

    /** Peak of each of [count] equal slices, so a short loud word isn't averaged away. */
    fun resample(values: List<Int>, count: Int): List<Int> {
        if (values.size <= count) return values
        return List(count) { i ->
            val from = i * values.size / count
            val to = ((i + 1) * values.size / count).coerceAtLeast(from + 1)
            values.subList(from, to).max()
        }
    }

    fun pack(values: IntArray): ByteArray {
        val bytes = ByteArray((values.size * 5 + 7) / 8)
        values.forEachIndexed { i, raw ->
            val value = raw.coerceIn(0, MAX_VALUE)
            val bit = i * 5
            val index = bit / 8
            val shift = bit % 8
            bytes[index] = (bytes[index].toInt() or (value shl shift)).toByte()
            if (shift > 3 && index + 1 < bytes.size) {
                bytes[index + 1] = (bytes[index + 1].toInt() or (value shr (8 - shift))).toByte()
            }
        }
        return bytes
    }

    fun unpack(bytes: ByteArray, count: Int): IntArray = IntArray(count) { i ->
        val bit = i * 5
        val index = bit / 8
        val shift = bit % 8
        val low = (bytes.getOrElse(index) { 0 }.toInt() and 0xFF) shr shift
        val high = if (shift > 3) (bytes.getOrElse(index + 1) { 0 }.toInt() and 0xFF) shl (8 - shift) else 0
        (low or high) and MAX_VALUE
    }
}
