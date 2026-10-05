#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;

in vec2 v_q;
in vec4 v_color;
uniform sampler2D u_sprite;   // 9×9 软点，只用 alpha，双线性取样
out vec4 o_color;

void main() {
    if (abs(v_q.x) > 1.0 || abs(v_q.y) > 1.0) discard;
    float cover = texture(u_sprite, v_q * 0.5 + 0.5).a;
    if (cover <= 0.0) discard;
    o_color = v_color * cover;
}
