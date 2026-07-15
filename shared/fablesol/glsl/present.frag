#version 300 es
precision highp float;

in vec2 vUv;
uniform sampler2D uScene;
uniform vec3 uBackdropColor;
uniform float uPresentationAlpha;
uniform vec2 uViewportPx;
uniform float uCornerRadiusPx;
uniform bool uSceneLinear;
uniform float uHdrHeadroom;
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

float roundedRectCoverage(vec2 pointPx) {
    vec2 halfSize = uViewportPx * 0.5;
    float radius = min(uCornerRadiusPx, min(halfSize.x, halfSize.y));
    vec2 q = abs(pointPx - halfSize) - (halfSize - vec2(radius));
    float distanceToEdge = length(max(q, vec2(0.0))) +
        min(max(q.x, q.y), 0.0) - radius;
    return 1.0 - smoothstep(-0.75, 0.75, distanceToEdge);
}

float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
}

void main() {
    vec3 sceneColor = texture(uScene, vUv).rgb;
    float presentationAlpha = clamp(uPresentationAlpha, 0.0, 1.0);
    vec3 color;
    if (uSceneLinear) {
        sceneColor = clamp(sceneColor, vec3(0.0), vec3(uHdrHeadroom));
        color = mix(srgbToLinear(uBackdropColor), sceneColor, presentationAlpha);
    } else {
        color = mix(uBackdropColor, sceneColor, presentationAlpha);
        color = clamp(color + triangularDither(gl_FragCoord.xy), 0.0, 1.0);
    }
    float coverage = roundedRectCoverage(gl_FragCoord.xy);
    // Android surface 合成使用预乘 alpha；圆角外必须同时清零 RGB，避免亮边。
    fragColor = vec4(color * coverage, coverage);
}
