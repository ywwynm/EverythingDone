#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;

// 工作面（半分辨率）最近邻放大到设备像素；源码工作面为 8 位，逐通道截到 1。
// 扩展画布坐标按片元中心直接算（与桌面实现逐像素换算相同）：插值出来的坐标在整数边界上会差一个最小单位，
// 最近邻取样就会错到相邻的工作面像素。
uniform vec4 u_rect;          // 扩展画布在视口里的位置（左上原点像素）：x0, y0, x1, y1
uniform vec4 u_viewport;      // 视口：x, y, 宽, 高（窗口坐标，左下原点）
uniform sampler2D u_work;
uniform ivec2 u_workSize;
// 默认 0 保持桌面及其它宿主的既有输出；容器透明度不参与粒子材料筛选。
uniform float u_containerFade;
#ifdef PAGE_BASE
uniform sampler2D u_base;
uniform vec4 u_baseRect;
uniform vec4 u_workRect;
#endif
out vec4 o_color;

void main() {
    vec2 p = vec2(gl_FragCoord.x - u_viewport.x, u_viewport.y + u_viewport.w - gl_FragCoord.y);
#ifndef PAGE_BASE
    ivec2 w = ivec2(floor((p - u_rect.xy) * 0.5));
    if (any(lessThan(w, ivec2(0))) || any(greaterThanEqual(w, u_workSize))) discard;
    o_color = min(texelFetch(u_work, w, 0), vec4(1.0)) * (1.0 - u_containerFade);
#else
    ivec2 w = ivec2(floor((p - u_workRect.xy) * 0.5));
    bool inside = all(greaterThanEqual(w, ivec2(0))) && all(lessThan(w, u_workSize));
    vec4 particle = inside ? min(texelFetch(u_work, w, 0), vec4(1.0)) * (1.0 - u_containerFade) : vec4(0.0);
    vec4 background = vec4(0.0);
    ivec2 b = ivec2(floor(p - u_baseRect.xy));
    if (all(greaterThanEqual(b, ivec2(0))) && all(lessThan(b, ivec2(u_baseRect.zw))))
        background = texelFetch(u_base, b, 0);
    o_color = particle + background * (1.0 - particle.a);
#endif
}
