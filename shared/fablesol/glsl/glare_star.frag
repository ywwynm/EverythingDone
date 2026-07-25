#version 300 es
precision highp float;

// 星点光源：小高斯足迹（对应银丝亮芯 1~2px 的源尺寸）。
in vec2 vDeltaPx;
in float vAmplitude;
in vec3 vTint;
uniform float uDotSigmaPx;
out vec4 fragColor;

void main() {
    float r2 = dot(vDeltaPx, vDeltaPx);
    float falloff = exp(-0.5 * r2 / max(uDotSigmaPx * uDotSigmaPx, 1e-4));
    fragColor = vec4(vAmplitude * falloff * vTint, 1.0);
}
