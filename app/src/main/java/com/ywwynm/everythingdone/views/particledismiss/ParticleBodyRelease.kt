package com.ywwynm.everythingdone.views.particledismiss

/** 原表面的最晚消失相位，与 material.vert / draw-record.comp 的表面交接公式对应。 */
internal object ParticleBodyRelease {
    fun progress(values: FloatArray, releaseSpread: Float): Float {
        require(values.size % 12 == 0)
        var end = 0f
        for (i in values.indices step 12) {
            val birth = values[i + 2]
            // trailing 只使 source_clock 提前，省去它给出保守上界。
            val sourceClock = birth - releaseSpread * (values[i + 11] - .5f)
            end = maxOf(end, birth, sourceClock + .060f)
        }
        return (end + .001f).coerceIn(0f, 1f)
    }
}
