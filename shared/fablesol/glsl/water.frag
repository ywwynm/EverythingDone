#version 300 es
precision highp float;

in vec3 vColor;
in vec3 vSubsurfaceColor;
in vec3 vMaterialColor;
in vec3 vBehindColor;
in float vMaterialOpacity;
in vec2 vScreenUv;
in vec2 vSurfacePositionPx;
in vec2 vSurfaceSlope;
in float vDepth01;
in float vCrestPinch;
in vec2 vSheenSlope;
in float vMicroNormalWeight;
in float vSdrSssWeight;
in float vHdrTransmissionPeak;
in float vDirectLight;
in float vThickness01;
in float vThicknessSurface;
in float vThicknessGlowWeight;
flat in int vFrontFill;

uniform sampler2D uPreWaterScene;
uniform vec2 uViewportPx;
uniform float uRasterScale;
uniform highp int uStartLayer;
uniform float uTimeSeconds;
uniform float uViewElevationRad;
uniform float uLightAzimuthRad;
uniform float uSurfaceHeadingRad;
uniform float uMicroNormalStrength;
uniform float uSpecularAaStrength;
uniform float uSunSssStrength;
uniform float uSunSssFalloff;
uniform bool uFrontFill;
uniform bool uSceneLinear;
uniform float uHdrGain;
uniform float uHdrHeadroom;
// 厚度透光（2026-07-16 质感提升批）：0 = 关闭 = 与既有输出逐位一致；
// uGlowDerivedBoost 为透光派生的线性提亮倍数。Android 未上传时按 GLES 默认 0 关闭。
uniform float uThicknessGlowStrength;
uniform float uGlowDerivedBoost;
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

// (1.333 - 1)^2 / (1.333 + 1)^2：空气到水面的法向入射反射率。
const float WATER_F0 = 0.020373;
const float WATER_IOR = 1.333;

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
    float normalizedFootprint = rowFootprint * frequency / 42.0;
    float analyticLimit = 1.0 - smoothstep(0.16, 0.42, normalizedFootprint);
    return mix(1.0, analyticLimit, clamp(uSpecularAaStrength, 0.0, 1.0));
}

vec2 windCombedMicroDerivative(vec2 positionPx, float depth01) {
    vec2 wind = normalize(vec2(cos(uSurfaceHeadingRad), sin(uSurfaceHeadingRad)));
    vec2 across = vec2(-wind.y, wind.x);
    vec2 combed = vec2(
        dot(positionPx, wind) * 0.34,
        dot(positionPx, across) * 1.28
    ) / 42.0;
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
    vec3 subsurfaceLinear = uSceneLinear
        ? vSubsurfaceColor
        : srgbToLinear(vSubsurfaceColor);
    float amount = clamp(
        uSunSssStrength * sunriseSubsurfaceMask(),
        0.0,
        0.160
    );
    return mix(linearColor, subsurfaceLinear, amount);
}

vec3 preWaterSceneLinear(vec2 uv) {
    vec2 halfTexel = 0.5 / max(uViewportPx, vec2(1.0));
    vec2 safeUv = clamp(uv, halfTexel, vec2(1.0) - halfTexel);
    // 显式 LOD 避免 sampler 位于 front-fill 非一致分支时依赖隐式导数。
    vec3 sampled = textureLod(uPreWaterScene, safeUv, 0.0).rgb;
    return uSceneLinear ? sampled : srgbToLinear(sampled);
}

// 掠射差分光泽已于 2026-07-16 目测否决并整项移除（宁少勿烂）。

// D155 薄度/透光量：入射量 = 水面处的波峰门（vThicknessSurface，波峰越高出
// 层均值进入水体的光越多，波谷为 0）；随后按"水面下深度"做 Beer–Lambert
// 指数衰减（λ = 2×uThicknessRangePx）。波浪网格顶点即在水面上，两 varying
// 恒等 → 深度为 0、无衰减，与旧行为一致；front fill 由此获得突破水位线、
// 逐列跟随水面轮廓的自然淡出。
float thicknessThin() {
    float entry = clamp(vThicknessSurface, 0.0, 1.0);
    float depthNorm = max(vThicknessSurface - vThickness01, 0.0);
    // 近表亮环：水面下前 0.35×范围保持全入射（近表层散射），随后
    // Beer–Lambert 衰减 λ=2.5×范围——用户裁决第 0 层亮度不得输给第 1 层。
    float transmit = exp(-max(depthNorm - 0.35, 0.0) * 0.4);
    return pow(clamp(entry * transmit, 0.0, 1.0), 1.25);
}

// 厚度透光（合并"波峰透光/薄峰透光"的材质版）：厚度代理来自顶点阶段的
// "高出层均值高度"（vThickness01），薄处按迎光坡向从内部亮起；
// 目标色 = subsurface 派生再线性提亮 uGlowDerivedBoost（保色相与饱和比，
// 不向白混）。0 = 关闭 = 与既有输出逐位一致。约 +10 ALU/px，零采样。
// boostScale：波浪侧恒 1；front fill（第 0 层）为 1.35——身份色最纯最深，
// 需更高提亮才能与混白近层达到同等绝对亮度（浅色身份被 1.0 钳制自然封顶）。
vec3 thicknessGlow(vec3 baseLinear, float normalX, float boostScale) {
    vec3 subsurfaceLinear = uSceneLinear
        ? vSubsurfaceColor
        : srgbToLinear(vSubsurfaceColor);
    vec3 target = min(
        subsurfaceLinear * (max(uGlowDerivedBoost, 1.0) * boostScale),
        vec3(1.0));
    float sunSide = clamp(sin(uLightAzimuthRad) * 4.0, -1.0, 1.0);
    float facing = clamp(0.5 + 1.1 * sunSide * normalX, 0.0, 1.0);
    float thin = thicknessThin();
    // D154：独立权重表；未上传（旧接线）时回退 SDR_SSS 权重，行为与 D151 一致。
    float layerWeight = vThicknessGlowWeight > 0.0001
        ? clamp(vThicknessGlowWeight, 0.0, 1.0)
        : clamp(vSdrSssWeight, 0.0, 1.0);
    float amount = clamp(
        uThicknessGlowStrength * thin * (0.35 + 0.65 * facing) *
            layerWeight * 0.62,
        0.0,
        0.55
    );
    return mix(baseLinear, target, amount);
}

// 供 HDR 透射 excess 复用的厚度掩码（与 SDR 透光同源）。
float thicknessExcessMask(vec3 normal) {
    float sunSide = clamp(sin(uLightAzimuthRad) * 4.0, -1.0, 1.0);
    float facing = clamp(0.5 + 1.1 * sunSide * normal.x, 0.0, 1.0);
    float thin = thicknessThin();
    return thin * facing * min(uThicknessGlowStrength, 1.0);
}

vec2 screenSpaceRefractionOffsetPx(
        vec3 normal,
        vec3 viewDir,
        float depth01) {
    vec3 incident = -viewDir;
    vec3 transmitted = refract(incident, normal, 1.0 / WATER_IOR);
    vec3 flatTransmitted = refract(
        incident,
        vec3(0.0, 1.0, 0.0),
        1.0 / WATER_IOR
    );
    vec2 lateral = transmitted.xz / max(-transmitted.y, 0.24);
    vec2 flatLateral = flatTransmitted.xz / max(-flatTransmitted.y, 0.24);
    float depthCurve = pow(clamp(depth01, 0.0, 1.0), 0.75);
    float offsetScalePx = mix(6.0, 16.0, depthCurve) * uRasterScale;
    vec2 delta = lateral - flatLateral;
    return offsetScalePx * vec2(
        delta.x,
        delta.y * sin(uViewElevationRad)
    );
}

vec3 applyRefractionAndBeer(
        vec3 linearColor,
        vec3 normal,
        vec3 viewDir) {
    vec3 identity = max(
        uSceneLinear ? vMaterialColor : srgbToLinear(vMaterialColor),
        vec3(0.0)
    );
    vec3 behind = max(
        uSceneLinear ? vBehindColor : srgbToLinear(vBehindColor),
        vec3(0.0)
    );
    vec2 offsetPx = screenSpaceRefractionOffsetPx(
        normal,
        viewDir,
        vDepth01
    );
    vec3 refractedBackground = preWaterSceneLinear(
        vScreenUv + offsetPx / max(uViewportPx, vec2(1.0))
    );

    vec3 incident = -viewDir;
    vec3 transmitted = refract(incident, normal, 1.0 / WATER_IOR);
    float depthCurve = pow(clamp(vDepth01, 0.0, 1.0), 0.75);
    float thickness = mix(0.72, 1.44, depthCurve);
    float opticalPath = thickness / max(-transmitted.y, 0.24);

    float peak = max(max(identity.r, identity.g), max(identity.b, 0.04));
    vec3 identityRatio = clamp(identity / peak, 0.0, 1.0);
    float minimumChannel = min(min(identity.r, identity.g), identity.b);
    float chroma = peak - minimumChannel;
    // 绝对线性 RGB 色差会把 B6BF8F 这类浅灰绿误判为高彩度。
    // 改用相对彩度，并随感知亮度连续收紧透过预算；浅色仍有真实折射，
    // 但不会为了显示背景而牺牲相邻层主体色差。
    float relativeChroma = chroma / max(peak, 0.04);
    float chromaGate = smoothstep(0.42, 0.86, relativeChroma);
    float perceptualLightness = pow(clamp(dot(
        identity,
        vec3(0.2126, 0.7152, 0.0722)
    ), 0.0, 1.0), 1.0 / 3.0);
    float lightColorProtection = 1.0 - 0.72 * smoothstep(
        0.62,
        0.86,
        perceptualLightness
    );
    vec3 sigma = vec3(0.12) + 0.24 * chromaGate *
        (vec3(1.0) - sqrt(max(identityRatio, vec3(0.05))));
    vec3 beer = exp(-sigma * opticalPath);

    float NdV = clamp(dot(normal, viewDir), 0.0, 1.0);
    float surfaceFresnel = WATER_F0 + (1.0 - WATER_F0) *
        pow(1.0 - NdV, 5.0);
    // 体积透过只占身份主体的小部分；浅色/低彩度进一步收敛，避免环境白洗平层差。
    float clearWaterFraction = mix(
        0.006,
        0.016,
        chromaGate * lightColorProtection
    );
    vec3 effectiveTransmission =
        (1.0 - surfaceFresnel) * clearWaterFraction * beer;
    vec3 volume = mix(identity, refractedBackground, effectiveTransmission);
    // vColor 已等于 mix(behind, identity, opacity)；这里用同一 opacity 只合成
    // 一次带折射/Beer 的当前介质，避免把本层 alpha 重复应用。
    return mix(behind, volume, clamp(vMaterialOpacity, 0.0, 1.0));
}

vec3 backlitTransmissionExcess(float fresnel, float thicknessMask) {
    float strength = clamp(uSunSssStrength / 0.16, 0.0, 1.0);
    float transmissionPeak = min(
        vHdrTransmissionPeak,
        uHdrHeadroom
    );
    // 厚度掩码与 SDR 厚度透光同源（uThicknessGlowStrength=0 时恒为 0）。
    float mask = min(sunriseSubsurfaceMask() * strength + thicknessMask, 1.0);
    float budget = 0.58 * (1.0 - fresnel) * mask *
        max(transmissionPeak - 1.0, 0.0) * uHdrGain;
    vec3 subsurfaceLinear = uSceneLinear
        ? vSubsurfaceColor
        : srgbToLinear(vSubsurfaceColor);
    float maximum = max(max(subsurfaceLinear.r, subsurfaceLinear.g),
        max(subsurfaceLinear.b, 0.001));
    vec3 identityTint = subsurfaceLinear / maximum;
    return identityTint * budget;
}

float waterEdgeCoverage() {
    float insideDistance = vFrontFill == 1
        ? -vDepth01
        : float(uStartLayer) / 8.0 - vDepth01;
    float pixelDepth = max(fwidth(vDepth01), 1e-6);
    // 只处理轮廓命中的亚像素残差；完整单侧 1px 坡道会侵占本就很薄的中远层主体，
    // 18 色回归会把相邻层色差压低近一半。主要精细度由原生 DPR 与 C1 网格承担。
    return smoothstep(-0.5 * pixelDepth, 0.5 * pixelDepth, insideDistance);
}

vec3 boundedReferenceWhite(vec3 linearColor) {
    vec3 baseline = max(linearColor, vec3(0.0));
    float peak = max(
        max(baseline.r, baseline.g),
        max(baseline.b, 1.0)
    );
    return baseline / peak;
}

vec3 edgeBehindBaseline() {
    vec3 behindLinear = uSceneLinear
        ? vBehindColor
        : srgbToLinear(vBehindColor);
    return boundedReferenceWhite(behindLinear);
}

void main() {
    // front-fill 是独立 uniform draw；在所有导数之前按整次 draw 一致地早退，
    // 避免前景纯色遮挡区仍支付微法线、哈希与折射的完整成本。
    if (uFrontFill) {
        float coverage = waterEdgeCoverage();
        vec3 frontLinear = uSceneLinear ? vColor : srgbToLinear(vColor);
        // D155（2026-07-16 用户裁决，适当放宽 D6）：前景水体在水线下
        // uThicknessRangePx 内接受同一厚度透光，让第 0 层的透光可见；
        // vThickness01 随深度衰减为 0，大面积主体仍保持身份纯色。
        // 迎光信号来自顶边复制的 sheen slope（未接线的平台为 0 → 中性 0.5）。
        if (uThicknessGlowStrength > 0.0001) {
            vec3 fillNormal = normalize(
                vec3(-vSheenSlope.x, 1.0, -vSheenSlope.y)
            );
            frontLinear = thicknessGlow(frontLinear, fillNormal.x, 1.35);
        }
        vec3 frontOutput = mix(
            edgeBehindBaseline(),
            boundedReferenceWhite(frontLinear),
            coverage
        );
        fragColor = uSceneLinear
            ? vec4(frontOutput, 1.0)
            : vec4(clamp(linearToSrgb(frontOutput), 0.0, 1.0), 1.0);
        return;
    }

    float microWeight = clamp(vMicroNormalWeight, 0.0, 1.0);
    vec2 microDerivative = windCombedMicroDerivative(
        vSurfacePositionPx,
        vDepth01
    );
    vec2 microSlope = microDerivative *
        (0.216 * uMicroNormalStrength * microWeight);
    vec2 continuousSlope = vSheenSlope + microSlope;

    vec3 normal = normalize(vec3(
        -continuousSlope.x,
        1.0,
        -continuousSlope.y
    ));
    vec3 viewDir = normalize(vec3(
        0.0,
        sin(uViewElevationRad),
        -cos(uViewElevationRad)
    ));
    // HDR transmission 保留既有 V·H Fresnel 标量，避免微法线把透射重新切成
    // 层内点状纹理；真实 N·V Fresnel 仍只用于低能量折射/Beer 分瓣。
    vec3 halfDirection = normalize(viewDir + lightDirection());
    float VdH = clamp(dot(viewDir, halfDirection), 0.0, 1.0);
    float transmissionFresnel = WATER_F0 + (1.0 - WATER_F0) *
        pow(1.0 - VdH, 5.0);

    vec3 linearColor = uSceneLinear ? vColor : srgbToLinear(vColor);
    if (vFrontFill == 0) {
        linearColor = applyRefractionAndBeer(
            linearColor,
            normal,
            viewDir
        );
        linearColor += linearColor * clamp(vDirectLight, 0.0, 0.020);
        // 厚度透光（体现象）在直射之后、旧 SSS 之前合成。
        if (uThicknessGlowStrength > 0.0001) {
            linearColor = thicknessGlow(linearColor, normal.x, 1.0);
        }
        if (uSunSssStrength > 0.0001) {
            linearColor = addSunriseSubsurface(linearColor);
        }
    }
    // 录音门控关闭时，即使浅色主体叠加直射/微法线/SDR SSS，也不得越过 reference white。
    // HDR 只由下面的录音增益门控局部 excess 开放。
    vec3 outLinear = boundedReferenceWhite(linearColor);
    if (uSceneLinear && vFrontFill == 0 && uHdrGain > 0.0001 &&
            uHdrHeadroom > 1.001 &&
            vHdrTransmissionPeak > 1.001) {
        float thicknessMask = uThicknessGlowStrength > 0.0001
            ? thicknessExcessMask(normal)
            : 0.0;
        outLinear += backlitTransmissionExcess(transmissionFresnel, thicknessMask);
        outLinear = min(outLinear, vec3(uHdrHeadroom));
    }
    float coverage = waterEdgeCoverage();
    outLinear = mix(edgeBehindBaseline(), outLinear, coverage);
    if (uSceneLinear) {
        fragColor = vec4(max(outLinear, vec3(0.0)), 1.0);
    } else {
        fragColor = vec4(clamp(linearToSrgb(outLinear), 0.0, 1.0), 1.0);
    }
}
