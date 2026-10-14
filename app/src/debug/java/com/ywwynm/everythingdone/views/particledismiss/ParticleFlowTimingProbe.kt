package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.os.Bundle
import kotlin.random.Random
import kotlin.math.ceil
import kotlin.math.sqrt

/** 对照真实 JNI 与原 Kotlin 公式，不创建界面或访问用户数据。 */
class ParticleFlowTimingProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching {
            check(ParticleMaterialNative.available)
            val random = Random(20261014)
            var cases = 0
            val rules = ParticleMicroflakeRenderer.sharedResources(targetContext.assets).rules
            for ((width, height) in listOf(1 to 1, 7 to 5, 256 to 256, 1012 to 1528)) {
                for (kind in 0..2) {
                    val pixels = IntArray(width * height) { i -> when (kind) {
                        0 -> 0
                        1 -> if (i % 3 == 0) 0xfffafafa.toInt() else 0xff56a9c5.toInt()
                        else -> random.nextInt().let { (it and 0xffffff) or ((240 + i % 16) shl 24) }
                    } }
                    val stride = ceil(sqrt(pixels.size / 65536.0)).toInt().coerceAtLeast(1)
                    val stats = ParticleMaterialNative.classifyPixels(pixels, width, height, stride)
                    val reference = ParticleMaterialNative.reference { ParticleMicroflakeContent.panel(pixels, width, height, rules) }
                    val native = if (stats[0] == 0L) FloatArray(3) else FloatArray(3) {
                        (stats[it + 2].toDouble() / stats[1] / 255.0).toFloat()
                    }
                    check(reference.color.contentEquals(native))
                    val prepared = ParticleMicroflakeContent.panel(pixels, width, height, rules, stats)
                    check(reference.color.contentEquals(prepared.color) && reference.weight.toRawBits() == prepared.weight.toRawBits())
                    check(reference.fraction.toRawBits() == (if (stats[0] == 0L) 0f else stats[1].toFloat()/stats[0]).toRawBits())
                    var opaque = 0L; var white = 0L
                    for (y in 0 until height step stride) for (x in 0 until width step stride) {
                        val c = pixels[y * width + x]
                        if (c ushr 24 > 242) {
                            opaque++
                            if (minOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 229) white++
                        }
                    }
                    check(stats[5] == opaque && stats[6] == white)
                    cases++
                }
            }
            for (count in listOf(0, 1, 256, 36270)) for (samples in listOf(1, 18, 36, 60)) {
                val values = FloatArray(count * 12)
                for (i in 0 until count) {
                    values[i * 12 + 2] = when (i % 5) {
                        0 -> (i % (samples + 1)) / samples.toFloat()
                        1 -> -.1f
                        else -> random.nextFloat()
                    }
                    values[i * 12 + 6] = random.nextFloat()
                    values[i * 12 + 11] = random.nextFloat()
                }
                val expected = ParticleMaterialNative.reference { ParticleReversePlan(values, samples) }
                val ranges = IntArray(count * 4)
                val bytes = ParticleMaterialNative.reverseRanges(values, samples, ranges)
                check(expected.ranges.contentEquals(ranges) && expected.bytes == bytes)
                for (spread in listOf(0f, .13f, .8f)) {
                    val reference = ParticleMaterialNative.reference { ParticleBodyRelease.progress(values, spread) }
                    check(reference.toRawBits() == ParticleMaterialNative.bodyRelease(values, spread).toRawBits())
                    cases++
                }
            }
            "通过 $cases 组像素分类、材料边界与历史范围逐位对照"
        }
        finish(if (result.isSuccess) 0 else 1, Bundle().apply {
            putString("result", result.getOrElse { it.stackTraceToString() })
        })
    }
}
