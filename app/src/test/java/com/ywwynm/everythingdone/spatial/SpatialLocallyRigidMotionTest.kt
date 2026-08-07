package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialLocallyRigidMotionTest {

    @Test
    fun `portrait physical rotation is not misclassified as shear`() {
        val width = 43
        val height = 97
        val rotation = 0.18f
        val horizontalX = FloatArray(width * height)
        val horizontalY = FloatArray(width * height) { index ->
            val x = index % width
            rotation * x / (height - 1f)
        }
        val basis = SpatialScreenSpaceMotionBasis(
            width = width,
            height = height,
            horizontalX = horizontalX,
            horizontalY = horizontalY,
            verticalX = FloatArray(width * height) { index ->
                val y = index / width
                -rotation * y / (width - 1f)
            },
            verticalY = FloatArray(width * height)
        )

        val distortion = basis.distortion(
            viewpointX = 1f,
            viewpointY = 1f,
            cutRight = BooleanArray(height * (width - 1)),
            cutDown = BooleanArray((height - 1) * width)
        )

        assertEquals(0f, distortion.nonSimilarityCoefficient, 2e-5f)
        assertEquals(0f, distortion.scaleCoefficient, 2e-5f)
    }

    @Test
    fun `smooth protected volume does not collapse into anchor centered strain spikes`() {
        val width = 192
        val height = 256
        val left = 42
        val right = 149
        val top = 24
        val bottom = 231
        val subject = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in left..right && y in top..bottom
        }
        val surface = FloatArray(width * height) { index ->
            if (subject[index]) 0.63f else 0.27f
        }
        val protectedDepth = surface.copyOf()
        for (y in top..bottom) {
            for (x in left..right) {
                val nx = (x - left).toFloat() / (right - left)
                val ny = (y - top).toFloat() / (bottom - top)
                val broadHead = if (x in 70..121 && y in 38..111) 0.045f else 0f
                protectedDepth[y * width + x] =
                    0.52f + 0.19f * nx - 0.055f * ny + broadHead
            }
        }
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)
        for (y in top..bottom) {
            cutRight[y * (width - 1) + left - 1] = true
            cutRight[y * (width - 1) + right] = true
        }
        for (x in left..right) {
            cutDown[(top - 1) * width + x] = true
            cutDown[bottom * width + x] = true
        }

        val basis = SpatialLocallyRigidMotionBuilder.build(
            width = width,
            height = height,
            targetDepth = surface,
            protectedMotionDepth = protectedDepth,
            cutRight = cutRight,
            cutDown = cutDown,
            protectedMask = subject,
            requestedMaximumAmplitude = 0.12f
        )
        val coefficients = ArrayList<Float>()
        val firstDerivatives = ArrayList<Float>()
        val secondDerivatives = ArrayList<Float>()
        for (y in top + 4..bottom - 4) {
            for (x in left + 4..right - 4) {
                val index = y * width + x
                coefficients += basis.horizontalX[index]
                firstDerivatives += abs(
                    basis.horizontalX[index + 1] - basis.horizontalX[index]
                ) * (width - 1)
                secondDerivatives += abs(
                    basis.horizontalX[index + 1] -
                        2f * basis.horizontalX[index] +
                        basis.horizontalX[index - 1]
                ) * (width - 1)
            }
        }
        fun percentile(values: List<Float>, fraction: Float): Float {
            val ordered = values.sorted()
            return ordered[((ordered.lastIndex) * fraction).toInt()]
        }
        val motionSpan = percentile(coefficients, 0.95f) - percentile(coefficients, 0.05f)
        val derivativeP95 = percentile(firstDerivatives, 0.95f)
        val derivativeP995 = percentile(firstDerivatives, 0.995f)
        val curvatureP95 = percentile(secondDerivatives, 0.95f)
        val curvatureP995 = percentile(secondDerivatives, 0.995f)
        assertTrue(
            "平滑宏观深度被少数锚点尖峰消耗：span=$motionSpan, " +
                "derivativeP95=$derivativeP95, derivativeP995=$derivativeP995",
            motionSpan >= 0.040f
        )
        assertTrue(
            "锚点附近出现离散曲率尖峰：curvatureP95=$curvatureP95, " +
                "curvatureP995=$curvatureP995",
            curvatureP995 <= 0.060f
        )
    }

    @Test
    fun `prelimited surface recovers protected macro volume within a full amplitude strain budget`() {
        val width = 96
        val height = 72
        val left = 20
        val right = 75
        val top = 8
        val bottom = 67
        val subject = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in left..right && y in top..bottom
        }
        val surface = FloatArray(width * height) { index ->
            if (subject[index]) 0.64f else 0.26f
        }
        val motionDepth = surface.copyOf()
        for (y in top..bottom) {
            for (x in left..right) {
                val nx = (x - left).toFloat() / (right - left)
                val ny = (y - top).toFloat() / (bottom - top)
                motionDepth[y * width + x] =
                    0.53f + 0.23f * nx - 0.07f * ny +
                    if ((x + y) % 9 == 0) 0.025f else 0f
            }
        }
        // 暗衣上的错误远景块不能直接变成局部拉伸。
        for (y in 38..45) for (x in 43..51) {
            motionDepth[y * width + x] = 0.24f
        }
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)
        for (y in top..bottom) {
            cutRight[y * (width - 1) + left - 1] = true
            cutRight[y * (width - 1) + right] = true
        }
        for (x in left..right) {
            cutDown[(top - 1) * width + x] = true
            if (bottom + 1 < height) cutDown[bottom * width + x] = true
        }

        val basis = SpatialLocallyRigidMotionBuilder.build(
            width = width,
            height = height,
            targetDepth = surface,
            protectedMotionDepth = motionDepth,
            cutRight = cutRight,
            cutDown = cutDown,
            protectedMask = subject,
            requestedMaximumAmplitude = 0.12f
        )
        val unlimitedBasis = SpatialLocallyRigidMotionBuilder.build(
            width = width,
            height = height,
            targetDepth = surface,
            protectedMotionDepth = motionDepth,
            cutRight = cutRight,
            cutDown = cutDown,
            protectedMask = subject,
            requestedMaximumAmplitude = 0.12f,
            maximumNonSimilarityStrain = 0.1f,
            maximumScaleStrain = 0.1f
        )
        val amplitude = 0.12f
        val macroLeft = basis.displacement(34 * width + 31, 1f, 0f, amplitude)
        val macroRight = basis.displacement(34 * width + 65, 1f, 0f, amplitude)
        val unlimitedLeft = unlimitedBasis.displacement(34 * width + 31, 1f, 0f, amplitude)
        val unlimitedRight = unlimitedBasis.displacement(34 * width + 65, 1f, 0f, amplitude)
        assertTrue(
            "受保护主体仍只使用已经压平的 surfaceDepth：" +
                abs(macroRight.x - macroLeft.x) + "，未限幅=" +
                abs(unlimitedRight.x - unlimitedLeft.x),
            abs(macroRight.x - macroLeft.x) >= 0.0042f
        )
        assertTrue(
            "主体宏观体积过强，可能重新出现橡皮形变",
            abs(macroRight.x - macroLeft.x) <= 0.012f
        )
        assertTrue(
            "自动预算后非相似形变仍越界",
            basis.maximumNonSimilarityStrain(amplitude, cutRight, cutDown) <= 0.010f
        )
        repeat(SpatialViewEnvelope.DIRECTION_COUNT) { direction ->
            val angle = direction * 2.0 * Math.PI / SpatialViewEnvelope.DIRECTION_COUNT
            val distortion = basis.distortion(
                viewpointX = kotlin.math.cos(angle).toFloat(),
                viewpointY = kotlin.math.sin(angle).toFloat(),
                cutRight = cutRight,
                cutDown = cutDown
            )
            assertTrue(
                "自动预算后局部等比缩放仍越界",
                amplitude * distortion.scaleCoefficient <= 0.0225f
            )
        }
        val subjectCenter = basis.displacement(36 * width + 48, 1f, 0f, amplitude)
        val background = basis.displacement(36 * width + 5, 1f, 0f, amplitude)
        assertTrue(
            "恢复主体体积时压低了前后景主视差",
            abs(subjectCenter.x - background.x) >= 0.035f
        )
    }

    @Test
    fun `protected subject keeps macro volume without horizontal face stretch`() {
        val width = 80
        val height = 64
        val left = 18
        val right = 61
        val top = 7
        val bottom = 60
        val subject = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in left..right && y in top..bottom
        }
        // 小型 matting 漏洞不能成为独立运动洞。
        for (y in 29..31) for (x in 42..44) subject[y * width + x] = false

        val depth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in left..right && y in top..bottom) {
                val nx = (x - left).toFloat() / (right - left)
                val ny = (y - top).toFloat() / (bottom - top)
                0.61f + 0.18f * nx - 0.07f * ny +
                    if (x in 34..48 && y in 14..34) 0.055f else 0f
            } else {
                0.22f
            }
        }
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)
        for (y in top..bottom) {
            cutRight[y * (width - 1) + left - 1] = true
            cutRight[y * (width - 1) + right] = true
        }
        for (x in left..right) {
            cutDown[(top - 1) * width + x] = true
            if (bottom + 1 < height) cutDown[bottom * width + x] = true
        }

        val basis = SpatialLocallyRigidMotionBuilder.build(
            width = width,
            height = height,
            targetDepth = depth,
            cutRight = cutRight,
            cutDown = cutDown,
            protectedMask = subject
        )
        val amplitude = 0.12f
        fun mapped(x: Int, y: Int): Pair<Float, Float> {
            val motion = basis.displacement(
                index = y * width + x,
                viewpointX = 1f,
                viewpointY = 0f,
                amplitude = amplitude
            )
            return x.toFloat() / (width - 1) - motion.x to
                y.toFloat() / (height - 1) - motion.y
        }

        val macroLeft = basis.displacement(34 * width + 27, 1f, 0f, amplitude)
        val macroRight = basis.displacement(34 * width + 53, 1f, 0f, amplitude)
        assertTrue(
            "主体仍被压成同一张纸片：" + abs(macroRight.x - macroLeft.x),
            abs(macroRight.x - macroLeft.x) >= 0.003f
        )

        // 在一个身份敏感的局部窗口内比较横向和纵向尺度。逐像素深度平移只会改变
        // 横向尺度；局部 similarity 拟合应把非均匀拉伸压到 1.5% 以内。
        val horizontalStart = mapped(36, 22)
        val horizontalEnd = mapped(46, 22)
        val verticalStart = mapped(41, 17)
        val verticalEnd = mapped(41, 27)
        val horizontalScale = hypot(
            horizontalEnd.first - horizontalStart.first,
            horizontalEnd.second - horizontalStart.second
        ) / (10f / (width - 1))
        val verticalScale = hypot(
            verticalEnd.first - verticalStart.first,
            verticalEnd.second - verticalStart.second
        ) / (10f / (height - 1))
        assertTrue(
            "局部非相似形变仍过大：horizontal=$horizontalScale vertical=$verticalScale",
            abs(horizontalScale - verticalScale) <= 0.015f
        )

        val holeLeft = basis.displacement(30 * width + 41, 1f, 0f, amplitude)
        val holeCenter = basis.displacement(30 * width + 43, 1f, 0f, amplitude)
        assertTrue(
            "matting 小孔形成了可见运动接缝：" +
                hypot(holeLeft.x - holeCenter.x, holeLeft.y - holeCenter.y),
            hypot(holeLeft.x - holeCenter.x, holeLeft.y - holeCenter.y) <= 0.0025f
        )

        val subjectCenter = basis.displacement(34 * width + 40, 1f, 0f, amplitude)
        val background = basis.displacement(34 * width + 5, 1f, 0f, amplitude)
        assertTrue(
            "保形后前后景空间视差塌缩",
            hypot(
                subjectCenter.x - background.x,
                subjectCenter.y - background.y
            ) >= 0.035f
        )
    }

    @Test
    fun `unprotected scene remains the original depth projection`() {
        val width = 24
        val height = 18
        val depth = FloatArray(width * height) { index ->
            0.15f + 0.7f * (index % width).toFloat() / (width - 1)
        }
        val basis = SpatialLocallyRigidMotionBuilder.build(
            width = width,
            height = height,
            targetDepth = depth,
            cutRight = BooleanArray(height * (width - 1)),
            cutDown = BooleanArray((height - 1) * width),
            protectedMask = null
        )
        for (index in depth.indices step 17) {
            val horizontal = basis.displacement(index, 1f, 0f, 0.12f)
            val vertical = basis.displacement(index, 0f, 1f, 0.12f)
            assertTrue(abs(horizontal.x - 0.12f * (depth[index] - 0.5f)) < 1e-6f)
            assertTrue(abs(horizontal.y) < 1e-6f)
            assertTrue(abs(vertical.x) < 1e-6f)
            assertTrue(abs(vertical.y - 0.12f * (depth[index] - 0.5f)) < 1e-6f)
        }
    }
}
