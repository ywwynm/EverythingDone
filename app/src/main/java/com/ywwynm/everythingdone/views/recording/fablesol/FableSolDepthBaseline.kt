package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs

/** 共享给 GLES 与 Canvas 的九层深度基线 PCHIP 插值。 */
internal object FableSolDepthBaseline {

    fun updateTangents(anchors: DoubleArray, tangents: DoubleArray) {
        require(anchors.isNotEmpty() && tangents.size == anchors.size)
        val last = anchors.lastIndex
        if (last == 0) {
            tangents[0] = 0.0
            return
        }
        if (last == 1) {
            val delta = anchors[1] - anchors[0]
            tangents[0] = delta
            tangents[1] = delta
            return
        }

        var before = anchors[1] - anchors[0]
        var after = anchors[2] - anchors[1]
        tangents[0] = endpointTangent(before, after)
        for (index in 1 until last) {
            tangents[index] = if (before == 0.0 || after == 0.0 || before * after <= 0.0) {
                0.0
            } else {
                2.0 * before * after / (before + after)
            }
            before = after
            if (index + 2 <= last) after = anchors[index + 2] - anchors[index + 1]
        }
        tangents[last] = endpointTangent(before, anchors[last - 1] - anchors[last - 2])
    }

    fun value(anchors: DoubleArray, tangents: DoubleArray, position: Double): Double {
        if (position <= 0.0) return anchors[0]
        val last = anchors.lastIndex
        if (position >= last) return anchors[last]
        val start = position.toInt()
        val fraction = position - start
        return FableSolCubicResampler.hermiteValue(
            anchors[start],
            anchors[start + 1],
            tangents[start],
            tangents[start + 1],
            fraction,
            1.0
        )
    }

    private fun endpointTangent(edgeDelta: Double, neighborDelta: Double): Double {
        val candidate = (3.0 * edgeDelta - neighborDelta) * 0.5
        if (edgeDelta == 0.0 || candidate * edgeDelta <= 0.0) return 0.0
        return if (edgeDelta * neighborDelta < 0.0 &&
            abs(candidate) > 3.0 * abs(edgeDelta)
        ) {
            3.0 * edgeDelta
        } else {
            candidate
        }
    }
}
