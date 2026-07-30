#version 300 es
precision highp float;
precision highp int;

// HDR10+ 统计的 GLES 3.0 精确兼容后端（fablesol-video-export D104、D124）。
//
// 没有 compute shader 与 SSBO 时，把每个像素的量按 24 位打进全分辨率 RGBA8，用项目已有保证
// 路径的 `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 读回，CPU 只做解包、直方图计数与求和。
// 24 位（1/16777215）细于 0.00001 的载荷网格，因此这条路的统计定义与画质**不降级**，代价是
// 每帧多一次全分辨率回读——D124 明确要求首版先用结构简单、易于用已知像素图验证的实现。
//
// `uChannel`：0 = max(R,G,B)（直方图与总和），1/2/3 = 单独的 R/G/B（MaxSCL，D103 要求不
// 经过桶）。四种模式共用一套打包，CPU 侧的解包因此只有一份。

in vec2 vUv;

uniform sampler2D uSource;   // 最终可见合成的线性 BT.2020，漫反射白归一化
uniform int uWidth;
uniform int uHeight;
uniform float uScale;        // 漫反射白尼特 / 10000
uniform int uChannel;

out vec4 outColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 linearRgb = vec3(0.0);
    if (pixel.x < uWidth && pixel.y < uHeight) {
        linearRgb = max(texelFetch(uSource, pixel, 0).rgb, vec3(0.0)) * uScale;
    }
    float value =
        uChannel == 1 ? linearRgb.r :
        uChannel == 2 ? linearRgb.g :
        uChannel == 3 ? linearRgb.b :
        max(linearRgb.r, max(linearRgb.g, linearRgb.b));

    float code = floor(clamp(value, 0.0, 1.0) * 16777215.0 + 0.5);
    float high = floor(code / 65536.0);
    float rest = code - high * 65536.0;
    float mid = floor(rest / 256.0);
    float low = rest - mid * 256.0;
    outColor = vec4(low / 255.0, mid / 255.0, high / 255.0, 1.0);
}
