#version 310 es
precision highp float;
precision highp int;

// 飘散样式的颗粒：每颗一个实例，数据是 drift-update.comp 写的同一个缓冲，按 divisor = 1 的实例化属性读入
// （Mali / Maleoon 的顶点阶段读不了 SSBO）。只裁掉解析覆盖恒为零的外围，不改变覆盖坐标与导数。
layout(location = 0) in vec4 a_geometry;
layout(location = 1) in vec4 a_color;
uniform vec2 u_work;          // 工作面尺寸（像素）
uniform vec2 u_workOffset;    // 模拟坐标平移到覆盖完整窗口的工作面；不改变出生与运动计算
uniform int u_tightQuad;
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
    // 覆盖半径为 0.64h，四点采样各偏移 0.25 工作像素，再留 0.25 像素余量。
    // 亚像素小点保留旧几何，避免极小三角形的光栅化精度改变覆盖采样。
    vec2 local = CORNERS[gl_VertexID] * (u_tightQuad != 0 && h >= 0.5 ? 0.64 * h + 0.5 : h + 1.0);
    v_q = local / h;
    vec2 p = a_geometry.xy + u_workOffset + local;
    // 工作面纹理按自上而下的行号存放：第 r 行 = 工作面 y ∈ [r, r + 1)
    gl_Position = vec4(p.x / u_work.x * 2.0 - 1.0, p.y / u_work.y * 2.0 - 1.0, 0.0, 1.0);
}
