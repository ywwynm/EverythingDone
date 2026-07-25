#version 300 es
precision highp float;

// 逐星针芒精灵（D213）：每颗星一次 draw、携带自己的针表（朝向偏移/转速/
// 参差/长度逐星不同——不同视场方向的光穿过泪膜/晶状体的不同区域，星芒
// 图案本就各不相同）。解析求值：along/perp 分解 + 1/(1+(r/knee)²) 剖面 ×
// 长度截止 × 长度-强度耦合（D212）× 垂向高斯细线；λ 径向色散作用于
// along（针尖偏暖）。顶点着色复用 glare_star.vert。
in vec2 vDeltaPx;
in float vAmplitude;
in vec3 vTint;
uniform float uStrength;
uniform int uNeedleCount;
uniform float uNeedleLengthPx;
uniform float uNeedleKneePx;
uniform float uNeedlePerpSigmaPx;
uniform vec4 uNeedles[16];
out vec4 fragColor;

const vec3 CHROMA_SCALE = vec3(1.11, 1.0, 0.85);

void main() {
    vec3 acc = vec3(0.0);
    for (int k = 0; k < uNeedleCount; ++k) {
        vec4 line = uNeedles[k];
        float along = dot(vDeltaPx, line.xy);
        float perp = vDeltaPx.x * line.y - vDeltaPx.y * line.x;
        float factor = along >= 0.0 ? line.z : line.w;
        vec3 r = vec3(abs(along)) / CHROMA_SCALE;
        vec3 knee = r / max(uNeedleKneePx, 0.5);
        vec3 profile = 1.0 / (1.0 + knee * knee);
        float cutEnd = max(factor, 0.02) * uNeedleLengthPx;
        vec3 cut = 1.0 - smoothstep(vec3(0.75 * cutEnd), vec3(cutEnd), r);
        float thin = exp(-0.5 * perp * perp
                         / max(uNeedlePerpSigmaPx * uNeedlePerpSigmaPx, 1e-4));
        // 长度-强度耦合（D212）：长芒亮而长、短芒暗而短。
        acc += profile * cut * factor * thin;
    }
    fragColor = vec4(vAmplitude * uStrength * acc * vTint, 1.0);
}
