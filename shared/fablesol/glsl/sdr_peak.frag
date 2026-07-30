#version 300 es
precision highp float;
precision highp int;

// `SDR（保留高光层次）· 动态映射` 的逐帧超白峰值归约（fablesol-video-export D73、D75）。
//
// 读的是**FableSol 场景纹理本身**——水体、银丝、星芒与眩光合成之后、色调映射之前的那一张。
// 外侧 padding、画框背景、投影、描边与时钟都在呈现阶段才合成进来，因此它们天然不在统计
// 范围内，不需要在这里额外排除（D73）。
//
// 一个输出 texel 归约源图的一个矩形块，剩下的最大值由 CPU 在 32×32 上取完。取的是真实峰值
// 而不是高分位数：银丝与星芒是刻意设计的稀疏高光，P99 之流可能完全漏掉它们（D75）。
//
// 回读格式固定 RGBA8——ES 3.0 只保证 `GL_RGBA` + `GL_UNSIGNED_BYTE` 这一组 glReadPixels
// 组合可用。标量按 uMaxValue 归一后packed成 16 位小端放进 R、G 两个字节，分辨率
// 9.6/65535 ≈ 1.5e-4，远细于平滑之后曲线能分辨的程度。

uniform sampler2D uSource;
uniform int uWidth;
uniform int uHeight;
uniform int uBlockW;
uniform int uBlockH;
uniform float uMaxValue;

out vec4 outColor;

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int x0 = dst.x * uBlockW;
    int y0 = dst.y * uBlockH;
    float peak = 0.0;
    for (int j = 0; j < uBlockH; ++j) {
        int y = y0 + j;
        if (y >= uHeight) break;
        for (int i = 0; i < uBlockW; ++i) {
            int x = x0 + i;
            if (x >= uWidth) break;
            vec3 c = texelFetch(uSource, ivec2(x, y), 0).rgb;
            peak = max(peak, max(c.r, max(c.g, c.b)));
        }
    }
    float code = floor(clamp(peak / uMaxValue, 0.0, 1.0) * 65535.0 + 0.5);
    float hi = floor(code / 256.0);
    float lo = code - hi * 256.0;
    outColor = vec4(lo / 255.0, hi / 255.0, 0.0, 1.0);
}
