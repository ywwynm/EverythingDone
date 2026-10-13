package com.ywwynm.everythingdone.views.particledismiss

import android.app.Instrumentation
import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** 三机核对实际 JNI 与独立字节基准；不创建 Activity、不访问记事。 */
class ParticleContentSamplesProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var cases = 0
        val result = runCatching {
            check(ParticleMaterialNative.available)
            val random = Random(20261012)
            for ((width, height) in listOf(1 to 1, 7 to 3, 129 to 144, 1012 to 1528, 1440 to 3088)) {
                val bytes = ByteArray(width * height * 4).also(random::nextBytes)
                bytes.fill(-1, 0, (bytes.size / 4 * 3 / 4) * 4)
                val positions = ParticleDriftContent.samplePositions(width, height)
                val expected = IntArray(positions.size) {
                    val at = positions[it] * 4
                    (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                        ((bytes[at + 2].toInt() and 255) shl 16) or ((bytes[at + 3].toInt() and 255) shl 24)
                }
                for (offset in 0..1) for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
                    val buffer = ByteBuffer.allocateDirect(bytes.size + offset)
                    buffer.position(offset); buffer.put(bytes); buffer.position(offset)
                    val source = buffer.slice().asReadOnlyBuffer().order(order)
                    source.position(minOf(3, source.limit()))
                    val originalPosition = source.position()
                    check(expected.contentEquals(ParticleDriftContent.samplesFrom(source, positions)))
                    check(source.position() == originalPosition && source.order() == order)
                    val native = ParticleDriftContent.analyseSamples(width, height, expected, 30000)
                    val reference = ParticleMaterialNative.reference {
                        ParticleDriftContent.analyse(width, height, source, 30000)
                    }
                    check(native.panel.contentEquals(reference.panel) && native.panelWeight == reference.panelWeight &&
                        native.replicas == reference.replicas && native.cumulative.contentEquals(reference.cumulative))
                    cases++
                }
            }
            val short = ByteBuffer.allocateDirect(12).apply { limit(8) }
            check(runCatching { ParticleDriftContent.samplesFrom(short, intArrayOf(2)) }.exceptionOrNull() is IndexOutOfBoundsException)
            check(runCatching { ParticleDriftContent.samplesFrom(short, intArrayOf(-1)) }.exceptionOrNull() is IndexOutOfBoundsException)
            "通过 $cases 组逐位对照及 2 组边界检查"
        }
        finish(if (result.isSuccess) 0 else 1, Bundle().apply {
            putString("result", result.getOrElse { it.stackTraceToString() })
        })
    }
}
