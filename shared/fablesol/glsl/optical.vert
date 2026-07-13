#version 300 es
precision highp float;

layout(location = 0) in vec2 aPositionPx;
layout(location = 1) in vec2 aLocalUv;
layout(location = 2) in vec4 aColor;
layout(location = 3) in float aOpticalMode;
layout(location = 4) in vec3 aEdgeColor;

uniform vec2 uViewportPx;
uniform float uRotationRad;

out vec2 vLocalUv;
out vec4 vColor;
out float vOpticalMode;
out vec3 vEdgeColor;

void main() {
    float c = cos(-uRotationRad);
    float s = sin(-uRotationRad);
    vec2 rotated = vec2(
        c * aPositionPx.x - s * aPositionPx.y,
        s * aPositionPx.x + c * aPositionPx.y
    );
    vec2 screen = rotated + uViewportPx * 0.5;
    vec2 ndc = vec2(
        screen.x / uViewportPx.x * 2.0 - 1.0,
        1.0 - screen.y / uViewportPx.y * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    vLocalUv = aLocalUv;
    vColor = aColor;
    vOpticalMode = aOpticalMode;
    vEdgeColor = aEdgeColor;
}
