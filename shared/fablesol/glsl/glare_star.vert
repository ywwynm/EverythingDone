#version 300 es
precision highp float;

// 人眼眩光（D206~D209）：CPU 星光轨迹的点光源注入。星点画进半分辨率
// E 场；√ 压缩与白炽去饱和已在 CPU 侧完成。Android 专用链（Python 桌面
// 以内嵌同构 shader 实现）；不属于 water/optical 七文件生产合同。
layout(location = 0) in vec2 aCornerPx;
layout(location = 1) in vec2 aCenterPx;
layout(location = 2) in float aAmplitude;
layout(location = 3) in vec3 aTint;
uniform vec2 uViewportPx;
out vec2 vDeltaPx;
out float vAmplitude;
out vec3 vTint;

void main() {
    vDeltaPx = aCornerPx - aCenterPx;
    vAmplitude = aAmplitude;
    vTint = aTint;
    vec2 ndc = vec2(aCornerPx.x / uViewportPx.x * 2.0 - 1.0,
                    1.0 - aCornerPx.y / uViewportPx.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
