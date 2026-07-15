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

// 逐 UV 计算某一子样本的覆盖与核心占比；不使用任何屏幕导数，供逐子样本调用。
void opticalCoverage(vec2 uv, out float coverage, out float glintCoreCoverage) {
    glintCoreCoverage = 0.0;
    if (vOpticalMode > 9.5) {
        // D129 界面肩：轮廓和带外均归零，带内半正弦峰值固定为 0.66。
        coverage = 0.66 * sin(3.14159265 * clamp(uv.y, 0.0, 1.0));
    } else if (vOpticalMode > 8.5) {
        coverage = sin(3.14159265 * clamp(uv.y, 0.0, 1.0));
    } else if (vOpticalMode > 7.5) {
        coverage = sin(3.14159265 * clamp(uv.y, 0.0, 1.0));
    } else if (vOpticalMode > 6.5) {
        // 解析光晕只承担 SDR 肩部，不进入超白预算。
        vec2 haloPoint = vec2(uv.x * 0.76, uv.y * 1.12);
        float radiusSquared = dot(haloPoint, haloPoint);
        float falloff = exp2(-6.5 * radiusSquared);
        float boundaryTaper = 1.0 - smoothstep(0.72, 1.0, uv.y);
        coverage = falloff * boundaryTaper;
    } else if (vOpticalMode > 5.5) {
        coverage = 1.0;
    } else if (vOpticalMode > 4.5) {
        coverage = sin(3.14159265 * clamp(uv.y, 0.0, 1.0));
    } else if (vOpticalMode > 3.5) {
        coverage = sin(3.14159265 * clamp(uv.y, 0.0, 1.0));
    } else if (vOpticalMode > 2.5) {
        // 水面起始处为实体短光带，向水内单调衰减；不生成椭圆空心环。
        float along = 1.0 - smoothstep(0.56, 1.0, abs(uv.x));
        float inward = 1.0 - smoothstep(0.08, 0.72, uv.y);
        glintCoreCoverage = along * inward;
        coverage = glintCoreCoverage;
    } else if (vOpticalMode > 1.5) {
        float along = 1.0 - smoothstep(0.68, 1.0, abs(uv.x));
        float inward = smoothstep(0.0, 0.14, uv.y) *
                       (1.0 - smoothstep(0.70, 1.0, uv.y));
        coverage = along * inward;
    } else {
        float radius = length(uv);
        coverage = 1.0 - smoothstep(0.42, 1.0, radius);
        coverage *= mix(1.0, smoothstep(0.0, 0.14, uv.y),
                        clamp(vOpticalMode, 0.0, 1.0));
    }
}

// 计算某 UV 子样本的最终预乘线性输出（rgb 预乘 alpha + 独立 HDR excess，a=覆盖 alpha）。
vec4 shadeOpticalSample(vec2 uv) {
    float coverage;
    float glintCoreCoverage;
    opticalCoverage(uv, coverage, glintCoreCoverage);
    if (coverage <= 0.0) return vec4(0.0);

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
    return vec4(color * opticalAlpha + hdrExcess, opticalAlpha);
}

void main() {
    // 光学实体的形状与 HDR excess 在片元里逐像素算一次，MSAA 只多采样几何覆盖、
    // 无法抗其形状/超白 clip 边缘的锯齿。这里对光学 pass 单独做 4x 旋转网格超采样
    // （RGSS）：按 UV 的屏幕导数把四个子样本放在四个不同的 x 与 y 子位置再平均。glint
    // 多为近水平光带，RGSS 的纵向分辨率明显优于轴对齐 2x2。实体较大时导数很小、四点
    // 几乎重合，输出与逐像素一次着色一致，不改变既定 glint 尺寸、峰值与观感。
    vec2 dux = dFdx(vLocalUv);
    vec2 duy = dFdy(vLocalUv);
    const vec2 R0 = vec2( 1.0 / 8.0,  3.0 / 8.0);
    const vec2 R1 = vec2( 3.0 / 8.0, -1.0 / 8.0);
    const vec2 R2 = vec2(-1.0 / 8.0, -3.0 / 8.0);
    const vec2 R3 = vec2(-3.0 / 8.0,  1.0 / 8.0);
    vec4 accumulated =
        shadeOpticalSample(vLocalUv + R0.x * dux + R0.y * duy) +
        shadeOpticalSample(vLocalUv + R1.x * dux + R1.y * duy) +
        shadeOpticalSample(vLocalUv + R2.x * dux + R2.y * duy) +
        shadeOpticalSample(vLocalUv + R3.x * dux + R3.y * duy);
    accumulated *= 0.25;
    // 四个子样本都无覆盖且无 HDR excess 时才丢弃，保持既有 early-out。
    if (accumulated.a <= 0.0009 &&
            max(max(accumulated.r, accumulated.g), accumulated.b) <= 0.0001) {
        discard;
    }
    fragColor = accumulated;
}
