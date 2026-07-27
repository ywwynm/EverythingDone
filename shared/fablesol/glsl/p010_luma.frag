#version 300 es
precision highp float;

// RGB → P010 的 **Y 平面**。
//
// 为什么要自己做这一步：HDR10+ 的动态元数据只能在**字节缓冲输入**模式下逐帧提供
//（`PARAMETER_KEY_HDR10_PLUS_INFO` 在 surface 输入模式下被系统明确禁止），而字节缓冲模式
// 下 RGB→YUV 就不再由编码器代劳，得我们自己交出 P010。
//
// 输出目标是 **RGBA8**，不是 16 位整数纹理：ES 3.0 只保证 `GL_RGBA` + `GL_UNSIGNED_BYTE`
// 这一组 glReadPixels 组合可用，整数纹理的回读格式是实现自定的，靠不住。因此一个输出
// texel 装**两个相邻的 Y 样本**（4 字节 = 2×16 位），回读出来的字节序正好就是 P010 的
// Y 平面本身。
//
// 行序：GL 的原点在左下，glReadPixels 从最下面一行开始返回；而 P010 要求第一行是画面
// 顶行。所以这里按 uHeight-1-y 反向取源，读回来自然就是自上而下。

in vec2 vUv;

uniform sampler2D uSource;   // RGB10_A2，已经是 PQ 编码后的 R'G'B'，全范围
uniform int uWidth;
uniform int uHeight;

out vec4 outColor;

// BT.2020 非恒定亮度的亮度系数。
const vec3 K_LUMA = vec3(0.2627, 0.6780, 0.0593);

/** 10 位样本按 P010 排布：有效位在高 10 位（即 <<6），16 位小端拆成两个字节。 */
vec2 encodeSample(float value10) {
    float clamped = clamp(value10, 0.0, 1023.0);
    float word = floor(clamped + 0.5) * 64.0;
    float hi = floor(word / 256.0);
    float lo = word - hi * 256.0;
    return vec2(lo / 255.0, hi / 255.0);
}

float lumaAt(int x, int y) {
    vec3 rgb = texelFetch(uSource, ivec2(x, y), 0).rgb;
    // 有限范围 10 位：黑 64、白 940。
    return 64.0 + dot(rgb, K_LUMA) * 876.0;
}

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int sourceY = uHeight - 1 - dst.y;
    int x0 = dst.x * 2;
    int x1 = min(x0 + 1, uWidth - 1);
    outColor = vec4(
        encodeSample(lumaAt(x0, sourceY)),
        encodeSample(lumaAt(x1, sourceY))
    );
}
