#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

// 应用自有 P010 的 **Y 平面**，含闭环亮度修正（D155、D156、D159）。
//
// 输出目标是 **RGBA8**，不是 16 位整数纹理：ES 3.0 只保证 `GL_RGBA` + `GL_UNSIGNED_BYTE`
// 这一组 glReadPixels 组合可用，整数纹理的回读格式是实现自定的。因此一个输出 texel 装
// **两个相邻的 Y 样本**（4 字节 = 2×16 位），回读出来的字节序正好就是 P010 的 Y 平面。
//
// **必须在色度之后跑**（D157 第 4 条）：闭环读的是本帧真正写出去的量化 Cb/Cr，不是"假设按
// 四舍五入产生"的色度。4:2:0 把全分辨率 Y' 与半分辨率 Cb/Cr 分开编码，播放端重新上采样后
// 每个像素结合的色度已经不同于原始 4:4:4；若原样保留 Y'，重建后的线性亮度会在高饱和细边缘
// 明显偏离——即 chroma leakage，钟表文字、星光与水面高光附近的错误明暗轮廓就是它。
//
// 修正用 ITU-T H.Sup15 的单步闭式（局部线性化）解，不做逐像素二分；高精度迭代解只在 JVM
// 测试里作 oracle。四条门禁与 FableSolExportP010Math.correctLuma 完全一致：斜率稳定、改变量
// 有界、误差确实降低、不引入新的 R'G'B' 非法值。
//
// 目标域随传递函数变，这一点不能混：PQ 比显示线性亮度，HLG 比场景线性亮度（播放端 OOTF
// 随显示条件变化，不能钉在某个峰值上），SDR 比经 BT.1886 参考显示得到的显示线性亮度。
//
// 坐标一律用**自上而下**的画面坐标，只在取样那一刻换算成 GL 的 y 向上。

in vec2 vUv;

uniform sampler2D uSource;        // 呈现中间面：RGBA16F 或 RGB10_A2，格式专属 R'G'B'
uniform sampler2D uChroma;        // 上一趟写出的量化 CbCr（RGBA8 打包）
uniform usampler2D uNoise;        // 64x64 蓝噪声秩（R16UI）
uniform int uWidth;
uniform int uHeight;
uniform int uTransfer;            // 0 = BT.1886，1 = PQ，2 = HLG
uniform vec3 uLumaWeights;        // kr, kg, kb
uniform vec2 uChromaScale;        // cbScale, crScale
uniform vec2 uChromaPhase;        // 与色度趟完全相同的相位
uniform vec2 uLumaCodeRange;      // 本次信号范围的亮度码值上下界
uniform float uMaxLumaCorrection; // 闭环最大改变量（信号域，= 24 码值 / 876）
uniform bool uNoiseEnabled;
uniform ivec2 uNoisePhaseLuma;

out vec4 outColor;

const int NOISE_SIZE = 64;
const float NOISE_LEVELS = 4096.0;
const float LUMA_MIN_CODE = 64.0;
const float LUMA_RANGE = 876.0;
const float CHROMA_MID_CODE = 512.0;
const float CHROMA_RANGE = 896.0;

const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;

const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;

const float BT1886_GAMMA = 2.4;
const float SLOPE_EPSILON = 1e-9;
const float LEGAL_EPSILON = 1e-6;

struct Taps {
    ivec2 offsets;
    vec2 weights;
    int count;
};

/**
 * 参考上采样抽头：偏移相对 `下标 / 2`。必须与降采样同一相位，否则"修正后的重建"与播放端
 * 看到的不是同一件事。与 FableSolExportP010Math.upsampleTaps 同一张表。
 */
Taps upsampleTaps(int index, float phase) {
    bool even = (index - (index / 2) * 2) == 0;
    Taps taps;
    if (phase < 0.25) {
        taps.offsets = even ? ivec2(0, 0) : ivec2(0, 1);
        taps.weights = even ? vec2(1.0, 0.0) : vec2(0.5, 0.5);
        taps.count = even ? 1 : 2;
    } else if (phase > 0.75) {
        taps.offsets = even ? ivec2(-1, 0) : ivec2(0, 0);
        taps.weights = even ? vec2(0.5, 0.5) : vec2(1.0, 0.0);
        taps.count = even ? 2 : 1;
    } else {
        taps.offsets = even ? ivec2(-1, 0) : ivec2(0, 1);
        taps.weights = even ? vec2(0.25, 0.75) : vec2(0.75, 0.25);
        taps.count = 2;
    }
    return taps;
}

vec3 fetchTopDown(int x, int y) {
    int cx = clamp(x, 0, uWidth - 1);
    int cy = clamp(y, 0, uHeight - 1);
    return texelFetch(uSource, ivec2(cx, uHeight - 1 - cy), 0).rgb;
}

/** 解出色度趟实际写出的 10 位码值。色度纹理本身已是自上而下排布，不需要再翻。 */
vec2 chromaCodesAt(int x, int y) {
    int cx = clamp(x, 0, uWidth / 2 - 1);
    int cy = clamp(y, 0, uHeight / 2 - 1);
    vec4 texel = texelFetch(uChroma, ivec2(cx, cy), 0);
    float cbWord = floor(texel.r * 255.0 + 0.5) + floor(texel.g * 255.0 + 0.5) * 256.0;
    float crWord = floor(texel.b * 255.0 + 0.5) + floor(texel.a * 255.0 + 0.5) * 256.0;
    return vec2(floor(cbWord / 64.0), floor(crWord / 64.0));
}

// 域名义限制（2026-07-30 裁定）：闭环只在名义 [0,1] 域内评估与修正。super-white 扩展域
// （≤109%）里求值饱和、改善判据两边相等，correctLuma 自然退化为保留原始 Y′——方向安全，
// 该区域的亚码值亮度误差不做修正。与 export_present.frag 的 hlgOetfChannel 有意工作在
// 扩展域的口径不同：各自目的使然。与 FableSolExportP010Math.toLinear 逐位对应，不得单侧放开。
float toLinear(float value) {
    float v = clamp(value, 0.0, 1.0);
    if (uTransfer == 1) {
        if (v <= 0.0) return 0.0;
        float p = pow(v, 1.0 / PQ_M2);
        float numerator = max(p - PQ_C1, 0.0);
        float denominator = PQ_C2 - PQ_C3 * p;
        if (denominator <= SLOPE_EPSILON) return 1.0;
        return pow(numerator / denominator, 1.0 / PQ_M1);
    }
    if (uTransfer == 2) {
        return v <= 0.5 ? v * v / 3.0 : (exp((v - HLG_C) / HLG_A) + HLG_B) / 12.0;
    }
    return pow(v, BT1886_GAMMA);
}

float linearSlope(float value) {
    float v = clamp(value, 0.0, 1.0);
    if (uTransfer == 1) {
        if (v <= SLOPE_EPSILON) return 0.0;
        float p = pow(v, 1.0 / PQ_M2);
        float numerator = p - PQ_C1;
        if (numerator <= 0.0) return 0.0;
        float denominator = PQ_C2 - PQ_C3 * p;
        if (denominator <= SLOPE_EPSILON) return 0.0;
        float ratio = numerator / denominator;
        float dRatio = (denominator + PQ_C3 * numerator) / (denominator * denominator);
        float dP = pow(v, 1.0 / PQ_M2 - 1.0) / PQ_M2;
        return pow(ratio, 1.0 / PQ_M1 - 1.0) / PQ_M1 * dRatio * dP;
    }
    if (uTransfer == 2) {
        return v <= 0.5 ? 2.0 * v / 3.0 : exp((v - HLG_C) / HLG_A) / (12.0 * HLG_A);
    }
    return BT1886_GAMMA * pow(v, BT1886_GAMMA - 1.0);
}

vec3 toRgb(float luma, float cb, float cr) {
    float r = luma + cr * uChromaScale.y;
    float b = luma + cb * uChromaScale.x;
    float g = (luma - uLumaWeights.x * r - uLumaWeights.z * b) / uLumaWeights.y;
    return vec3(r, g, b);
}

float luminanceOf(vec3 rgb) {
    return dot(
        uLumaWeights, vec3(toLinear(rgb.r), toLinear(rgb.g), toLinear(rgb.b))
    );
}

bool isLegal(vec3 rgb) {
    return all(greaterThanEqual(rgb, vec3(-LEGAL_EPSILON))) &&
        all(lessThanEqual(rgb, vec3(1.0 + LEGAL_EPSILON)));
}

/** 与 FableSolExportP010Math.correctLuma 一一对应。不改善或解不稳定时保留原始 Y'。 */
float correctLuma(float target, float originalLuma, float cb, float cr) {
    vec3 originalRgb = toRgb(originalLuma, cb, cr);
    bool originalLegal = isLegal(originalRgb);
    float originalLuminance = luminanceOf(originalRgb);
    float slope = dot(
        uLumaWeights,
        vec3(
            linearSlope(originalRgb.r),
            linearSlope(originalRgb.g),
            linearSlope(originalRgb.b)
        )
    );
    if (!(slope > SLOPE_EPSILON)) return originalLuma;
    float step = (target - originalLuminance) / slope;
    if (isnan(step) || isinf(step)) return originalLuma;
    float candidateLuma = originalLuma +
        clamp(step, -uMaxLumaCorrection, uMaxLumaCorrection);
    vec3 candidateRgb = toRgb(candidateLuma, cb, cr);
    if (originalLegal && !isLegal(candidateRgb)) return originalLuma;
    float candidateLuminance = luminanceOf(candidateRgb);
    return abs(candidateLuminance - target) < abs(originalLuminance - target)
        ? candidateLuma
        : originalLuma;
}

float noiseThreshold(ivec2 coordinate) {
    if (!uNoiseEnabled) return 0.5;
    ivec2 p = (coordinate + uNoisePhaseLuma) % NOISE_SIZE;
    return (float(texelFetch(uNoise, p, 0).r) + 0.5) / NOISE_LEVELS;
}

vec2 packSample(float code) {
    float word = clamp(code, 0.0, 1023.0) * 64.0;
    float hi = floor(word / 256.0);
    float lo = word - hi * 256.0;
    return vec2(lo / 255.0, hi / 255.0);
}

/** 一个亮度样本：原始 Y' → 闭环修正 → 蓝噪声阈值量化 → P010 码值。 */
float lumaCodeAt(int x, int y) {
    vec3 rgb = fetchTopDown(x, y);
    float originalLuma = dot(rgb, uLumaWeights);

    Taps horizontal = upsampleTaps(x, uChromaPhase.x);
    Taps vertical = upsampleTaps(y, uChromaPhase.y);
    vec2 codes = vec2(0.0);
    for (int j = 0; j < vertical.count; ++j) {
        float wy = vertical.weights[j];
        int cy = y / 2 + vertical.offsets[j];
        for (int i = 0; i < horizontal.count; ++i) {
            codes += chromaCodesAt(x / 2 + horizontal.offsets[i], cy) *
                (horizontal.weights[i] * wy);
        }
    }
    float cb = (codes.x - CHROMA_MID_CODE) / CHROMA_RANGE;
    float cr = (codes.y - CHROMA_MID_CODE) / CHROMA_RANGE;

    float target = luminanceOf(rgb);
    float luma = correctLuma(target, originalLuma, cb, cr);
    return clamp(
        floor(LUMA_MIN_CODE + luma * LUMA_RANGE + noiseThreshold(ivec2(x, y))),
        uLumaCodeRange.x,
        uLumaCodeRange.y
    );
}

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int x0 = dst.x * 2;
    int x1 = min(x0 + 1, uWidth - 1);
    outColor = vec4(
        packSample(lumaCodeAt(x0, dst.y)),
        packSample(lumaCodeAt(x1, dst.y))
    );
}
