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
 * 旧 C3 曾在编码 sRGB 中把整层填充乘暗并称为 Beer–Lambert 吸收；该做法既没有
 * 独立透射分瓣，也会污染主体颜色，已按 D127 移除。真实体积吸收只能进入介质
 * 透射/折射路径，不能在 Canvas 填充之后补一个整层暗化 shader。
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
            // 连续半正弦剖面：保留旧三子带 source-over 的中央峰值与积分光量，
            // 消除浪面展开时出现的多档透明度平台；两端严格连续归零。
            float a = 0.66 * sin(3.14159265 * rel);
            float alpha = tint.a * a;
            return half4(tint.rgb * alpha, alpha);
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

}
