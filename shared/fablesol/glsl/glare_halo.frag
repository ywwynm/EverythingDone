#version 300 es
precision highp float;

// 光轮 + 梦幻宽晕合成（半分辨率）。针芒已改为逐星解析精灵（D213）：
// 全场共享 gather 无法表达逐星差异，也受半分辨率采样限制。
in vec2 vUv;
uniform sampler2D uSpread;
uniform sampler2D uSpreadWide;
uniform float uStrength;
uniform float uHaloGain;
out vec4 fragColor;

const float AURA_WEIGHT = 0.5;

void main() {
    vec3 aura = texture(uSpread, vUv).rgb;
    // 梦幻宽晕：点源经两级模糊稀释 ~67×，uHaloGain 按稀释率反归一。
    vec3 halo = texture(uSpreadWide, vUv).rgb;
    fragColor = vec4(uStrength * (AURA_WEIGHT * aura + uHaloGain * halo), 1.0);
}
