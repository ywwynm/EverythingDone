package com.ywwynm.everythingdone.views.recording.fablesol

import android.graphics.RuntimeShader
import android.os.Build

/**
 * 阶段 C（AGSL，API 33+）：逐像素渲染增强，Canvas 路径保留为回退（D20）。
 *
 * - C1 抖动：渐变输出叠加三角分布噪声（±1/255），消除 OLED 大面积平缓渐变的
 *   色阶条纹（banding）——Canvas LinearGradient 无解，shader 是标准解。
 * - C2 软带：fade 软带不再用三条折线子带近似钟形剖面，而是把该层轮廓上沿与
 *   厚度（≤216 列）作为 uniform 数组传入，逐像素插值出带内相对深度并求
 *   连续剖面——数学精确、无子带阶差，且把路径光栅从 CPU 移到 GPU。
 *
 * 所有 shader 运行时编译；任何一步失败（编译器差异/旧系统）即整体回退，
 * 不影响既有渲染。剖面平台值与 CPU 子带堆叠一致（0.14/0.48/0.72），过渡改为
 * smoothstep 连续化。
 */
object FableSolAgsl {

    /** 三角分布抖动（两次哈希求和）：比均匀噪声更接近感知最优的量化解相关。 */
    private const val DITHER_SRC = """
        uniform shader src;
        half4 main(float2 xy) {
            half4 c = src.eval(xy);
            float n1 = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453);
            float n2 = fract(sin(dot(xy + 17.13, float2(26.6514, 57.211))) * 24634.6345);
            float d = (n1 + n2) * 0.5 - 0.5;
            return half4(c.rgb + d * (1.0 / 255.0) * c.a, c.a);
        }
    """

    /**
     * 软带剖面：x→列插值 top/th → rel=(y−top)/th → 连续钟形 alpha。
     * 轮廓数据经 RGBA_F16 位图（216×1，r=top01、g=th01）以 input shader 采样
     * ——AGSL（GLSL ES 1.0 fragment 语义）不允许 uniform 数组动态索引，
     * 纹理采样是合法等价物（2026-07-11 真机红屏确诊后的返工）。
     */
    private const val BAND_SRC = """
        uniform shader data;
        uniform float x0;
        uniform float dxStep;
        uniform float cntF;
        uniform float yMin;
        uniform float yRange;
        uniform float4 tint;   // r,g,b ∈0..1（非预乘），a=峰值 alpha ∈0..1
        half4 main(float2 xy) {
            float f = (xy.x - x0) / dxStep;
            if (f < 0.0 || f > cntF - 1.0) return half4(0.0);
            float j = floor(min(f, cntF - 2.0));
            float fr = f - j;
            half4 d0 = data.eval(float2(j + 0.5, 0.5));
            half4 d1 = data.eval(float2(j + 1.5, 0.5));
            float t = yMin + mix(float(d0.r), float(d1.r), fr) * yRange;
            float h = mix(float(d0.g), float(d1.g), fr) * yRange;
            if (h < 0.05) return half4(0.0);
            float rel = (xy.y - t) / h;
            if (rel < 0.0 || rel > 1.0) return half4(0.0);
            float a = 0.14
                + 0.34 * smoothstep(0.06, 0.14, rel)
                + 0.24 * smoothstep(0.18, 0.28, rel)
                - 0.24 * smoothstep(0.60, 0.70, rel)
                - 0.34 * smoothstep(0.68, 0.78, rel)
                - 0.14 * smoothstep(0.86, 1.00, rel);
            float alpha = tint.a * a;
            return half4(tint.rgb * alpha, alpha);
        }
    """

    /**
     * C3 层填充：在既有渐变（input shader）之上做逐像素水体光学。
     * - 深度吸收：Beer–Lambert 近似——深度越大越向本层深色收敛（乘性衰减、
     *   保色相，有下限不压黑），absorb=0 时恒等。
     * - 焦散：表面下 2~36dp 的横向拉伸值噪声亮脉（两倍频、随层流漂移、
     *   深度包络、稀疏化阈值），caustic=0 时恒等。
     */
    private const val LAYER_SRC = """
        uniform shader src;
        uniform shader data;
        uniform float x0;
        uniform float dxStep;
        uniform float cntF;
        uniform float yMin;
        uniform float yRange;
        uniform float densityPx;
        uniform float absorb;
        half4 main(float2 xy) {
            half4 c = src.eval(xy);
            float f = clamp((xy.x - x0) / dxStep, 0.0, cntF - 1.0);
            float j = floor(min(f, cntF - 2.0));
            float fr = f - j;
            half4 d0 = data.eval(float2(j + 0.5, 0.5));
            half4 d1 = data.eval(float2(j + 1.5, 0.5));
            float t = yMin + mix(float(d0.r), float(d1.r), fr) * yRange;
            float d = max(xy.y - t, 0.0) / densityPx;
            if (d <= 0.0) return c;
            float att = exp(-absorb * 0.010 * d);
            float g = mix(0.72, 1.0, att);
            return half4(c.rgb * half(g), c.a);
        }
    """

    val supported: Boolean = Build.VERSION.SDK_INT >= 33

    /** 渐变抖动包装 shader；构建失败返回 null（调用方直接用原渐变）。 */
    val dither: RuntimeShader? by lazy {
        if (!supported) null else try {
            RuntimeShader(DITHER_SRC)
        } catch (_: Throwable) {
            null
        }
    }

    /** 软带逐像素 shader；构建失败返回 null（调用方走 CPU 子带路径）。 */
    val band: RuntimeShader? by lazy {
        if (!supported) null else try {
            RuntimeShader(BAND_SRC)
        } catch (_: Throwable) {
            null
        }
    }

    /** C3 层填充光学 shader；构建失败返回 null（调用方直接用渐变填充）。 */
    val layerFill: RuntimeShader? by lazy {
        if (!supported) null else try {
            RuntimeShader(LAYER_SRC)
        } catch (_: Throwable) {
            null
        }
    }
}
