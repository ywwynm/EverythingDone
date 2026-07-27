#version 300 es
precision highp float;

// HDR10+ 动态元数据要的**逐帧亮度统计**，用一次 GPU 归约算出来。
//
// 不在 CPU 上扫全帧：一帧上百万像素，逐像素做 YCbCr→RGB 再统计，在 Kotlin 里每帧要几十
// 毫秒，乘上 120fps × 几分钟音频就完全不能接受了。这里把画面缩到 STATS_SIZE×STATS_SIZE，
// 每个输出 texel 归约它覆盖的那一块。
//
// 输出是 RGBA8：R/G/B = 该块内各通道的**最大值**，A = 该块内 max(R,G,B) 的**平均值**。
// 都是 PQ 编码后的非线性值，CPU 端再套 EOTF 换成尼特。
//
// 8 位量化带来的误差在峰值附近约百分之几——对"告诉播放端该按多高的峰值还原"这件事完全
// 够用，而且它是**降低精度的测量**，不是编造。真正不能省的是"取最大值"这个语义：块内取
// max 而不是取平均，才不会把水面上那些很小的高光点漏掉。

in vec2 vUv;

uniform sampler2D uSource;   // RGB10_A2，PQ 编码后的 R'G'B'
uniform int uWidth;
uniform int uHeight;
uniform int uBlockW;
uniform int uBlockH;

out vec4 outColor;

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int originX = dst.x * uBlockW;
    int originY = dst.y * uBlockH;

    vec3 peak = vec3(0.0);
    float sum = 0.0;
    float count = 0.0;
    for (int j = 0; j < uBlockH; ++j) {
        int y = originY + j;
        if (y >= uHeight) break;
        for (int i = 0; i < uBlockW; ++i) {
            int x = originX + i;
            if (x >= uWidth) break;
            vec3 rgb = texelFetch(uSource, ivec2(x, y), 0).rgb;
            peak = max(peak, rgb);
            sum += max(rgb.r, max(rgb.g, rgb.b));
            count += 1.0;
        }
    }
    float mean = count > 0.0 ? sum / count : 0.0;
    outColor = vec4(clamp(peak, 0.0, 1.0), clamp(mean, 0.0, 1.0));
}
