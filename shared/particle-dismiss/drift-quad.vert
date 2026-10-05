#version 310 es
precision highp float;
precision highp int;

// 叠加层像素坐标（左上原点）里的一个矩形，外扩一个像素：片元着色器按片元中心自己判定落在不在矩形里，
// 光栅化只负责盖住它（边界像素交给片元着色器，不靠三角形边界的取舍）。
uniform vec4 u_rect;          // x0, y0, x1, y1
uniform vec4 u_viewport;      // 视口：x, y, 宽, 高

const vec2 CORNERS[4] = vec2[4](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));

void main() {
    vec2 p = mix(u_rect.xy - 1.0, u_rect.zw + 1.0, CORNERS[gl_VertexID]);
    gl_Position = vec4(p.x / u_viewport.z * 2.0 - 1.0, 1.0 - p.y / u_viewport.w * 2.0, 0.0, 1.0);
}
