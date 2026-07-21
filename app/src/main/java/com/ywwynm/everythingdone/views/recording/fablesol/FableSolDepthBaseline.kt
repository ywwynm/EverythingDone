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
        requireValid(anchors, tangents)
        return valueUnchecked(anchors, tangents, position)
    }

    /**
     * 契约校验的独立入口：顶点循环每帧调用 [valueUnchecked] 19012 次，
     * 把 `require` 提到循环外只做一次。锚点与切线数组是渲染器的常驻字段，
     * 循环期间形状不变，因此循环外校验一次与逐次校验等效。
     */
    fun requireValid(anchors: DoubleArray, tangents: DoubleArray) {
        require(anchors.size >= 2 && tangents.size == anchors.size)
    }

    /** 与 [value] 数学完全相同、不做契约校验的热路径入口；调用方需先 [requireValid]。 */
    fun valueUnchecked(anchors: DoubleArray, tangents: DoubleArray, position: Double): Double {
        // 纵向轨道只会越出不足一个层间距。沿端点切线延拓能让一阶导连续；
        // 旧端点钳制会在穿越最外层时把导数突然归零，形成斜线接平台的尖角。
        if (position < 0.0) return anchors[0] + tangents[0] * position
        val last = anchors.lastIndex
        if (position > last) return anchors[last] + tangents[last] * (position - last)
        if (position == last.toDouble()) return anchors[last]
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
