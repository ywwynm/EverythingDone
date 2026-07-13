#version 300 es
precision highp float;

layout(location = 0) in vec2 aPositionPx;
layout(location = 1) in vec2 aSlope;
layout(location = 2) in float aDepth01;
layout(location = 3) in float aCrestPinch;

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
uniform vec3 uHorizonColor;
uniform float uViewElevationRad;
uniform float uLightAzimuthRad;
uniform float uDepthScatteringStrength;
uniform bool uFrontFill;

out vec3 vColor;
out vec3 vSubsurfaceColor;
out vec2 vSurfacePositionPx;
out vec2 vSurfaceSlope;
out float vDepth01;
out float vCrestPinch;
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

vec3 depthScattering(vec3 base, vec3 deep, vec3 subsurface,
                     vec2 slope, float depth01, float crestPinch) {
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
    float sunFacing = smoothstep(0.15, 0.85, dot(normal, lightDirection()));
    float grazing = smoothstep(0.18, 0.92, depth01);
    float thinCrest = crestPinch * mix(0.65, 1.0, sunFacing);
    float subsurfaceMask = clamp(0.10 + 0.18 * grazing + 0.75 * thinCrest, 0.0, 1.0);
    return mix(base, mix(deep, subsurface, subsurfaceMask), uDepthScatteringStrength);
}

vec3 relativeLongitudinalLight(vec3 base, vec2 slope, float depth01) {
    if (abs(slope.y) <= 1e-12) return base;
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
    vec2 referenceNormal = normalize(vec2(-slope.x, 1.0));
    vec3 viewDir = normalize(vec3(0.0, sin(uViewElevationRad), -cos(uViewElevationRad)));
    vec3 lightDir = lightDirection();
    float fullNdl = clamp(dot(normal, lightDir), 0.0, 1.0);
    float fullNdv = clamp(dot(normal, viewDir), 0.001, 1.0);
    float refNdl = clamp(dot(referenceNormal, lightDir.xy), 0.0, 1.0);
    float refNdv = clamp(dot(referenceNormal, viewDir.xy), 0.001, 1.0);
    float f0 = 0.020373;
    float fullFresnel = f0 + (1.0 - f0) * pow(1.0 - fullNdv, 5.0);
    float refFresnel = f0 + (1.0 - f0) * pow(1.0 - refNdv, 5.0);
    vec3 linearBase = srgbToLinear(base);
    vec3 linearSky = srgbToLinear(uHorizonColor);
    vec3 fullLight = fullNdl * (1.0 - fullFresnel) * linearBase +
        fullFresnel * linearSky;
    vec3 referenceLight = refNdl * (1.0 - refFresnel) * linearBase +
        refFresnel * linearSky;
    vec3 candidate = linearToSrgb(linearBase + fullLight - referenceLight);
    float baseLuminance = dot(base, vec3(0.2126, 0.7152, 0.0722));
    float candidateLuminance = dot(candidate, vec3(0.2126, 0.7152, 0.0722));
    if (candidateLuminance >= baseLuminance) return candidate;
    float darkness = clamp(
        (baseLuminance - candidateLuminance) / max(baseLuminance, 1.0 / 255.0),
        0.0,
        1.0
    );
    float nearScale = 1.0 - clamp(depth01, 0.0, 1.0);
    float depthScale = max(nearScale * nearScale, 0.05);
    float blackMix = 0.14 * sqrt(darkness) * depthScale;
    return base * (1.0 - blackMix);
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
        color = depthScattering(
            layerGradient(0, q),
            deepGradient(0, q),
            materialSubsurface,
            aSlope,
            aDepth01,
            aCrestPinch
        );
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
        color = depthScattering(
            color,
            deepColor,
            subsurfaceColor,
            aSlope,
            aDepth01,
            aCrestPinch
        );
        color = relativeLongitudinalLight(color, aSlope, aDepth01);
        materialSubsurface = subsurfaceColor;
    }
    vColor = color;
    vSubsurfaceColor = materialSubsurface;
    vSurfacePositionPx = aPositionPx;
    vSurfaceSlope = aSlope;
    vDepth01 = aDepth01;
    vCrestPinch = aCrestPinch;
    vFrontFill = uFrontFill ? 1 : 0;
}
