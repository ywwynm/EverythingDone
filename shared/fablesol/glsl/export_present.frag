#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

// FableSol 可视化视频导出的最终呈现。屏上的 present.frag 一行不动——这条是独立 program，
// 逐帧关键路径不为导出背任何分支（fablesol-video-export D4）。
//
// 一遍做完：画框底色 → 卡片投影 → 圆角卡片内的水体（必要时先做 SDR 色调映射）→ 时钟叠加
// → 传递函数编码 →（8-bit 档）码值域蓝噪声抖动。全程在线性光里合成，最后一步才套 OETF，
// 因此时钟的 alpha 混合是物理正确的。
//
// **色调映射只作用于场景纹理**，在时钟合成之前（D73）：padding、画框底色、投影、描边和
// 时钟都是 SDR 图形元素，它们本来就在 0～1 之内，被高亮压缩一遍只会平白变暗。

in vec2 vUv;

uniform sampler2D uScene;
uniform sampler2D uClock;
uniform usampler2D uNoise;      // 64x64 蓝噪声秩（R16UI）；资源不可用时是 1x1 中性占位
// HLG 的两张查表（D164、D165）；非 HLG 档位绑的是 1x1 占位——sampler 绑到不完整纹理是
// 未定义行为，即便那条分支不会执行。
uniform sampler2D uHlgShoulder; // 一维肩部参数表：键 C_n，值 ξ（R32F，size x 1）
uniform sampler2D uHlgDevice;   // 方向域 W_device 查表（R32F，grid x 3*grid）

uniform vec2  uCardOriginPx;     // 卡片左下角（GL 坐标，y 向上）
uniform vec2  uCardSizePx;
uniform float uCornerRadiusPx;

uniform vec3  uBackdropColor;    // sRGB 编码
uniform float uShadowOffsetPx;   // 向下的偏移量（GL 里即 -y）
uniform float uShadowRadiusPx;
uniform float uShadowAlpha;
uniform vec3  uRimColor;         // sRGB 编码
uniform float uRimAlpha;
uniform float uRimWidthPx;

uniform vec4  uClockRectPx;      // x, y, w, h（GL 坐标）
uniform float uClockAlpha;

uniform bool  uSceneLinear;
uniform float uHdrHeadroom;
// 0 = BT.709 SDR，1 = BT.2020 HLG，2 = BT.2020 PQ，3 = 线性 BT.2020（全片亮度预分析，D86）
uniform int   uTransfer;
uniform bool  uDither;
uniform bool  uNoiseEnabled;     // 假 = 蓝噪声资源不可用，退回三角哈希（D162 第 5 条）
uniform float uSdrWhiteNits;     // SDR 参考白的绝对亮度（PQ 用；BT.2408 = 203 尼特）

// HLG 输出变换的参数（D126～D134、D164、D165）；与 FableSolExportHlgTransform 一一对应。
uniform float uHlgDisplayWhite;  // D_ref = E_ref^γ，参考白的显示线性归一化值
uniform float uHlgKneeNorm;      // K_n = K_D^(1/γ)，归一化肩部起点
uniform float uHlgHeadroomNorm;  // H_n = H_D^(1/γ)，归一化端点
uniform float uHlgSignalMax;     // 标准信号上限（super-white 1.09，名义范围 1.0）
uniform vec2  uHlgShoulderDomain;// 肩部表的 C_n 定义域
uniform int   uHlgShoulderSize;
uniform bool  uHlgDeviceEnabled; // 假 = 全方向共用 uHlgDeviceCeiling，不查表
uniform float uHlgDeviceCeiling;
uniform int   uHlgDeviceGrid;

// SDR 保留高光层次的曲线参数；与 FableSolExportSdrToneMap.Curve 一一对应（D68～D76）。
uniform bool  uToneMap;
uniform float uToneKnee;         // K：其下完全恒等
uniform float uToneWhite;        // W = F(1)：HDR 参考白的落点
uniform float uTonePeak;         // P：本帧的控制峰值
uniform float uToneExponent;     // p ≥ 1：超白段的指数
uniform float uToneTarget;       // T = F(P) ≤ 1

// 附件 0：格式专属输出变换后的 R'G'B'，交给 P010 或直接写进编码器 Surface。
layout(location = 0) out vec4 fragColor;
// 附件 1：同一次合成的**线性 BT.2020**，只在 HDR10+ 需要逐帧统计时绑定（D102、D103）。
// 走同一趟而不是再画一遍，统计与编码输入因此不可能来自两张不同的画面；PQ 往返再反解会在
// 1000 尼特附近损失约 4 尼特，远粗于 0.1 尼特的载荷网格。
layout(location = 1) out vec4 fragLinear;

const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;
const float PQ_MAX_NITS = 10000.0;

const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;

// 参考 HLG 显示器的 system gamma（BT.2100，1000 尼特参考显示条件）与 BT.2020 亮度权重。
const float HLG_GAMMA = 1.2;
const vec3  HLG_LUMA = vec3(0.2627, 0.6780, 0.0593);
// ξ 小于这个数时肩部与恒等映射的差别已在浮点噪声以内。
const float HLG_XI_EPSILON = 1e-4;

const int NOISE_SIZE = 64;
const float NOISE_LEVELS = 4096.0;

float srgbToLinearChannel(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

vec3 srgbToLinear(vec3 c) {
    return vec3(
        srgbToLinearChannel(c.r),
        srgbToLinearChannel(c.g),
        srgbToLinearChannel(c.b)
    );
}

float linearToBt709Channel(float c) {
    // BT.709 OETF。与 sRGB 曲线在观感上极接近，但视频侧的正规写法是这一条。
    return c < 0.018 ? 4.5 * c : 1.099 * pow(c, 0.45) - 0.099;
}

vec3 linearToBt709(vec3 c) {
    c = clamp(c, vec3(0.0), vec3(1.0));
    return vec3(
        linearToBt709Channel(c.r),
        linearToBt709Channel(c.g),
        linearToBt709Channel(c.b)
    );
}

vec3 bt709ToBt2020(vec3 c) {
    return vec3(
        dot(c, vec3(0.62740390, 0.32928304, 0.04331307)),
        dot(c, vec3(0.06909729, 0.91954040, 0.01136231)),
        dot(c, vec3(0.01639144, 0.08801331, 0.89559525))
    );
}

/**
 * PQ（ST.2084）的反 EOTF。绝对亮度曲线：先把线性值按 uSdrWhiteNits 折成尼特，再对 10000
 * 归一。强度 9.6 折合约 1949 尼特，远在上限之内——**因此 PQ 分支不需要任何压缩**，
 * 下面那条渐进压缩只服务 HLG。
 */
float pqInverseEotfChannel(float nits) {
    float y = clamp(nits / PQ_MAX_NITS, 0.0, 1.0);
    float ym = pow(y, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * ym) / (1.0 + PQ_C3 * ym), PQ_M2);
}

/**
 * BT.2100 HLG OETF。**上界不钳到 1.0**：super-white 区间正是靠这条曲线在名义峰值以上的
 * 自然延拓表达的（D134），钳死就永远产不出 100% 以上的信号。
 */
float hlgOetfChannel(float e) {
    e = max(e, 0.0);
    return e <= 1.0 / 12.0 ? sqrt(3.0 * e) : HLG_A * log(12.0 * e - HLG_B) + HLG_C;
}

float hlgInverseOetfChannel(float v) {
    v = max(v, 0.0);
    return v <= 0.5 ? v * v / 3.0 : (exp((v - HLG_C) / HLG_A) + HLG_B) / 12.0;
}

/**
 * 该方向经设备回环验证后的信号上限 `W_device(u)`（D165）。
 *
 * 方向域是 `max(u) = 1` 的三个立方体面；面 0 的最大分量是 R、面内坐标 (G, B)，面 1 是 G、
 * 坐标 (R, B)，面 2 是 B、坐标 (R, G)。与 FableSolExportHlgDeviceRange 同一约定，双线性
 * 插值也逐行对应——面与面共享边上的网格点由同一方向求得同一值，因此插值天然连续。
 */
float hlgDeviceCeiling(vec3 u) {
    if (!uHlgDeviceEnabled) return uHlgDeviceCeiling;
    int face;
    float a;
    float b;
    if (u.r >= u.g && u.r >= u.b) {
        face = 0; a = u.g; b = u.b;
    } else if (u.g >= u.b) {
        face = 1; a = u.r; b = u.b;
    } else {
        face = 2; a = u.r; b = u.g;
    }
    int last = uHlgDeviceGrid - 1;
    float x = clamp(a, 0.0, 1.0) * float(last);
    float y = clamp(b, 0.0, 1.0) * float(last);
    int x0 = clamp(int(floor(x)), 0, last);
    int y0 = clamp(int(floor(y)), 0, last);
    int x1 = min(x0 + 1, last);
    int y1 = min(y0 + 1, last);
    float fx = x - float(x0);
    float fy = y - float(y0);
    int base = face * uHlgDeviceGrid;
    float v00 = texelFetch(uHlgDevice, ivec2(x0, base + y0), 0).r;
    float v10 = texelFetch(uHlgDevice, ivec2(x1, base + y0), 0).r;
    float v01 = texelFetch(uHlgDevice, ivec2(x0, base + y1), 0).r;
    float v11 = texelFetch(uHlgDevice, ivec2(x1, base + y1), 0).r;
    return mix(mix(v00, v10, fx), mix(v01, v11, fx), fy);
}

/** 一维肩部参数表：以归一化容量 C_n 为键（D164），线性插值取 ξ。 */
float hlgShoulderXi(float capacity) {
    float span = uHlgShoulderDomain.y - uHlgShoulderDomain.x;
    if (span <= 0.0 || uHlgShoulderSize < 2) {
        return texelFetch(uHlgShoulder, ivec2(0, 0), 0).r;
    }
    float t = clamp((capacity - uHlgShoulderDomain.x) / span, 0.0, 1.0) *
        float(uHlgShoulderSize - 1);
    int i0 = clamp(int(floor(t)), 0, uHlgShoulderSize - 2);
    float f = t - float(i0);
    float lo = texelFetch(uHlgShoulder, ivec2(i0, 0), 0).r;
    float hi = texelFetch(uHlgShoulder, ivec2(i0 + 1, 0), 0).r;
    return mix(lo, hi, f);
}

/**
 * 归一化指数肩部（D131）：`F(K) = K`、`F'(K) = 1`、`F(H) = C`，局部斜率始终在 0～1。
 *
 * 旧实现是**逐通道**软肩，高光因此自然趋白；现在只对 maxRGB 求映射、再给 RGB 施加共同
 * 增益（D128 第 3 步），彩色高光的色度方向保持不变。
 */
float hlgShoulder(float value, float capacity, float xi) {
    if (value <= uHlgKneeNorm) return value;
    if (capacity <= uHlgKneeNorm) return min(value, capacity);
    if (xi <= HLG_XI_EPSILON) return value;
    float span = uHlgHeadroomNorm - uHlgKneeNorm;
    if (span <= 0.0) return value;
    float a = span / xi;
    return min(uHlgKneeNorm + a * (1.0 - exp(-(value - uHlgKneeNorm) / a)), capacity);
}

/**
 * 完整的 HLG 输出变换（D128、D132）。
 *
 * ```text
 * 显示线性 Rec.709 → 显示线性 BT.2020 → 按 D_ref 归一为参考显示光 → 逆 OOTF
 * → 场景线性方向肩部 → 共同 RGB 增益 → HLG OETF
 * ```
 *
 * 旧代码直接把显示线性值乘 0.26497 再套 OETF，等于把已经包含显示意图的图形渲染重新解释成
 * 摄像机场景光；参考 HLG 显示器随后再施加一次 1.2 的 OOTF，`0.5×` 参考白从应有的 101.5
 * 尼特掉到 88.4，`2.0×` 参考白从 406 涨到 466。
 */
vec3 hlgEncode(vec3 color) {
    vec3 wide = max(bt709ToBt2020(color), vec3(0.0));
    float s = max(color.r, max(color.g, color.b));
    float yD = dot(wide, HLG_LUMA);
    if (s <= 0.0 || yD <= 0.0) return vec3(0.0);
    // 逆 OOTF：E_S = D · Y_D^((1-γ)/γ)，其中 D = D_ref · wide。
    float scale = uHlgDisplayWhite *
        pow(uHlgDisplayWhite * yD, (1.0 - HLG_GAMMA) / HLG_GAMMA);
    vec3 scene = wide * scale;
    float m = max(scene.r, max(scene.g, scene.b));
    if (m <= 0.0) return vec3(0.0);
    // 1/γ 次齐次性：m = q(u) · s^(1/γ)，因此 q 可以直接由本像素反解，不必再跑一遍方向变换。
    float normalized = pow(s, 1.0 / HLG_GAMMA);
    float q = m / normalized;
    if (q <= 0.0) return vec3(0.0);

    vec3 u = color / s;
    // C_match(u) = (max(v) / Y_v)^((γ-1)/γ)，对正比例缩放不变，直接读 wide 即可。
    float matchCapacity = pow(
        max(wide.r, max(wide.g, wide.b)) / yD, (HLG_GAMMA - 1.0) / HLG_GAMMA
    );
    float ceiling = min(
        min(hlgOetfChannel(matchCapacity), uHlgSignalMax), hlgDeviceCeiling(u)
    );
    float capacity = hlgInverseOetfChannel(ceiling) / q;
    float mapped = hlgShoulder(normalized, capacity, hlgShoulderXi(capacity));
    float gain = mapped / normalized;
    vec3 signal = vec3(
        hlgOetfChannel(scene.r * gain),
        hlgOetfChannel(scene.g * gain),
        hlgOetfChannel(scene.b * gain)
    );
    // 端点由 A_n 的解精确落在 ceiling 上；这道钳位只为浮点舍入安全。
    return clamp(signal, vec3(0.0), vec3(ceiling));
}

/**
 * `SDR（保留高光层次）` 的亮度尺度曲线。三段与 FableSolExportSdrToneMap 逐行对应，
 * 改这里就要改那边，反之亦然。
 */
float sdrToneScalar(float m) {
    if (m <= uToneKnee) return m;
    float span = 1.0 - uToneKnee;
    if (m <= 1.0) return 1.0 - span * exp(-(m - uToneKnee) / span);
    // 本帧没有超白内容时整段退化成常数：控制峰值落在 1.0，上面没有东西可分配。
    if (uTonePeak <= 1.0) return uToneWhite;
    float u = clamp((m - 1.0) / (uTonePeak - 1.0), 0.0, 1.0);
    return uToneTarget - (uToneTarget - uToneWhite) * pow(1.0 - u, uToneExponent);
}

/**
 * 对 RGB 施加**共同**增益（D69、D76）。
 *
 * 亮度尺度取 max(R, G, B)：带色高光不会因为某个通道权重低而逃过压缩，而三个通道乘同一个
 * 数，色相、饱和度和既有通道比例原样保留。不做顶部去饱和，也不逐通道硬钳。
 */
vec3 sdrToneMap(vec3 c) {
    float m = max(c.r, max(c.g, c.b));
    if (m <= 0.0) return c;
    return c * (sdrToneScalar(m) / m);
}

float roundedRectDistance(vec2 pointPx, vec2 originPx, vec2 sizePx, float radiusPx) {
    vec2 halfSize = sizePx * 0.5;
    vec2 centre = originPx + halfSize;
    float radius = min(radiusPx, min(halfSize.x, halfSize.y));
    vec2 q = abs(pointPx - centre) - (halfSize - vec2(radius));
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

/** D162 之前的三角哈希；只在蓝噪声资源不可用时作同格式后备（D162 第 5 条）。 */
float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
}

/**
 * 8-bit 码值域的蓝噪声阈值舍入（D162）。
 *
 * `floor(v*255 + threshold)` 的期望值等于 `v*255`，误差始终不足一个码值——这是以相邻码值
 * 为上下候选的**无偏**选择，而不是往编码值上叠一层噪声。R′、G′、B′ 共用同一个阈值，
 * 中性色因此仍是中性色，也不会为了消除亮度色带而制造彩色噪点（D162 第 3 条）。
 *
 * 图案钉在画布像素坐标上，不随帧旋转或重新随机化：逐帧噪声在视频里就是闪烁，还平白增加
 * 编码压力（D162 第 1 条）。
 */
vec3 blueNoiseQuantize(vec3 encoded, ivec2 pixel) {
    ivec2 p = pixel % NOISE_SIZE;
    float threshold = uNoiseEnabled
        ? (float(texelFetch(uNoise, p, 0).r) + 0.5) / NOISE_LEVELS
        : 0.5;
    // 阈值恒在 (0, 1) 内，因此精确的 0 与 1 仍落在真黑与真白码值上（D162 第 4 条）。
    return clamp(floor(encoded * 255.0 + threshold) / 255.0, 0.0, 1.0);
}

void main() {
    vec2 pointPx = gl_FragCoord.xy;

    // ---- 画框底色 ----
    vec3 color = srgbToLinear(uBackdropColor);

    // ---- 卡片投影：与卡片共用同一个距离场，代价只是多一次 smoothstep ----
    float shadowDistance = roundedRectDistance(
        pointPx + vec2(0.0, uShadowOffsetPx),
        uCardOriginPx,
        uCardSizePx,
        uCornerRadiusPx
    );
    float shadow = 1.0 - smoothstep(0.0, max(uShadowRadiusPx, 1.0), shadowDistance);
    color = mix(color, vec3(0.0), shadow * uShadowAlpha);

    // ---- 卡片本体 ----
    float cardDistance = roundedRectDistance(
        pointPx, uCardOriginPx, uCardSizePx, uCornerRadiusPx
    );
    float cardCoverage = 1.0 - smoothstep(-0.75, 0.75, cardDistance);
    if (cardCoverage > 0.0) {
        vec2 cardUv = (pointPx - uCardOriginPx) / uCardSizePx;
        vec3 scene = texture(uScene, clamp(cardUv, vec2(0.0), vec2(1.0))).rgb;
        vec3 sceneLinear = uSceneLinear
            ? clamp(scene, vec3(0.0), vec3(uHdrHeadroom))
            : srgbToLinear(scene);
        // 高亮压缩只压 FableSol 自己生成的内容，且必须在时钟合成之前（D73）。
        if (uToneMap) sceneLinear = sdrToneMap(sceneLinear);

        // 时钟：纹理按屏幕坐标（y 向下）渲染，取样时翻转 v。
        vec2 clockUv = (pointPx - uClockRectPx.xy) / uClockRectPx.zw;
        if (clockUv.x >= 0.0 && clockUv.x <= 1.0 && clockUv.y >= 0.0 && clockUv.y <= 1.0) {
            vec4 ink = texture(uClock, vec2(clockUv.x, 1.0 - clockUv.y));
            float inkAlpha = ink.a * uClockAlpha;
            if (inkAlpha > 0.0) {
                // Bitmap 是直通 alpha 的 sRGB；先解到线性再按 alpha 混。
                vec3 inkLinear = srgbToLinear(ink.a > 0.0 ? ink.rgb / ink.a : ink.rgb);
                sceneLinear = mix(sceneLinear, inkLinear, inkAlpha);
            }
        }
        color = mix(color, sceneLinear, cardCoverage);
    }

    // ---- 发丝描边：轮廓落在卡片边界外侧半个 uRimWidthPx 上 ----
    if (uRimAlpha > 0.0 && uRimWidthPx > 0.0) {
        float rim = 1.0 - smoothstep(0.0, uRimWidthPx, abs(cardDistance));
        color = mix(color, srgbToLinear(uRimColor), rim * uRimAlpha);
    }

    // ---- 传递函数 ----
    vec3 encoded;
    if (uTransfer == 3) {
        // 全片亮度预分析（D86）：只做 Rec.709 → BT.2020 的线性转换，**不套 OETF、不钳到
        // 1.0**。写进来的是漫反射白归一化的线性值，最高可到 HDR 强度；因此这一档要求
        // FP16 中间面，归一化定点面会把高光一律截成 1.0。
        encoded = max(bt709ToBt2020(color), vec3(0.0));
    } else if (uTransfer == 2) {
        vec3 wide = max(bt709ToBt2020(color), vec3(0.0)) * uSdrWhiteNits;
        encoded = vec3(
            pqInverseEotfChannel(wide.r),
            pqInverseEotfChannel(wide.g),
            pqInverseEotfChannel(wide.b)
        );
    } else if (uTransfer == 1) {
        encoded = hlgEncode(color);
    } else {
        encoded = linearToBt709(color);
    }

    if (uDither) {
        encoded = uNoiseEnabled
            ? blueNoiseQuantize(encoded, ivec2(pointPx))
            : clamp(encoded + triangularDither(pointPx), 0.0, 1.0);
    }
    fragColor = vec4(encoded, 1.0);
    fragLinear = vec4(max(bt709ToBt2020(color), vec3(0.0)), 1.0);
}
