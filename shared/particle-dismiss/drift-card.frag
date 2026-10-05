#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;

// 卡片残留：快照 × 消蚀遮罩（源码消蚀着色器，噪声模式），盖在颗粒之上。
// 快照像素按片元中心直接算（理由同 drift-composite.frag）。
uniform vec4 u_rect;          // 快照在视口里的位置（左上原点像素）：x0, y0, x1, y1
uniform vec4 u_viewport;      // 视口：x, y, 宽, 高（窗口坐标，左下原点）
uniform sampler2D u_image;    // 快照（预乘）
uniform ivec2 u_full;
uniform float u_time;
out vec4 o_color;

#include "drift-common.glsl"

void main() {
    vec2 p = vec2(gl_FragCoord.x - u_viewport.x, u_viewport.y + u_viewport.w - gl_FragCoord.y);
    ivec2 px = ivec2(floor(p - u_rect.xy));
    if (any(lessThan(px, ivec2(0))) || any(greaterThanEqual(px, u_full))) discard;
    float alpha = driftMaskAlpha(driftMaskValue(px), u_time);
    if (alpha <= 0.0) discard;
    o_color = texelFetch(u_image, px, 0) * alpha;
}
