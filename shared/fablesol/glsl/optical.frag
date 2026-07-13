#version 300 es
precision highp float;

in vec2 vLocalUv;
in vec4 vColor;
in float vOpticalMode;
in vec3 vEdgeColor;
out vec4 fragColor;

void main() {
    float coverage;
    if (vOpticalMode > 6.5) {
        // 解析光晕：在线性强度意义下先于闪点核心绘制，无模糊采样。
        vec2 haloPoint = vec2(vLocalUv.x * 0.76, vLocalUv.y * 1.12);
        float radiusSquared = dot(haloPoint, haloPoint);
        float falloff = exp2(-4.6 * radiusSquared);
        float boundaryTaper = 1.0 - smoothstep(0.82, 1.0, vLocalUv.y);
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
    vec3 color = vOpticalMode > 2.5 && vOpticalMode < 3.5
        ? mix(vColor.rgb, vEdgeColor, smoothstep(0.0, 0.72, halo))
        : vColor.rgb;
    fragColor = vec4(color, vColor.a * coverage);
}
