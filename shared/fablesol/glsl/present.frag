#version 300 es
precision highp float;

in vec2 vUv;
uniform sampler2D uScene;
// 卓易通真实控件的 sRGB 预乘纹理；默认关闭，不改变普通设备和离线导出。
uniform sampler2D uForeground;
uniform bool uHasForeground;
uniform vec3 uBackdropColor;
uniform float uPresentationAlpha;
uniform vec2 uViewportPx;
uniform float uCornerRadiusPx;
// 下两角相对 uCornerRadiusPx 的半径增量（默认 0 = 与上两角一致）。调参 Dialog
// 的顶置预览用 -uCornerRadiusPx 把底部两角切成直角，与下方内容无缝衔接。
uniform float uCornerRadiusBottomDeltaPx;
uniform bool uSceneLinear;
// 默认 false 保持 Android scRGB／SDR 及桌面模拟器的原有输出。
uniform bool uOutputPq;
// 截图在应用内显式转回 SDR，避免依赖兼容环境的 PQ PixelCopy 转换。
uniform bool uOutputSdr;
uniform bool uPqRgba8;
uniform float uHdrHeadroom;
out vec4 fragColor;

float srgbToLinearChannel(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

vec3 srgbToLinear(vec3 c) {
    return vec3(
        srgbToLinearChannel(c.r),
        srgbToLinearChannel(c.g),
        srgbToLinearChannel(c.b)
    );
}

vec3 linearToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(12.92 * c, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055,
        step(vec3(0.0031308), c));
}

float roundedRectCoverage(vec2 pointPx) {
    vec2 halfSize = uViewportPx * 0.5;
    // gl_FragCoord 的 y 向上：y > halfSize.y 属于视图顶部两角。
    float cornerRadiusPx = pointPx.y > halfSize.y
        ? uCornerRadiusPx
        : max(uCornerRadiusPx + uCornerRadiusBottomDeltaPx, 0.0);
    float radius = min(cornerRadiusPx, min(halfSize.x, halfSize.y));
    vec2 q = abs(pointPx - halfSize) - (halfSize - vec2(radius));
    float distanceToEdge = length(max(q, vec2(0.0))) +
        min(max(q.x, q.y), 0.0) - radius;
    return 1.0 - smoothstep(-0.75, 0.75, distanceToEdge);
}

float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
}

vec3 encodePq(vec3 linear709) {
    vec3 wide = vec3(
        dot(linear709, vec3(0.62740390, 0.32928304, 0.04331307)),
        dot(linear709, vec3(0.06909729, 0.91954040, 0.01136231)),
        dot(linear709, vec3(0.01639144, 0.08801331, 0.89559525))
    );
    // 与既有图片 PQ／视频导出一致：203 nit 是内容参考白，不是屏幕测量值。
    vec3 y = pow(clamp(wide * (203.0 / 10000.0), 0.0, 1.0), vec3(0.1593017578125));
    return pow((vec3(0.8359375) + 18.8515625 * y) / (vec3(1.0) + 18.6875 * y), vec3(78.84375));
}

void main() {
    vec3 sceneColor = texture(uScene, vUv).rgb;
    float presentationAlpha = clamp(uPresentationAlpha, 0.0, 1.0);
    vec3 color;
    if (uSceneLinear) {
        sceneColor = clamp(sceneColor, vec3(0.0), vec3(uHdrHeadroom));
        color = mix(srgbToLinear(uBackdropColor), sceneColor, presentationAlpha);
    } else {
        color = mix(uBackdropColor, sceneColor, presentationAlpha);
        color = clamp(color + triangularDither(gl_FragCoord.xy), 0.0, 1.0);
    }
    if (uHasForeground) {
        // HWUI 硬件缓冲以左上为原点。先取消预乘再做 EOTF，不能对预乘 RGB 直接转线性。
        vec4 ui = texture(uForeground, vec2(vUv.x, 1.0 - vUv.y));
        vec3 straight = ui.a > 0.0 ? clamp(ui.rgb / ui.a, 0.0, 1.0) : vec3(0.0);
        vec3 foreground = uSceneLinear ? srgbToLinear(straight) : straight;
        // 控件使用内容参考白，独立于海浪准备态透明度和 HDR 高光强度。
        color = mix(color, foreground, ui.a);
    }
    float coverage = roundedRectCoverage(gl_FragCoord.xy);
    if (uOutputPq) {
        color = encodePq(color);
        // 卓易通透明窗口使用 RGBA8：只对 PQ 编码值抖动，不改变线性场景或亮度预算。
        // 各通道共用同一噪声，避免中性白／灰产生彩色噪点。
        if (uPqRgba8) color = clamp(color + triangularDither(gl_FragCoord.xy), 0.0, 1.0);
    }
    if (uOutputSdr && uSceneLinear) color = linearToSrgb(color);
    // 完整 8 位 alpha；准备态的 presentationAlpha 只混 RGB，圆角内部始终不透明。
    if (uOutputPq && uPqRgba8) coverage = floor(coverage * 255.0 + 0.5) / 255.0;
    // Android surface 合成使用预乘 alpha；圆角外必须同时清零 RGB，避免亮边。
    fragColor = vec4(color * coverage, coverage);
}
