#version 300 es
precision highp float;

layout(location = 0) in vec2 aPositionPx;
layout(location = 1) in vec2 aSlope;
layout(location = 2) in float aDepth01;
layout(location = 3) in float aCrestPinch;
layout(location = 4) in vec2 aSheenSlope;

uniform vec2 uViewportPx;
uniform float uRotationRad;
uniform int uStartLayer;
uniform vec3 uLayerStart[9];
uniform vec3 uLayerStop1[9];
uniform vec3 uLayerStop2[9];
uniform vec3 uLayerEnd[9];
uniform vec3 uLayerDeepStart[9];
uniform vec3 uLayerDeepStop1[9];
uniform vec3 uLayerDeepStop2[9];
uniform vec3 uLayerDeepEnd[9];
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
uniform float uMacroShadowLumaCap;
uniform float uMacroLightWeights[9];
uniform float uMacroShadowWeights[9];
uniform float uMicroNormalWeights[9];
uniform float uSdrSssWeights[9];
uniform float uHdrSheenPeaks[9];
uniform float uHdrTransmissionPeaks[9];
uniform bool uFrontFill;

out vec3 vColor;
out vec3 vSubsurfaceColor;
out vec2 vSurfacePositionPx;
out vec2 vSurfaceSlope;
out float vDepth01;
out float vCrestPinch;
out vec2 vSheenSlope;
out float vMicroNormalWeight;
out float vSdrSssWeight;
out float vHdrSheenPeak;
out float vHdrTransmissionPeak;
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

float linearToSrgbChannel(float c) {
    c = clamp(c, 0.0, 1.0);
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

vec3 linearToSrgb(vec3 c) {
    return vec3(
        linearToSrgbChannel(c.r),
        linearToSrgbChannel(c.g),
        linearToSrgbChannel(c.b)
    );
}

vec3 environmentAt(float globalY) {
    float q = clamp(globalY / max(uViewportPx.y, 1.0), 0.0, 1.0);
    return q <= 0.42
        ? mix(uEnvironmentTop, uEnvironmentHorizon, q / 0.42)
        : mix(uEnvironmentHorizon, uEnvironmentBottom, (q - 0.42) / 0.58);
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
    if (q <= 0.24) return mix(uLayerStart[layer], uLayerStop1[layer], q / 0.24);
    if (q <= 0.60) return mix(uLayerStop1[layer], uLayerStop2[layer], (q - 0.24) / 0.36);
    return mix(uLayerStop2[layer], uLayerEnd[layer], (q - 0.60) / 0.40);
}

vec3 deepGradient(int layer, float q) {
    if (q <= 0.24) return mix(uLayerDeepStart[layer], uLayerDeepStop1[layer], q / 0.24);
    if (q <= 0.60) return mix(uLayerDeepStop1[layer], uLayerDeepStop2[layer], (q - 0.24) / 0.36);
    return mix(uLayerDeepStop2[layer], uLayerDeepEnd[layer], (q - 0.60) / 0.40);
}

vec3 subsurfaceGradient(int layer, float q) {
    if (q <= 0.24) {
        return mix(uLayerSubsurfaceStart[layer], uLayerSubsurfaceStop1[layer], q / 0.24);
    }
    if (q <= 0.60) {
        return mix(uLayerSubsurfaceStop1[layer], uLayerSubsurfaceStop2[layer], (q - 0.24) / 0.36);
    }
    return mix(uLayerSubsurfaceStop2[layer], uLayerSubsurfaceEnd[layer], (q - 0.60) / 0.40);
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

vec3 relativeLongitudinalLight(vec3 base, vec3 deep, vec2 slope,
                               float depth01, float crestPinch) {
    // 正向坡面光保留 D87；背坡只朝身份色派生的 deepColor 移动，并按最终线性亮度损失封顶。
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
    vec3 referenceNormal = normalize(vec3(-slope.x, 1.0, 0.0));
    float relativeNdl = dot(normal, lightDirection())
        - dot(referenceNormal, lightDirection());
    float positiveNdl = max(relativeNdl, 0.0);
    float relativeLift = min(
        positiveNdl * LONGITUDINAL_LIGHT_RESPONSE,
        MAX_RELATIVE_LONGITUDINAL_LIFT
    ) * sampleLayerCurve(uMacroLightWeights, depth01);
    vec3 lit = base * (1.0 + relativeLift);

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
    float cap = clamp(uMacroShadowLumaCap, 0.0, 0.040);
    if (shadowMask <= 0.0 || cap <= 0.0) return lit;

    vec3 litLinear = srgbToLinear(clamp(lit, 0.0, 1.0));
    vec3 deepLinear = srgbToLinear(clamp(deep, 0.0, 1.0));
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    float litLuma = dot(litLinear, LUMA);
    float availableLoss = max(litLuma - dot(deepLinear, LUMA), 0.0);
    float requestedLoss = litLuma * cap * shadowMask;
    float deepMix = availableLoss > 1e-6
        ? clamp(requestedLoss / availableLoss, 0.0, 1.0)
        : 0.0;
    return linearToSrgb(mix(litLinear, deepLinear, deepMix));
}

void main() {
    float c = cos(-uRotationRad);
    float s = sin(-uRotationRad);
    vec2 rotated = vec2(
        c * aPositionPx.x - s * aPositionPx.y,
        s * aPositionPx.x + c * aPositionPx.y
    );
    vec2 screen = rotated + uViewportPx * 0.5;
    vec2 ndc = vec2(screen.x / uViewportPx.x * 2.0 - 1.0,
                    1.0 - screen.y / uViewportPx.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);

    vec3 color;
    vec3 materialSubsurface;
    if (uFrontFill) {
        float q = gradientT(aPositionPx, 0);
        materialSubsurface = subsurfaceGradient(0, q);
        color = layerGradient(0, q);
    } else {
        color = environmentAt(screen.y);
        vec3 deepColor = color;
        vec3 subsurfaceColor = color;
        for (int layer = 8; layer >= 0; --layer) {
            if (layer >= uStartLayer) {
                float q = gradientT(aPositionPx, layer);
                vec3 layerColor = layerGradient(layer, q);
                color = mix(color, layerColor, uLayerAlpha[layer]);
                deepColor = mix(deepColor, deepGradient(layer, q), uLayerAlpha[layer]);
                subsurfaceColor = mix(
                    subsurfaceColor,
                    subsurfaceGradient(layer, q),
                    uLayerAlpha[layer]
                );
            }
        }
        color = relativeLongitudinalLight(
            color,
            deepColor,
            aSlope,
            aDepth01,
            aCrestPinch
        );
        materialSubsurface = subsurfaceColor;
    }
    vColor = color;
    vSubsurfaceColor = materialSubsurface;
    vSurfacePositionPx = aPositionPx;
    vSurfaceSlope = aSlope;
    vDepth01 = aDepth01;
    vCrestPinch = aCrestPinch;
    vSheenSlope = aSheenSlope;
    // 网格行与九层曲线节点对齐；在顶点阶段采样后线性插值，与逐片元采样等价。
    vMicroNormalWeight = sampleLayerCurve(uMicroNormalWeights, aDepth01);
    vSdrSssWeight = sampleLayerCurve(uSdrSssWeights, aDepth01);
    vHdrSheenPeak = sampleLayerCurve(uHdrSheenPeaks, aDepth01);
    vHdrTransmissionPeak = sampleLayerCurve(uHdrTransmissionPeaks, aDepth01);
    vFrontFill = uFrontFill ? 1 : 0;
}
