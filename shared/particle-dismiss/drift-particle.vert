#version 310 es
precision highp float;
precision highp int;

// 飘散样式的颗粒：每颗一个实例，数据是 drift-update.comp 写的同一个缓冲，按 divisor = 1 的实例化属性读入
// （Mali / Maleoon 的顶点阶段读不了 SSBO）。四个顶点围成源码绘制后端的方块：半宽 + 1 个工作面像素的羽化。
layout(location = 0) in vec4 a_geometry;
layout(location = 1) in vec4 a_color;
uniform vec2 u_work;          // 工作面尺寸（像素）
uniform vec2 u_workOffset;    // 模拟坐标平移到覆盖完整窗口的工作面；不改变出生与运动计算
out vec2 v_q;
out vec4 v_color;

const vec2 CORNERS[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));

void main() {
    float h = a_geometry.z;
    v_color = a_color;
    if (h <= 0.0) {
        v_q = vec2(2.0);
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    vec2 local = CORNERS[gl_VertexID] * (h + 1.0);
    v_q = local / h;
    vec2 p = a_geometry.xy + u_workOffset + local;
    // 工作面纹理按自上而下的行号存放：第 r 行 = 工作面 y ∈ [r, r + 1)
    gl_Position = vec4(p.x / u_work.x * 2.0 - 1.0, p.y / u_work.y * 2.0 - 1.0, 0.0, 1.0);
}
