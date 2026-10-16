package com.ywwynm.everythingdone.hdr

/** PhotoView 的 Android 行主序矩阵转为 GPU 列主序；输入纹理坐标以左上为原点。 */
internal object HdrViewerGeometry {
    fun transform(m: FloatArray, drawableWidth: Int, drawableHeight: Int,
                  viewWidth: Int, viewHeight: Int): FloatArray {
        require(m.size == 9 && m.all { it.isFinite() })
        require(drawableWidth > 0 && drawableHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val sx = 2f / viewWidth
        val sy = 2f / viewHeight
        return floatArrayOf(
            (sx * m[0] - m[6]) * drawableWidth, (m[6] - sy * m[3]) * drawableWidth, m[6] * drawableWidth,
            (sx * m[1] - m[7]) * drawableHeight, (m[7] - sy * m[4]) * drawableHeight, m[7] * drawableHeight,
            sx * m[2] - m[8], m[8] - sy * m[5], m[8]
        )
    }
}
