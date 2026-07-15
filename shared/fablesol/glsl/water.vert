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
uniform float uMicroNormalWeights[9];
uniform float uSdrSssWeights[9];
uniform float uHdrTransmissionPeaks[9];
uniform bool uSceneLinear;
uniform bool uFrontFill;

out vec3 vColor;
out vec3 vSubsurfaceColor;
out vec3 vMaterialColor;
out vec3 vBehindColor;
out float vMaterialOpacity;
out vec2 vScreenUv;
out vec2 vSurfacePositionPx;
out vec2 vSurfaceSlope;
out float vDepth01;
out float vCrestPinch;
out vec2 vSheenSlope;
out float vMicroNormalWeight;
out float vSdrSssWeight;
out float vHdrTransmissionPeak;
out float vDirectLight;
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
    );
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
    if (uFrontFill) {
        float q = gradientT(aPositionPx, 0);
        materialSubsurface = subsurfaceGradient(0, q);
        materialColor = layerGradient(0, q);
        behindColor = materialColor;
        materialOpacity = uLayerAlpha[0];
        color = materialColor;
    } else {
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
    vCrestPinch = aCrestPinch;
    vSheenSlope = aSheenSlope;
    // 网格行与九层曲线节点对齐；在顶点阶段采样后线性插值，与逐片元采样等价。
    vMicroNormalWeight = sampleLayerCurve(uMicroNormalWeights, aDepth01);
    vSdrSssWeight = sampleLayerCurve(uSdrSssWeights, aDepth01);
    vHdrTransmissionPeak = sampleLayerCurve(uHdrTransmissionPeaks, aDepth01);
    vDirectLight = directLight;
    vFrontFill = uFrontFill ? 1 : 0;
}
