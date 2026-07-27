#version 300 es
precision highp float;

// RGB → P010 的 **CbCr 平面**（4:2:0，交错存放）。
//
// 一个输出 texel 正好对应一组 (Cb, Cr)，共 4 字节，与 RGBA8 一一对应；输出纹理尺寸就是
// 半宽半高，回读出来的字节序正好是 P010 的交错色度平面。行序反转的理由见 p010_luma.frag。
//
// 色度由 2×2 个源像素**在 R'G'B' 域取平均**后再算 Cb/Cr——BT.2020 是非恒定亮度系统，
// 色差本来就定义在非线性量上，所以这里不能先转回线性再平均。

in vec2 vUv;

uniform sampler2D uSource;   // RGB10_A2，已经是 PQ 编码后的 R'G'B'，全范围
uniform int uWidth;
uniform int uHeight;

out vec4 outColor;

const vec3 K_LUMA = vec3(0.2627, 0.6780, 0.0593);
// BT.2020 的色差归一化系数：Cb 除 1.8814，Cr 除 1.4746。
const float KB_SCALE = 1.8814;
const float KR_SCALE = 1.4746;

vec2 encodeSample(float value10) {
    float clamped = clamp(value10, 0.0, 1023.0);
    float word = floor(clamped + 0.5) * 64.0;
    float hi = floor(word / 256.0);
    float lo = word - hi * 256.0;
    return vec2(lo / 255.0, hi / 255.0);
}

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int x0 = dst.x * 2;
    int x1 = min(x0 + 1, uWidth - 1);
    // 色度行 dst.y 对应画面自上而下的第 2*dst.y 与 2*dst.y+1 行。
    int sy0 = uHeight - 1 - dst.y * 2;
    int sy1 = max(sy0 - 1, 0);

    vec3 sum = texelFetch(uSource, ivec2(x0, sy0), 0).rgb +
        texelFetch(uSource, ivec2(x1, sy0), 0).rgb +
        texelFetch(uSource, ivec2(x0, sy1), 0).rgb +
        texelFetch(uSource, ivec2(x1, sy1), 0).rgb;
    vec3 rgb = sum * 0.25;

    float luma = dot(rgb, K_LUMA);
    float cb = (rgb.b - luma) / KB_SCALE;
    float cr = (rgb.r - luma) / KR_SCALE;
    // 有限范围 10 位色度：中点 512，上下各 448（即 64…960）。
    outColor = vec4(
        encodeSample(512.0 + cb * 896.0),
        encodeSample(512.0 + cr * 896.0)
    );
}
