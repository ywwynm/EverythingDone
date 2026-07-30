#version 300 es
precision highp float;
precision highp int;

// HDR10 / HDR10+ 的全片静态亮度预分析归约（fablesol-video-export D85、D86）。
//
// 读的是**最终可见合成**的线性 BT.2020 画面——`export_present.frag` 在分析模式下不套 OETF，
// 直接把 `bt709ToBt2020(color)` 写进 FP16 中间面。因此 FableSol 场景、画框背景、卡片、投影、
// 描边与时钟全部计入，与 D73 为动态 SDR 排除外围图形的口径**不同**：那条是创作映射的口径，
// 这条是内容亮度元数据的口径。
//
// 每个输出 texel 归约源图的一个矩形块，同时给出该块的最大值与平均值：
//
// - `MaxCLL` 需要全片全部像素 `max(R,G,B)` 的最大值 —— 块最大值再取最大即可；
// - `MaxFALL` 需要逐帧平均值的全片最大值 —— 块平均值按块内实际像素数加权求和，
//   加权在 CPU 上做，因为边缘块可能不满。
//
// 回读格式固定 RGBA8：ES 3.0 只保证 `GL_RGBA` + `GL_UNSIGNED_BYTE` 这一组 glReadPixels
// 组合可用。最大值放 R、G 两字节，平均值放 B、A 两字节，都是按 uMaxValue 归一的 16 位小端。

uniform sampler2D uSource;
uniform int uWidth;
uniform int uHeight;
uniform int uBlockW;
uniform int uBlockH;
uniform float uMaxValue;

out vec4 outColor;

vec2 pack16(float value) {
    // 上取整而不是四舍五入：MaxCLL/MaxFALL 的语义是**上界**（D90），量化方向必须与语义
    // 同侧。四舍五入可把峰值压低半个台阶（强度 16 档时约 0.025 尼特），真值贴在整数尼特
    // 上方时，解码后 ceil 到整数尼特仍会低报 1 尼特。上取整最多高报一个台阶，上界语义下
    // 无害。
    float code = ceil(clamp(value / uMaxValue, 0.0, 1.0) * 65535.0);
    float hi = floor(code / 256.0);
    return vec2((code - hi * 256.0) / 255.0, hi / 255.0);
}

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int x0 = dst.x * uBlockW;
    int y0 = dst.y * uBlockH;
    float peak = 0.0;
    float total = 0.0;
    int count = 0;
    for (int j = 0; j < uBlockH; ++j) {
        int y = y0 + j;
        if (y >= uHeight) break;
        for (int i = 0; i < uBlockW; ++i) {
            int x = x0 + i;
            if (x >= uWidth) break;
            vec3 c = max(texelFetch(uSource, ivec2(x, y), 0).rgb, vec3(0.0));
            float m = max(c.r, max(c.g, c.b));
            peak = max(peak, m);
            total += m;
            count += 1;
        }
    }
    float mean = count > 0 ? total / float(count) : 0.0;
    outColor = vec4(pack16(peak), pack16(mean));
}
