#version 300 es
precision highp float;

in vec3 vColor;
in vec3 vSubsurfaceColor;
in vec2 vSurfacePositionPx;
in vec2 vSurfaceSlope;
in float vDepth01;
in float vCrestPinch;
in vec2 vSheenSlope;
in float vMicroNormalWeight;
in float vSdrSssWeight;
in float vHdrSheenPeak;
in float vHdrTransmissionPeak;
flat in int vFrontFill;

uniform float uTimeSeconds;
uniform float uViewElevationRad;
uniform float uLightAzimuthRad;
uniform float uSurfaceHeadingRad;
uniform float uMicroNormalStrength;
uniform float uSpecularAaStrength;
uniform float uSunSssStrength;
uniform float uSunSssFalloff;
uniform bool uSceneLinear;
uniform float uHdrGain;
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

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// IQ 风格解析导数值噪声：x=值，yz=对输入坐标的解析导数。
vec3 valueNoiseDerivative(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
    float crossTerm = a - b - c + d;
    float value = a + (b - a) * u.x + (c - a) * u.y + crossTerm * u.x * u.y;
    vec2 derivative = du * vec2(
        (b - a) + crossTerm * u.y,
        (c - a) + crossTerm * u.x
    );
    return vec3(value, derivative);
}

float octaveBandLimit(float frequency, float depth01) {
    float rowFootprint = mix(1.0, 3.2, clamp(depth01, 0.0, 1.0));
    float normalizedFootprint = rowFootprint * frequency / 44.0;
    float analyticLimit = 1.0 - smoothstep(0.16, 0.42, normalizedFootprint);
    return mix(1.0, analyticLimit, clamp(uSpecularAaStrength, 0.0, 1.0));
}

vec2 windCombedMicroDerivative(vec2 positionPx, float depth01) {
    vec2 wind = normalize(vec2(cos(uSurfaceHeadingRad), sin(uSurfaceHeadingRad)));
    vec2 across = vec2(-wind.y, wind.x);
    vec2 combed = vec2(
        dot(positionPx, wind) * 0.34,
        dot(positionPx, across) * 1.28
    ) / 44.0;
    combed.x += uTimeSeconds * 0.11;
    vec2 derivative = vec2(0.0);
    float weightSum = 0.0;

    vec3 octave0 = valueNoiseDerivative(combed);
    float weight0 = octaveBandLimit(1.0, depth01);
    derivative += octave0.yz * weight0;
    weightSum += weight0;

    vec3 octave1 = valueNoiseDerivative(combed * 2.03 + vec2(17.1, 9.2));
    float weight1 = 0.52 * octaveBandLimit(2.03, depth01);
    derivative += octave1.yz * weight1 * 2.03;
    weightSum += weight1;

    vec3 octave2 = valueNoiseDerivative(combed * 4.11 + vec2(4.7, 23.3));
    float weight2 = 0.26 * octaveBandLimit(4.11, depth01);
    derivative += octave2.yz * weight2 * 4.11;
    weightSum += weight2;
    return derivative / max(weightSum, 1e-4);
}

vec3 lightDirection() {
    float lightElevation = radians(50.0);
    return normalize(vec3(
        sin(uLightAzimuthRad) * cos(lightElevation),
        sin(lightElevation),
        -cos(uLightAzimuthRad) * cos(lightElevation)
    ));
}

vec3 applyWindCombedMicroNormals(vec3 linearColor) {
    float rowFade = clamp(vMicroNormalWeight, 0.0, 1.0);
    if (rowFade <= 0.0001) return linearColor;
    vec2 derivative = windCombedMicroDerivative(vSurfacePositionPx, vDepth01);
    vec2 microSlope = derivative * (0.075 * uMicroNormalStrength);
    vec3 baseNormal = normalize(vec3(-vSurfaceSlope.x, 1.0, -vSurfaceSlope.y));
    vec3 detailNormal = normalize(vec3(
        -(vSurfaceSlope.x + microSlope.x),
        1.0,
        -(vSurfaceSlope.y + microSlope.y)
    ));
    vec3 lightDir = lightDirection();
    float lightDelta = dot(detailNormal, lightDir) - dot(baseNormal, lightDir);
    return linearColor * clamp(1.0 + lightDelta * 0.72 * rowFade, 0.82, 1.18);
}

float sunriseSubsurfaceMask() {
    vec3 viewDir = normalize(vec3(0.0, sin(uViewElevationRad), -cos(uViewElevationRad)));
    vec3 lightDir = lightDirection();
    vec2 viewHorizontal = normalize(viewDir.xz);
    vec2 lightHorizontal = normalize(lightDir.xz);
    float sunAlignment = clamp(dot(viewHorizontal, lightHorizontal), 0.0, 1.0);
    float sunriseLobe = pow(sunAlignment, clamp(uSunSssFalloff, 4.0, 10.0));
    float crestMask = pow(clamp(vCrestPinch, 0.0, 1.0), 1.35);
    float nearMask = clamp(vSdrSssWeight, 0.0, 1.0);
    return crestMask * nearMask * (0.08 + 0.92 * sunriseLobe);
}

vec3 addSunriseSubsurface(vec3 linearColor) {
    return linearColor + srgbToLinear(vSubsurfaceColor) *
        uSunSssStrength * sunriseSubsurfaceMask();
}

float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
}

// Step C：掠射角 Fresnel 天空/太阳反射的超白光泽。只在 scene-linear 录音态加"越过 reference
// white"的差量，SDR 路径完全不进——把 Step B 压弱的那片反射在 HDR 里提成发亮的银泽。
// 大面积柔（峰值随深度归 SDR，D74）、朝太阳集中（统一太阳模型）、近中性白带一丝身份色（D69）。
vec3 grazingSheenExcess(vec3 normal, float fresnel) {
    vec3 viewDir = normalize(vec3(0.0, sin(uViewElevationRad), -cos(uViewElevationRad)));
    vec3 lightDir = lightDirection();
    // 朝太阳只作加亮加成（基线 1、朝太阳更高），不作门控——平缓水面几乎没有满足窄瓣的朝向，
    // 一旦拿它门控，光泽会整片消失（这正是上一版完全看不到 HDR 的原因）。
    vec3 refl = reflect(-viewDir, normal);
    float sunCos = clamp(dot(refl, lightDir), 0.0, 1.0);
    float sunBoost = 1.0 + 1.4 * pow(sunCos, 3.0);               // 全域 ~1，朝太阳最高 ~2.4
    // 低通宏观法线表达有限粗糙度下的宽镜面瓣；0.70 只拓宽 HDR 银泽，不改变 SDR 基色。
    float grazing = pow(clamp(fresnel * sunBoost, 0.0, 1.0), 0.70);
    float sheenPeak = min(vHdrSheenPeak, uHdrHeadroom);
    float sheen = grazing * max(sheenPeak - 1.0, 0.0);           // 超白差量，天然不超过 headroom
    float maxChannel = max(max(vColor.r, vColor.g), max(vColor.b, 0.001));
    vec3 tint = mix(vec3(1.0), vColor / maxChannel, 0.14);        // 近中性白 + 一丝身份色
    return tint * (sheen * uHdrGain);
}

// Step D：与反射共用 Fresnel 预算的背光透射。现有 SSS 负责 SDR 内的体色提亮；这里只把同一
// 小面积 crest/backlit 掩码放行到 reference white 以上。透射使用 (1-R)，因此掠射自动让位给
// Step C 银泽，较正视角保留最多身份色；mode8 只留下很弱的独立肩部。
vec3 backlitTransmissionExcess(float fresnel) {
    float strength = clamp(uSunSssStrength / 0.16, 0.0, 1.0);
    float transmissionPeak = min(
        vHdrTransmissionPeak,
        uHdrHeadroom
    );
    float budget = (1.0 - fresnel) * sunriseSubsurfaceMask() * strength *
        max(transmissionPeak - 1.0, 0.0) * uHdrGain;
    vec3 subsurfaceLinear = srgbToLinear(vSubsurfaceColor);
    float maximum = max(max(subsurfaceLinear.r, subsurfaceLinear.g),
        max(subsurfaceLinear.b, 0.001));
    vec3 identityTint = subsurfaceLinear / maximum;
    return identityTint * budget;
}

void main() {
    vec3 color = vColor;
    if (vFrontFill == 0) {
        vec3 linearColor = srgbToLinear(color);
        if (uMicroNormalStrength > 0.0001) {
            linearColor = applyWindCombedMicroNormals(linearColor);
        }
        if (uSunSssStrength > 0.0001) {
            linearColor = addSunriseSubsurface(linearColor);
        }
        color = linearToSrgb(linearColor);
    }
    float dither = vFrontFill == 1 ? triangularDither(gl_FragCoord.xy) : 0.0;
    vec3 encodedColor = clamp(color + dither, 0.0, 1.0);
    vec3 outLinear = uSceneLinear ? srgbToLinear(encodedColor) : encodedColor;
    if (uSceneLinear && vFrontFill == 0 && uHdrGain > 0.0001 &&
            uHdrHeadroom > 1.001 &&
            (vHdrSheenPeak > 1.001 || vHdrTransmissionPeak > 1.001)) {
        vec3 viewDir = normalize(vec3(0.0, sin(uViewElevationRad), -cos(uViewElevationRad)));
        vec3 normal = normalize(vec3(-vSheenSlope.x, 1.0, -vSheenSlope.y));
        float NdV = clamp(dot(normal, viewDir), 0.0, 1.0);
        float f0 = 0.020373;
        float fresnel = f0 + (1.0 - f0) * pow(1.0 - NdV, 5.0);
        outLinear += grazingSheenExcess(normal, fresnel);
        outLinear += backlitTransmissionExcess(fresnel);
        outLinear = min(outLinear, vec3(uHdrHeadroom));
    }
    fragColor = vec4(outLinear, 1.0);
}
