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
    float glintCoreCoverage = 0.0;
    if (vOpticalMode > 9.5) {
        // D129 界面肩：轮廓和带外均归零，带内半正弦峰值固定为 0.66。
        coverage = 0.66 * sin(3.14159265 * clamp(vLocalUv.y, 0.0, 1.0));
    } else if (vOpticalMode > 8.5) {
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
        // 水面起始处为实体短光带，向水内单调衰减；不生成椭圆空心环。
        float along = 1.0 - smoothstep(0.56, 1.0, abs(vLocalUv.x));
        float inward = 1.0 - smoothstep(0.08, 0.72, vLocalUv.y);
        glintCoreCoverage = along * inward;
        coverage = glintCoreCoverage;
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

    vec3 primaryColor = uSceneLinear
        ? srgbToLinear(vColor.rgb)
        : vColor.rgb;
    vec3 edgeColor = uSceneLinear
        ? srgbToLinear(vEdgeColor)
        : vEdgeColor;
    // edgeColor 只在核心覆盖内部形成很弱的中心层次，不再延伸成外晕。
    vec3 color = vOpticalMode > 2.5 && vOpticalMode < 3.5
        ? mix(edgeColor, primaryColor, clamp(glintCoreCoverage, 0.0, 1.0))
        : primaryColor;
    vec3 hdrExcess = vec3(0.0);
    if (uSceneLinear && uHdrGain > 0.0001 && uHdrHeadroom > 1.001) {
        float hdrMask = 0.0;
        float targetPeak = 1.0;
        float neutralMix = 0.0;
        if (vOpticalMode > 2.5 && vOpticalMode < 3.5) {
            hdrMask = pow(clamp(glintCoreCoverage, 0.0, 1.0), 1.65) *
                clamp(vHdrEligibility, 0.0, 1.0);
            targetPeak = min(uHdrCorePeak, uHdrHeadroom);
            neutralMix = 0.72;
        } else if (vOpticalMode > 3.5 && vOpticalMode < 4.5) {
            hdrMask = smoothstep(0.28, 0.82, vHdrEligibility) *
                pow(clamp(coverage, 0.0, 1.0), 1.45);
            targetPeak = min(uHdrCrestPeak, uHdrHeadroom);
            neutralMix = 0.34;
        } else if (vOpticalMode > 7.5 && vOpticalMode < 8.5) {
            hdrMask = smoothstep(0.24, 0.78, vHdrEligibility) *
                pow(clamp(coverage, 0.0, 1.0), 1.40);
            targetPeak = min(uHdrTransmissionPeak, uHdrHeadroom);
            neutralMix = 0.18;
        }
        if (targetPeak > 1.001 && hdrMask > 0.0001) {
            vec3 tint = hdrTint(color, neutralMix);
            // 局部实体的 alpha 本身就是覆盖/能量预算；不再反向除 alpha 制造巨大白色源。
            float excess = max(targetPeak - 1.0, 0.0) *
                clamp(uHdrGain * hdrMask, 0.0, 1.0);
            hdrExcess = tint * excess;
        }
    }
    float opticalAlpha = clamp(vColor.a * coverage, 0.0, 1.0);
    // SDR 使用预乘 alpha；HDR excess 独立于覆盖 alpha，峰值与出现面积分离。
    fragColor = vec4(color * opticalAlpha + hdrExcess, opticalAlpha);
}
