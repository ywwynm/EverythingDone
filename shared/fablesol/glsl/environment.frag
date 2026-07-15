#version 300 es
precision highp float;

in vec2 vUv;
uniform vec3 uEnvironmentTop;
uniform vec3 uEnvironmentHorizon;
uniform vec3 uEnvironmentBottom;
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

void main() {
    float q = 1.0 - vUv.y;
    vec3 top = uSceneLinear ? srgbToLinear(uEnvironmentTop) : uEnvironmentTop;
    vec3 horizon = uSceneLinear
        ? srgbToLinear(uEnvironmentHorizon)
        : uEnvironmentHorizon;
    vec3 bottom = uSceneLinear
        ? srgbToLinear(uEnvironmentBottom)
        : uEnvironmentBottom;
    vec3 color = q <= 0.42
        ? mix(top, horizon, q / 0.42)
        : mix(horizon, bottom, (q - 0.42) / 0.58);
    // HDR 必须先解码各停靠点再插值，避免在 encoded sRGB 中合成环境辐射。
    fragColor = vec4(color, 1.0);
}
