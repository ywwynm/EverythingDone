#version 300 es
precision highp float;

in vec2 vUv;
uniform sampler2D uScene;
uniform vec3 uBackdropColor;
uniform float uPresentationAlpha;
uniform vec2 uViewportPx;
uniform float uCornerRadiusPx;
uniform bool uSceneLinear;
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

float linearToSrgbChannel(float c) {
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

vec3 linearToSrgb(vec3 c) {
    return vec3(
        linearToSrgbChannel(c.r),
        linearToSrgbChannel(c.g),
        linearToSrgbChannel(c.b)
    );
}

float roundedRectCoverage(vec2 pointPx) {
    vec2 halfSize = uViewportPx * 0.5;
    float radius = min(uCornerRadiusPx, min(halfSize.x, halfSize.y));
    vec2 q = abs(pointPx - halfSize) - (halfSize - vec2(radius));
    float distanceToEdge = length(max(q, vec2(0.0))) +
        min(max(q.x, q.y), 0.0) - radius;
    return 1.0 - smoothstep(-0.75, 0.75, distanceToEdge);
}

void main() {
    vec3 sceneColor = texture(uScene, vUv).rgb;
    float presentationAlpha = clamp(uPresentationAlpha, 0.0, 1.0);
    vec3 color;
    if (uSceneLinear) {
        // 在编码域复刻原有 0.16↔1.0 presentation alpha，再转回 linear scRGB。
        vec3 encodedScene = linearToSrgb(sceneColor);
        color = srgbToLinear(mix(uBackdropColor, encodedScene, presentationAlpha));
    } else {
        color = mix(uBackdropColor, sceneColor, presentationAlpha);
    }
    float coverage = roundedRectCoverage(gl_FragCoord.xy);
    // Android surface 合成使用预乘 alpha；圆角外必须同时清零 RGB，避免亮边。
    fragColor = vec4(color * coverage, coverage);
}
