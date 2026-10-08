#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;

in vec2 v_q;
in vec4 v_color;
out vec4 o_color;

#include "drift-coverage.glsl"

void main() {
    float cover = driftParticleCoverage(v_q, dFdx(v_q), dFdy(v_q));
    if (cover <= 0.0) discard;
    o_color = v_color * cover;
}
