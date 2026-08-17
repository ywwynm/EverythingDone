package com.ywwynm.everythingdone.views.recording

import kotlin.math.abs
import kotlin.math.exp

/** 无状态 PCM16 混音工具；所有输入与输出都使用交错采样的 signed little-endian 语义。 */
object Pcm16AudioMixer {
    const val SYSTEM_GAIN = 0.64f
    const val MICROPHONE_GAIN = 0.64f
    private const val LIMITER_THRESHOLD = 0.91f
    private const val PCM_MAX = 32767f
    private const val PCM_MIN = -32768f

    /**
     * 把立体声系统音频与单声道麦克风混为立体声。返回实际输出帧数。
     * 麦克风居中加入左右两侧；两路不做 ducking，只使用固定增益和总线软限幅。
     */
    fun mixSystemAndMicrophone(
        systemStereo: ShortArray,
        systemFrames: Int,
        microphoneMono: ShortArray,
        microphoneSamples: Int,
        outputStereo: ShortArray
    ): Int {
        val frames = minOf(
            systemFrames.coerceAtLeast(0),
            microphoneSamples.coerceAtLeast(0),
            systemStereo.size / 2,
            microphoneMono.size,
            outputStereo.size / 2
        )
        for (frame in 0 until frames) {
            val mic = microphoneMono[frame] / 32768f * MICROPHONE_GAIN
            val index = frame * 2
            val left = systemStereo[index] / 32768f * SYSTEM_GAIN + mic
            val right = systemStereo[index + 1] / 32768f * SYSTEM_GAIN + mic
            outputStereo[index] = normalizedToPcm16(softLimit(left))
            outputStereo[index + 1] = normalizedToPcm16(softLimit(right))
        }
        return frames
    }

    /** 立体声最终总线下混为单声道，供 FableSol 与旧分析器消费。 */
    fun downmixStereoToMono(
        stereo: ShortArray,
        frames: Int,
        mono: ShortArray
    ): Int {
        val count = minOf(frames.coerceAtLeast(0), stereo.size / 2, mono.size)
        for (frame in 0 until count) {
            val index = frame * 2
            mono[frame] = ((stereo[index].toInt() + stereo[index + 1].toInt()) / 2).toShort()
        }
        return count
    }

    fun writeLittleEndian(samples: ShortArray, sampleCount: Int, output: ByteArray): Int {
        val count = minOf(sampleCount.coerceAtLeast(0), samples.size, output.size / 2)
        var byteIndex = 0
        for (sampleIndex in 0 until count) {
            val value = samples[sampleIndex].toInt()
            output[byteIndex++] = (value and 0xff).toByte()
            output[byteIndex++] = ((value ushr 8) and 0xff).toByte()
        }
        return byteIndex
    }

    internal fun softLimit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= LIMITER_THRESHOLD) return value
        val remaining = 1f - LIMITER_THRESHOLD
        val compressed = LIMITER_THRESHOLD +
            remaining * (1f - exp(-(magnitude - LIMITER_THRESHOLD) / remaining))
        return if (value < 0f) -compressed else compressed
    }

    private fun normalizedToPcm16(value: Float): Short {
        val pcm = (value * 32768f).coerceIn(PCM_MIN, PCM_MAX)
        return pcm.toInt().toShort()
    }
}
