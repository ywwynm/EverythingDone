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

float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
}

void main() {
    float q = 1.0 - vUv.y;
    vec3 color = q <= 0.42
        ? mix(uEnvironmentTop, uEnvironmentHorizon, q / 0.42)
        : mix(uEnvironmentHorizon, uEnvironmentBottom, (q - 0.42) / 0.58);
    vec3 encodedColor = clamp(color + triangularDither(gl_FragCoord.xy), 0.0, 1.0);
    fragColor = vec4(uSceneLinear ? srgbToLinear(encodedColor) : encodedColor, 1.0);
}
