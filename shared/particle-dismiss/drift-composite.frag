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
out vec4 o_color;

void main() {
    vec2 p = vec2(gl_FragCoord.x - u_viewport.x, u_viewport.y + u_viewport.w - gl_FragCoord.y);
    ivec2 w = ivec2(floor((p - u_rect.xy) * 0.5));
    if (any(lessThan(w, ivec2(0))) || any(greaterThanEqual(w, u_workSize))) discard;
    o_color = min(texelFetch(u_work, w, 0), vec4(1.0));
}
