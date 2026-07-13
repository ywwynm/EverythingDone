#version 300 es
precision highp float;

in vec3 vColor;
in vec3 vSubsurfaceColor;
in vec2 vSurfacePositionPx;
in vec2 vSurfaceSlope;
in float vDepth01;
in float vCrestPinch;
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
    float rowFade = mix(1.0, 0.42, clamp(vDepth01, 0.0, 1.0));
    return linearColor * clamp(1.0 + lightDelta * 0.72 * rowFade, 0.82, 1.18);
}

vec3 addSunriseSubsurface(vec3 linearColor) {
    vec3 viewDir = normalize(vec3(0.0, sin(uViewElevationRad), -cos(uViewElevationRad)));
    vec3 lightDir = lightDirection();
    vec2 viewHorizontal = normalize(viewDir.xz);
    vec2 lightHorizontal = normalize(lightDir.xz);
    float sunAlignment = clamp(dot(viewHorizontal, lightHorizontal), 0.0, 1.0);
    float sunriseLobe = pow(sunAlignment, clamp(uSunSssFalloff, 4.0, 10.0));
    float crestMask = pow(clamp(vCrestPinch, 0.0, 1.0), 1.35);
    float nearMask = pow(1.0 - clamp(vDepth01, 0.0, 1.0), 0.70);
    float mask = crestMask * nearMask * (0.08 + 0.92 * sunriseLobe);
    return linearColor + srgbToLinear(vSubsurfaceColor) * uSunSssStrength * mask;
}

float triangularDither(vec2 p) {
    float a = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(p + 17.13, vec2(26.6514, 57.211))) * 24634.6345);
    return ((a + b) * 0.5 - 0.5) / 255.0;
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
    fragColor = vec4(uSceneLinear ? srgbToLinear(encodedColor) : encodedColor, 1.0);
}
