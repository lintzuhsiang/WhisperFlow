package com.liveTranslation.player

/**
 * Audio resampling utility for Whisper ASR.
 *
 * Whisper expects 16 kHz mono PCM-16. Most video files contain AAC/MP3 audio
 * at 44100 or 48000 Hz, typically stereo. This object provides a lightweight
 * Kotlin-only resampler (linear interpolation) — no native deps required.
 */
object AudioResampler {

    const val TARGET_RATE = 16000

    /**
     * Downmix multi-channel audio to mono and resample from [inputRate] Hz
     * to [TARGET_RATE] Hz using linear interpolation.
     *
     * @param input       Raw PCM-16 samples (interleaved if multi-channel).
     * @param inputRate   Source sample rate in Hz (e.g. 44100, 48000).
     * @param inputChannels Number of audio channels (1 = mono, 2 = stereo).
     * @return            Mono PCM-16 at 16 kHz, ready for WhisperCppEngine.
     */
    fun resampleToWhisper(
        input: ShortArray,
        inputRate: Int,
        inputChannels: Int
    ): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        // 1. Downmix to mono
        val monoLen = input.size / inputChannels
        val mono = ShortArray(monoLen)
        if (inputChannels == 1) {
            input.copyInto(mono)
        } else {
            for (i in 0 until monoLen) {
                var sum = 0L
                for (ch in 0 until inputChannels) {
                    sum += input[i * inputChannels + ch]
                }
                mono[i] = (sum / inputChannels).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        // 2. Resample to 16000 Hz via linear interpolation
        if (inputRate == TARGET_RATE) return mono

        val ratio = inputRate.toDouble() / TARGET_RATE
        val outLen = (monoLen / ratio).toInt()
        if (outLen == 0) return ShortArray(0)

        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = mono[idx.coerceIn(0, monoLen - 1)].toInt()
            val s1 = mono[(idx + 1).coerceIn(0, monoLen - 1)].toInt()
            out[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
