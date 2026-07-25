#version 300 es
precision highp float;

// 分离高斯（近核弥散 σ1.2dp 与梦幻宽晕 σ7.2dp 共用）。13 权重（±12 tap）：
// tap 间距必须压在源平滑尺度之下——首版宽晕 6tap×σ/2 的二维梳齿织出
// 格点阵，经 ~18× 增益放大成"小方格/雪花"伪影（D212）。uTexelStep 编码
// 方向与间距，权重 CPU 侧归一。
in vec2 vUv;
uniform sampler2D uSource;
uniform vec2 uTexelStep;
uniform float uWeights[13];
uniform int uTapCount;
out vec4 fragColor;

void main() {
    vec3 acc = uWeights[0] * texture(uSource, vUv).rgb;
    for (int j = 1; j <= uTapCount; ++j) {
        vec2 offset = uTexelStep * float(j);
        acc += uWeights[j] * (texture(uSource, vUv + offset).rgb
                              + texture(uSource, vUv - offset).rgb);
    }
    fragColor = vec4(acc, 1.0);
}
