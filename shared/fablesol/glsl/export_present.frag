#version 300 es
precision highp float;

// FableSol 可视化视频导出的最终呈现。屏上的 present.frag 一行不动——这条是独立 program，
// 逐帧关键路径不为导出背任何分支（fablesol-video-export D4）。
//
// 一遍做完：画框底色 → 卡片投影 → 圆角卡片内的水体 → 时钟叠加 → 传递函数编码。
// 全程在线性光里合成，最后一步才套 OETF，因此时钟的 alpha 混合是物理正确的。

in vec2 vUv;

uniform sampler2D uScene;
uniform sampler2D uClock;

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
uniform int   uTransfer;         // 0 = BT.709 SDR，1 = BT.2020 HLG，2 = BT.2020 PQ
uniform bool  uDither;
uniform float uHlgDiffuseScene;  // SDR 参考白对应的 HLG 场景线性值（BT.2408 = 0.26497）
uniform float uHlgKnee;          // 该倍数以下完全线性，之上渐进压缩
uniform float uSdrWhiteNits;     // SDR 参考白的绝对亮度（PQ 用；BT.2408 = 203 尼特）

out vec4 fragColor;

const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;
const float PQ_MAX_NITS = 10000.0;

const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;

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

float hlgOetfChannel(float e) {
    e = clamp(e, 0.0, 1.0);
    return e <= 1.0 / 12.0 ? sqrt(3.0 * e) : HLG_A * log(12.0 * e - HLG_B) + HLG_C;
}

/**
 * 线性域软肩。HLG 在 SDR 参考白之上只有 1/uHlgDiffuseScene（约 3.77）倍余量，而用户
 * HDR 强度可达 9.6——硬钳会把最亮的镜面核心压成一片死白平顶。这里 knee 以下完全线性，
 * 之上以指数渐近到上限，让高光继续保有形状。逐通道压缩，高光因而自然趋白。
 */
float shoulder(float x, float ceiling) {
    if (x <= uHlgKnee || ceiling <= uHlgKnee) return min(x, ceiling);
    float span = ceiling - uHlgKnee;
    return uHlgKnee + span * (1.0 - exp(-(x - uHlgKnee) / span));
}

float roundedRectDistance(vec2 pointPx, vec2 originPx, vec2 sizePx, float radiusPx) {
    vec2 halfSize = sizePx * 0.5;
    vec2 centre = originPx + halfSize;
    float radius = min(radiusPx, min(halfSize.x, halfSize.y));
    vec2 q = abs(pointPx - centre) - (halfSize - vec2(radius));
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
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
    if (uTransfer == 2) {
        vec3 wide = max(bt709ToBt2020(color), vec3(0.0)) * uSdrWhiteNits;
        encoded = vec3(
            pqInverseEotfChannel(wide.r),
            pqInverseEotfChannel(wide.g),
            pqInverseEotfChannel(wide.b)
        );
    } else if (uTransfer == 1) {
        vec3 wide = max(bt709ToBt2020(color), vec3(0.0));
        float ceiling = 1.0 / max(uHlgDiffuseScene, 1e-4);
        wide = vec3(
            shoulder(wide.r, ceiling),
            shoulder(wide.g, ceiling),
            shoulder(wide.b, ceiling)
        );
        vec3 sceneLight = wide * uHlgDiffuseScene;
        encoded = vec3(
            hlgOetfChannel(sceneLight.r),
            hlgOetfChannel(sceneLight.g),
            hlgOetfChannel(sceneLight.b)
        );
    } else {
        encoded = linearToBt709(color);
    }

    if (uDither) {
        encoded = clamp(encoded + triangularDither(pointPx), 0.0, 1.0);
    }
    fragColor = vec4(encoded, 1.0);
}
