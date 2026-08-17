package com.ywwynm.everythingdone.views.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16AudioMixerTest {

    @Test
    fun `microphone is centered into both system channels`() {
        val system = shortArrayOf(10_000, -10_000)
        val microphone = shortArrayOf(5_000)
        val output = ShortArray(2)

        val frames = Pcm16AudioMixer.mixSystemAndMicrophone(
            system, 1, microphone, 1, output
        )

        assertEquals(1, frames)
        assertEquals(9_600, output[0].toInt())
        assertEquals(-3_200, output[1].toInt())
    }

    @Test
    fun `soft limiter prevents clipping while preserving polarity`() {
        val system = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        val microphone = shortArrayOf(Short.MAX_VALUE)
        val output = ShortArray(2)

        Pcm16AudioMixer.mixSystemAndMicrophone(system, 1, microphone, 1, output)

        assertTrue(output[0] in 1..Short.MAX_VALUE)
        assertTrue(kotlin.math.abs(output[1].toInt()) <= 2)
    }

    @Test
    fun `stereo downmix averages channels without overflow`() {
        val stereo = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE)
        val mono = ShortArray(2)

        val frames = Pcm16AudioMixer.downmixStereoToMono(stereo, 2, mono)

        assertEquals(2, frames)
        assertEquals(Short.MAX_VALUE, mono[0])
        assertEquals(Short.MIN_VALUE, mono[1])
    }

    @Test
    fun `preference values are stable and unknown values fall back to microphone`() {
        assertEquals(AudioInputMode.SYSTEM, AudioInputMode.fromPreference("system"))
        assertEquals(
            AudioInputMode.SYSTEM_AND_MICROPHONE,
            AudioInputMode.fromPreference("system_and_microphone")
        )
        assertEquals(AudioInputMode.MICROPHONE, AudioInputMode.fromPreference("future_value"))
    }
}
