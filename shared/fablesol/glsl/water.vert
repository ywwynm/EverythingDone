#version 300 es
precision highp float;

layout(location = 0) in vec2 aPositionPx;
layout(location = 1) in vec2 aSlope;
layout(location = 2) in float aDepth01;
layout(location = 3) in float aCrestPinch;
layout(location = 4) in vec2 aSheenSlope;
// |∇depth01|（未旋转水面空间，1/px）：CPU 侧按雅可比解析求出的"相邻行沿轮廓
// 法向垂直间距的倒数"。片元用它把 depth 差换算成到本层轮廓的垂直像素距离；
// dFdx/dFdy 逐三角形恒定，会把窄银丝按网格四边形切成错位小段（D220）。
layout(location = 5) in float aRimDistancePx;

uniform vec2 uViewportPx;
uniform float uRotationRad;
uniform float uRasterScale;
uniform highp int uStartLayer;
uniform vec3 uLayerStart[9];
uniform vec3 uLayerStop1[9];
uniform vec3 uLayerStop2[9];
uniform vec3 uLayerEnd[9];
uniform vec3 uLayerSubsurfaceStart[9];
uniform vec3 uLayerSubsurfaceStop1[9];
uniform vec3 uLayerSubsurfaceStop2[9];
uniform vec3 uLayerSubsurfaceEnd[9];
uniform float uLayerAlpha[9];
uniform vec2 uGradientOrigin[9];
uniform vec2 uGradientDirection[9];
uniform float uGradientDenominator[9];
uniform vec3 uEnvironmentTop;
uniform vec3 uEnvironmentHorizon;
uniform vec3 uEnvironmentBottom;
uniform float uViewElevationRad;
uniform float uLightAzimuthRad;
uniform float uMacroLightWeights[9];
uniform float uMacroShadowWeights[9];
uniform float uHdrTransmissionPeaks[9];
uniform bool uSceneLinear;
uniform bool uFrontFill;
// 厚度透光（2026-07-16 质感提升批）：逐锚层的轮廓均值 y（物理 px，未旋转），
// 与归一化范围。两者未上传（Android 未接线）时 vThickness01 恒为 0 = 关闭。
uniform float uLayerMeanYPx[9];
uniform float uThicknessRangePx;
// D154：厚度透光独立权重表（4~8 层上提一档）。未上传时片元回退 SDR_SSS 表。
uniform float uThicknessGlowWeights[9];
// 波峰银边（2026-07-16 夜）：逐层存在度，近层重、远层近无。
// 未上传（Android 未接线）时全 0，片元侧银边恒为 0 = 关闭。
uniform float uCrestRimWeights[9];

out vec3 vColor;
out vec3 vSubsurfaceColor;
out vec3 vMaterialColor;
out vec3 vBehindColor;
out float vMaterialOpacity;
out vec2 vScreenUv;
out vec2 vSurfacePositionPx;
out vec2 vSurfaceSlope;
out float vDepth01;
out vec2 vSheenSlope;
out float vHdrTransmissionPeak;
out float vDirectLight;
out float vThickness01;
out float vThicknessSurface;
out float vThicknessGlowWeight;
out float vCrestRimWeight;
out float vRimDistancePx;
flat out int vFrontFill;

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

vec3 materialInput(vec3 encodedColor) {
    return uSceneLinear ? srgbToLinear(encodedColor) : encodedColor;
}

vec3 environmentAt(float globalY) {
    float q = clamp(globalY / max(uViewportPx.y, 1.0), 0.0, 1.0);
    vec3 top = materialInput(uEnvironmentTop);
    vec3 horizon = materialInput(uEnvironmentHorizon);
    vec3 bottom = materialInput(uEnvironmentBottom);
    return q <= 0.42
        ? mix(top, horizon, q / 0.42)
        : mix(horizon, bottom, (q - 0.42) / 0.58);
}

float gradientT(vec2 localPosition, int layer) {
    vec2 delta = localPosition - uGradientOrigin[layer];
    return clamp(
        dot(delta, uGradientDirection[layer]) / max(uGradientDenominator[layer], 1e-6),
        0.0,
        1.0
    );
}

vec3 layerGradient(int layer, float q) {
    vec3 start = materialInput(uLayerStart[layer]);
    vec3 stop1 = materialInput(uLayerStop1[layer]);
    vec3 stop2 = materialInput(uLayerStop2[layer]);
    vec3 end = materialInput(uLayerEnd[layer]);
    if (q <= 0.24) return mix(start, stop1, q / 0.24);
    if (q <= 0.60) return mix(stop1, stop2, (q - 0.24) / 0.36);
    return mix(stop2, end, (q - 0.60) / 0.40);
}

vec3 subsurfaceGradient(int layer, float q) {
    vec3 start = materialInput(uLayerSubsurfaceStart[layer]);
    vec3 stop1 = materialInput(uLayerSubsurfaceStop1[layer]);
    vec3 stop2 = materialInput(uLayerSubsurfaceStop2[layer]);
    vec3 end = materialInput(uLayerSubsurfaceEnd[layer]);
    if (q <= 0.24) {
        return mix(start, stop1, q / 0.24);
    }
    if (q <= 0.60) {
        return mix(stop1, stop2, (q - 0.24) / 0.36);
    }
    return mix(stop2, end, (q - 0.60) / 0.40);
}

vec3 lightDirection() {
    float lightElevation = radians(50.0);
    return normalize(vec3(
        sin(uLightAzimuthRad) * cos(lightElevation),
        sin(lightElevation),
        -cos(uLightAzimuthRad) * cos(lightElevation)
    ));
}

const float MAX_RELATIVE_LONGITUDINAL_LIFT = 0.015;
const float LONGITUDINAL_LIGHT_RESPONSE = 0.12;
const float DIRECT_LIGHT_BASE_RESPONSE = 0.004;
const float MAX_DIRECT_LIGHT_LOBE = 0.019;
const float MACRO_SHADOW_NDL_START = 0.080;
const float MACRO_SHADOW_NDL_FULL = 0.180;
const float MACRO_SHADOW_CREST_START = 0.005;
const float MACRO_SHADOW_CREST_FULL = 0.080;
const float MACRO_SHADOW_LOCAL_FLOOR = 0.300;

float sampleLayerCurve(float values[9], float depth01) {
    float position = clamp(depth01, 0.0, 1.0) * 8.0;
    int lower = int(floor(position));
    int upper = min(lower + 1, 8);
    return mix(values[lower], values[upper], fract(position));
}

float relativeLongitudinalDirect(vec2 slope, float depth01, float crestPinch) {
    // 主体、环境反射和体积光不参与遮挡乘暗；背坡遮挡只削减这个微弱直射分瓣。
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
    vec3 referenceNormal = normalize(vec3(-slope.x, 1.0, 0.0));
    vec3 lightDir = lightDirection();
    float ndl = max(dot(normal, lightDir), 0.0);
    float relativeNdl = dot(normal, lightDir) - dot(referenceNormal, lightDir);
    float positiveNdl = max(relativeNdl, 0.0);
    float relativeLift = min(
        positiveNdl * LONGITUDINAL_LIGHT_RESPONSE,
        MAX_RELATIVE_LONGITUDINAL_LIFT
    );
    // 以实际 N·L 提供一个很弱但始终真实存在的同色直射分瓣；背坡遮挡因此有可削减的能量，
    // 又不会低于未加直射的主体色。
    float directLobe = min(
        ndl * DIRECT_LIGHT_BASE_RESPONSE + relativeLift,
        MAX_DIRECT_LIGHT_LOBE
    ) * sampleLayerCurve(uMacroLightWeights, depth01);
    float backSlope = smoothstep(
        MACRO_SHADOW_NDL_START,
        MACRO_SHADOW_NDL_FULL,
        max(-relativeNdl, 0.0)
    );
    float depthGate = sampleLayerCurve(uMacroShadowWeights, depth01);
    float crestGate = mix(
        MACRO_SHADOW_LOCAL_FLOOR,
        1.0,
        smoothstep(
            MACRO_SHADOW_CREST_START,
            MACRO_SHADOW_CREST_FULL,
            crestPinch
        )
    );
    float shadowMask = backSlope * depthGate * crestGate;
    return directLobe * (1.0 - clamp(shadowMask, 0.0, 1.0));
}

void main() {
    float c = cos(-uRotationRad);
    float s = sin(-uRotationRad);
    vec2 rotated = vec2(
        c * aPositionPx.x - s * aPositionPx.y,
        s * aPositionPx.x + c * aPositionPx.y
    ) * uRasterScale;
    vec2 screen = rotated + uViewportPx * 0.5;
    vec2 ndc = vec2(screen.x / uViewportPx.x * 2.0 - 1.0,
                    1.0 - screen.y / uViewportPx.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);

    vec3 color;
    vec3 materialSubsurface;
    vec3 materialColor;
    vec3 behindColor;
    float materialOpacity;
    float directLight = 0.0;
    behindColor = environmentAt(screen.y);
    vec3 behindSubsurface = behindColor;
    for (int layer = 8; layer >= 0; --layer) {
        if (layer > uStartLayer) {
            float q = gradientT(aPositionPx, layer);
            vec3 layerColor = layerGradient(layer, q);
            behindColor = mix(
                behindColor,
                layerColor,
                uLayerAlpha[layer]
            );
            behindSubsurface = mix(
                behindSubsurface,
                subsurfaceGradient(layer, q),
                uLayerAlpha[layer]
            );
        }
    }
    if (uFrontFill) {
        float q = gradientT(aPositionPx, 0);
        materialSubsurface = subsurfaceGradient(0, q);
        materialColor = layerGradient(0, q);
        materialOpacity = uLayerAlpha[0];
        color = materialColor;
    } else {
        float materialQ = gradientT(aPositionPx, uStartLayer);
        materialColor = layerGradient(uStartLayer, materialQ);
        materialOpacity = uLayerAlpha[uStartLayer];
        color = mix(behindColor, materialColor, materialOpacity);
        materialSubsurface = mix(
            behindSubsurface,
            subsurfaceGradient(uStartLayer, materialQ),
            materialOpacity
        );
        directLight = relativeLongitudinalDirect(aSlope, aDepth01, aCrestPinch);
    }
    vColor = color;
    vSubsurfaceColor = materialSubsurface;
    vMaterialColor = materialColor;
    vBehindColor = behindColor;
    vMaterialOpacity = materialOpacity;
    vScreenUv = vec2(
        screen.x / uViewportPx.x,
        1.0 - screen.y / uViewportPx.y
    );
    vSurfacePositionPx = aPositionPx;
    vSurfaceSlope = aSlope;
    vDepth01 = aDepth01;
    vSheenSlope = aSheenSlope;
    // 网格行与九层曲线节点对齐；在顶点阶段采样后线性插值，与逐片元采样等价。
    vHdrTransmissionPeak = sampleLayerCurve(uHdrTransmissionPeaks, aDepth01);
    vDirectLight = directLight;
    // 厚度代理：高出本层轮廓均值的高度（y 向下为正，波峰 y 更小）。
    // 整体开关由片元侧 uThicknessGlowStrength=0 承担（Android 未接线时安全）。
    // D154 近层覆盖偏置：层 0=+0.45 → 层 2≈0，让最近条带整体进入透光渐变。
    // D155（2026-07-16 用户裁决，适当放宽 D6）：front fill 不再强制归零——
    // 前景水体在水线下同一厚度范围内衰减到 0，第 0 层透光由此可见，
    // 大面积主体仍保持身份纯色。fill 的 aDepth01∈[-1,0]，偏置系数 clamp 在层 0 档。
    float layerMeanY = sampleLayerCurve(uLayerMeanYPx, aDepth01);
    float nearBias = 0.45 * clamp(1.0 - aDepth01 * 4.0, 0.0, 1.0);
    // 顶点端不 clamp：fill 网格只有上下两排顶点，先 clamp 再插值会把
    // "范围内衰减"线性拉伸到整块填充高度；原始代理对 y 严格线性，
    // 插值即逐像素精确，clamp 统一由片元的 thin/掩码承担。
    vThickness01 =
        (layerMeanY - aPositionPx.y) / max(uThicknessRangePx, 1.0) + nearBias;
    // D155：水面处的厚度代理（逐列常量）。波浪网格顶点本身在水面上；
    // front fill 的本列水面 y 由网格侧写入闲置的 aSlope.y（fill 宏观坡度恒 0）。
    // 片元用 (vThicknessSurface − vThickness01) 还原"水面下深度"做
    // Beer–Lambert 衰减；波浪侧两值恒等 → 衰减为 0，行为不变。
    float surfaceYPx = uFrontFill ? aSlope.y : aPositionPx.y;
    vThicknessSurface =
        (layerMeanY - surfaceYPx) / max(uThicknessRangePx, 1.0) + nearBias;
    vThicknessGlowWeight = sampleLayerCurve(uThicknessGlowWeights, aDepth01);
    vCrestRimWeight = sampleLayerCurve(uCrestRimWeights, aDepth01);
    // 到本层上轮廓的法向距离（D221）。位置变换是 rot(−θ)·pos·rasterScale，
    // 等距旋转不改变长度，只需按同一缩放折算到屏幕像素。
    // 锚行同属相邻两个层带、只能存一个值：CPU 存的是"到上方那个锚行的距离"，
    // 因此本次 draw 把它当上轮廓时必须归零，当下界时存储值正是本带带高。
    float anchorDepth = float(uStartLayer) / 8.0;
    bool topContour = !uFrontFill &&
        abs(aDepth01 - anchorDepth) < 0.5 / float(96);
    vRimDistancePx = topContour ? 0.0 : aRimDistancePx * uRasterScale;
    vFrontFill = uFrontFill ? 1 : 0;
}
