#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

// 应用自有 P010 的 **CbCr 平面**（4:2:0，交错存放）。
//
// 一个输出 texel 正好对应一组 (Cb, Cr)，共 4 字节，与 RGBA8 一一对应；输出纹理尺寸就是
// 半宽半高，回读出来的字节序正好是 P010 的交错色度平面。
//
// 与 FableSolExportP010Math 逐行对照，三件事必须与它一致（改这里就要改那边，反之亦然）：
//
// 1. **颜色定义随输出格式走**（D158 第 3 条）。10-bit SDR 是 BT.709 NCL，HDR10/HDR10+ 是
//    BT.2020 NCL + PQ，HLG/杜比视界 8.4 是 BT.2020 NCL + HLG。系数由 uniform 传入，着色器
//    里不写死任何一套。
// 2. **有相位的低通滤波**（D154、D170），不再用无位置语义的 2×2 box average——后者的色度
//    中心落在 (0.5, 0.5)，等价于 H.273 Type 1，而 BT.2020/BT.2100 规定 Type 2。相位由短探测
//    读到的码流声明决定：Type 2 共点、编码器声明别的合法类型就匹配它、什么都没声明按 Type 0
//    兼容语义。基础抽头是 ITU-T H.Sup15 的 f0 = [1/8, 6/8, 1/8]；居中相位不能把三抽头硬套到
//    半整数位置上，改用同族的对称四抽头 [1,3,3,1]/8。
// 3. **码值域蓝噪声阈值舍入**（D157），不是四舍五入。阈值表不可用时 uNoiseEnabled 为假，
//    阈值固定 0.5，即普通四舍五入。
//
// 色差定义在**非线性**量上（BT.2020/BT.709 都是非恒定亮度系统），所以滤波在 R'G'B' 域做，
// 不能先转回线性再平均。
//
// 坐标一律用**自上而下**的画面坐标，只在取样那一刻换算成 GL 的 y 向上：P010 要求第一行是
// 画面顶行，而 glReadPixels 从 GL 的最下面一行开始返回。

in vec2 vUv;

uniform sampler2D uSource;        // 呈现中间面：RGBA16F 或 RGB10_A2，格式专属 R'G'B'
uniform usampler2D uNoise;        // 64x64 蓝噪声秩（R16UI）
uniform int uWidth;
uniform int uHeight;
uniform vec3 uLumaWeights;        // kr, kg, kb
uniform vec2 uChromaScale;        // cbScale, crScale
uniform vec2 uChromaPhase;        // 水平、垂直相位，单位是亮度样本间距
// 本次信号范围的色度码值边界：(cbMin, cbMax, crMin, crMax)。Cb 与 Cr 分开给——D140 要求
// 分别求出两个分量的连续安全区间，合并成一条会让较窄的那个在边缘像素上越界。
uniform vec4 uChromaCodeRange;
uniform bool uNoiseEnabled;
uniform ivec2 uNoisePhaseCb;
uniform ivec2 uNoisePhaseCr;

out vec4 outColor;

const int NOISE_SIZE = 64;
const float NOISE_LEVELS = 4096.0;
const float CHROMA_MID_CODE = 512.0;
const float CHROMA_RANGE = 896.0;

struct Taps {
    ivec4 offsets;
    vec4 weights;
    int count;
};

/** 相对 `2 * 输出下标` 的抽头。与 FableSolExportP010Math.downsampleTaps 同一张表。 */
Taps tapsFor(float phase) {
    Taps taps;
    if (phase > 0.25 && phase < 0.75) {
        taps.offsets = ivec4(-1, 0, 1, 2);
        taps.weights = vec4(0.125, 0.375, 0.375, 0.125);
        taps.count = 4;
    } else {
        int centre = phase >= 0.75 ? 1 : 0;
        taps.offsets = ivec4(centre - 1, centre, centre + 1, 0);
        taps.weights = vec4(0.125, 0.75, 0.125, 0.0);
        taps.count = 3;
    }
    return taps;
}

/** 边界一致延拓：夹住下标而不是折返，首末色度样本因此不会被挪到另一个采样位置。 */
vec3 fetchTopDown(int x, int y) {
    int cx = clamp(x, 0, uWidth - 1);
    int cy = clamp(y, 0, uHeight - 1);
    return texelFetch(uSource, ivec2(cx, uHeight - 1 - cy), 0).rgb;
}

float noiseThreshold(ivec2 coordinate, ivec2 phase) {
    if (!uNoiseEnabled) return 0.5;
    ivec2 p = (coordinate + phase) % NOISE_SIZE;
    return (float(texelFetch(uNoise, p, 0).r) + 0.5) / NOISE_LEVELS;
}

/** `floor(value + threshold)`：期望值等于 value，误差始终不足一个目标码值。 */
float quantize(float value, float threshold, vec2 codeRange) {
    return clamp(floor(value + threshold), codeRange.x, codeRange.y);
}

/** P010：10 位有效值放在 16 位字的高位，小端拆成两个字节。 */
vec2 packSample(float code) {
    float word = clamp(code, 0.0, 1023.0) * 64.0;
    float hi = floor(word / 256.0);
    float lo = word - hi * 256.0;
    return vec2(lo / 255.0, hi / 255.0);
}

void main() {
    ivec2 dst = ivec2(gl_FragCoord.xy);
    int baseX = dst.x * 2;
    int baseY = dst.y * 2;

    Taps horizontal = tapsFor(uChromaPhase.x);
    Taps vertical = tapsFor(uChromaPhase.y);
    vec3 rgb = vec3(0.0);
    for (int j = 0; j < vertical.count; ++j) {
        float wy = vertical.weights[j];
        int sy = baseY + vertical.offsets[j];
        for (int i = 0; i < horizontal.count; ++i) {
            rgb += fetchTopDown(baseX + horizontal.offsets[i], sy) *
                (horizontal.weights[i] * wy);
        }
    }

    float luma = dot(rgb, uLumaWeights);
    float cb = (rgb.b - luma) / uChromaScale.x;
    float cr = (rgb.r - luma) / uChromaScale.y;
    float cbCode = quantize(
        CHROMA_MID_CODE + cb * CHROMA_RANGE,
        noiseThreshold(dst, uNoisePhaseCb),
        uChromaCodeRange.xy
    );
    float crCode = quantize(
        CHROMA_MID_CODE + cr * CHROMA_RANGE,
        noiseThreshold(dst, uNoisePhaseCr),
        uChromaCodeRange.zw
    );
    outColor = vec4(packSample(cbCode), packSample(crCode));
}
