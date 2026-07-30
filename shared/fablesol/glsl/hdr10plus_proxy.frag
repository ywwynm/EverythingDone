#version 300 es
precision highp float;
precision highp int;

// ApplicationVersion 1 `FractionBrightPixels` 的 5:1 代理帧（D108，ST 2094-40 §10.4）。
//
// 规范要求的是**平滑缩小**，不是每隔五个像素抽一个——抽样会把一根一像素宽的银丝要么整根
// 留下、要么整根丢掉，得到的高亮面积完全取决于相位。这里对每个代理像素所覆盖的 5×5 源像素
// 取面积平均，再按 BT.2020/D65 求亮度（式 6）。
//
// 亮度按 24 位打进 RGBA8 回读：代理帧只有约 217×294，回读量可以忽略，而 24 位远细于权重
// 函数 1/255～5/255 的判据尺度。

in vec2 vUv;

uniform sampler2D uSource;   // 最终可见合成的线性 BT.2020，漫反射白归一化
uniform int uWidth;
uniform int uHeight;
uniform float uScale;        // 漫反射白尼特 / 10000

out vec4 outColor;

const int PROXY_SCALE = 5;
const vec3 LUMA = vec3(0.2627, 0.6780, 0.0593);

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int x0 = dst.x * PROXY_SCALE;
    int y0 = dst.y * PROXY_SCALE;
    vec3 total = vec3(0.0);
    int count = 0;
    for (int j = 0; j < PROXY_SCALE; ++j) {
        int y = y0 + j;
        if (y >= uHeight) break;
        for (int i = 0; i < PROXY_SCALE; ++i) {
            int x = x0 + i;
            if (x >= uWidth) break;
            total += max(texelFetch(uSource, ivec2(x, y), 0).rgb, vec3(0.0));
            count += 1;
        }
    }
    vec3 mean = count > 0 ? total / float(count) : vec3(0.0);
    float luminance = dot(mean * uScale, LUMA);

    float code = floor(clamp(luminance, 0.0, 1.0) * 16777215.0 + 0.5);
    float high = floor(code / 65536.0);
    float rest = code - high * 65536.0;
    float mid = floor(rest / 256.0);
    float low = rest - mid * 256.0;
    outColor = vec4(low / 255.0, mid / 255.0, high / 255.0, 1.0);
}
