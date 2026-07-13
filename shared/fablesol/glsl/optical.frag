#version 300 es
precision highp float;

in vec2 vLocalUv;
in vec4 vColor;
in float vOpticalMode;
in vec3 vEdgeColor;
in float vHdrEligibility;

uniform bool uSceneLinear;
uniform float uHdrGain;
uniform float uHdrHeadroom;
uniform float uHdrCorePeak;
uniform float uHdrCrestPeak;
uniform float uHdrTransmissionPeak;
out vec4 fragColor;

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

vec3 hdrTint(vec3 linearColor, float neutralMix) {
    float maximum = max(max(linearColor.r, linearColor.g), linearColor.b);
    vec3 identityTint = linearColor / max(maximum, 0.001);
    return mix(identityTint, vec3(1.0), neutralMix);
}

void main() {
    float coverage;
    if (vOpticalMode > 8.5) {
        coverage = sin(3.14159265 * clamp(vLocalUv.y, 0.0, 1.0));
    } else if (vOpticalMode > 7.5) {
        coverage = sin(3.14159265 * clamp(vLocalUv.y, 0.0, 1.0));
    } else if (vOpticalMode > 6.5) {
        // 解析光晕只承担 SDR 肩部，不进入超白预算。
        vec2 haloPoint = vec2(vLocalUv.x * 0.76, vLocalUv.y * 1.12);
        float radiusSquared = dot(haloPoint, haloPoint);
        float falloff = exp2(-6.5 * radiusSquared);
        float boundaryTaper = 1.0 - smoothstep(0.72, 1.0, vLocalUv.y);
        coverage = falloff * boundaryTaper;
    } else if (vOpticalMode > 5.5) {
        coverage = 1.0;
    } else if (vOpticalMode > 4.5) {
        coverage = sin(3.14159265 * clamp(vLocalUv.y, 0.0, 1.0));
    } else if (vOpticalMode > 3.5) {
        coverage = sin(3.14159265 * clamp(vLocalUv.y, 0.0, 1.0));
    } else if (vOpticalMode > 2.5) {
        float along = 1.0 - smoothstep(0.36, 1.0, abs(vLocalUv.x));
        float inward = smoothstep(0.0, 0.16, vLocalUv.y) *
                       (1.0 - smoothstep(0.38, 1.0, vLocalUv.y));
        coverage = along * inward;
    } else if (vOpticalMode > 1.5) {
        float along = 1.0 - smoothstep(0.68, 1.0, abs(vLocalUv.x));
        float inward = smoothstep(0.0, 0.14, vLocalUv.y) *
                       (1.0 - smoothstep(0.70, 1.0, vLocalUv.y));
        coverage = along * inward;
    } else {
        float radius = length(vLocalUv);
        coverage = 1.0 - smoothstep(0.42, 1.0, radius);
        coverage *= mix(1.0, smoothstep(0.0, 0.14, vLocalUv.y),
                        clamp(vOpticalMode, 0.0, 1.0));
    }
    if (coverage <= 0.001) discard;

    float halo = clamp(max(abs(vLocalUv.x), vLocalUv.y), 0.0, 1.0);
    vec3 encodedColor = vOpticalMode > 2.5 && vOpticalMode < 3.5
        ? mix(vColor.rgb, vEdgeColor, smoothstep(0.0, 0.72, halo))
        : vColor.rgb;

    vec3 color = uSceneLinear ? srgbToLinear(encodedColor) : encodedColor;
    if (uSceneLinear && uHdrGain > 0.0001 && uHdrHeadroom > 1.001) {
        float hdrMask = 0.0;
        float targetPeak = 1.0;
        float neutralMix = 0.0;
        if (vOpticalMode > 2.5 && vOpticalMode < 3.5) {
            hdrMask = pow(clamp(coverage, 0.0, 1.0), 1.55);
            targetPeak = min(uHdrCorePeak, uHdrHeadroom);
            neutralMix = 0.90;
        } else if (vOpticalMode > 3.5 && vOpticalMode < 4.5) {
            hdrMask = smoothstep(0.28, 0.82, vHdrEligibility) *
                pow(clamp(coverage, 0.0, 1.0), 1.35);
            targetPeak = min(uHdrCrestPeak, uHdrHeadroom);
            neutralMix = 0.45;
        } else if (vOpticalMode > 7.5 && vOpticalMode < 8.5) {
            hdrMask = smoothstep(0.24, 0.78, vHdrEligibility) *
                pow(clamp(coverage, 0.0, 1.0), 1.40);
            targetPeak = min(uHdrTransmissionPeak, uHdrHeadroom);
            neutralMix = 0.25;
        }
        if (targetPeak > 1.001 && hdrMask > 0.0001) {
            vec3 tint = hdrTint(color, neutralMix);
            vec3 targetColor = max(color, tint * targetPeak);
            // 当前 pass 使用 SRC_ALPHA 混合；预补偿 excess，才能让窄、低 alpha 的浪峰带
            // 真正越过 reference white；暗色 Thing 的补偿只使用 targetPeak 之上的剩余 headroom。
            float opticalAlpha = max(vColor.a * coverage, 0.02);
            float sourcePeak = max(max(color.r, color.g), color.b);
            float darkCompensation = min(
                max(uHdrHeadroom - targetPeak, 0.0),
                clamp(1.0 - sourcePeak, 0.0, 0.45)
            );
            vec3 excess = max(targetColor - vec3(1.0), vec3(0.0)) +
                tint * darkCompensation;
            vec3 sourceWithExcess = color + excess / opticalAlpha;
            color = mix(
                color,
                sourceWithExcess,
                clamp(uHdrGain * hdrMask, 0.0, 1.0)
            );
        }
    }
    fragColor = vec4(color, vColor.a * coverage);
}
