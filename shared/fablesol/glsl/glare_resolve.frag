#version 300 es
precision highp float;

// 全分辨率 resolve：先按显示 cap 收回场景超白（SDR 态 cap=1，录音态
// cap = 1+(headroom−1)×hdrGain 与既有钳制一致），再叠加眩光并二次 cap。
// SDR 场景纹理是 sRGB 编码的 RGBA8：解码→线性叠加→再编码。
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uGlare;
uniform float uDisplayCap;
uniform bool uSceneLinear;
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

float linearToSrgbChannel(float c) {
    c = clamp(c, 0.0, 1.0);
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

vec3 linearToSrgb(vec3 c) {
    return vec3(
        linearToSrgbChannel(c.r),
        linearToSrgbChannel(c.g),
        linearToSrgbChannel(c.b)
    );
}

void main() {
    vec4 scene = texture(uScene, vUv);
    vec3 sceneRgb = uSceneLinear ? scene.rgb : srgbToLinear(scene.rgb);
    vec3 capped = min(sceneRgb, vec3(uDisplayCap));
    vec3 color = min(capped + texture(uGlare, vUv).rgb, vec3(uDisplayCap));
    fragColor = vec4(uSceneLinear ? color : linearToSrgb(color), scene.a);
}
