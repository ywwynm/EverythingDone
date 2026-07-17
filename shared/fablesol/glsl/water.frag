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
in float vCrestRimWeight;
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
// 波峰银边（2026-07-16 夜）：剪影掠射镜面线。0 = 关闭 = 与既有输出逐位一致；
// uCrestRimWidthPx 为亮芯高斯宽度（物理 px），uCrestRimHaloAmp 为光晕幅度，
// uCrestRimPeakBoost 为 HDR 峰值增量（峰值−1）。Android 未上传时零默认关闭。
uniform float uCrestRimStrength;
uniform float uCrestRimWidthPx;
uniform float uCrestRimHaloAmp;
uniform float uCrestRimPeakBoost;
// 银丝滑动：调制场相位（物理 px，CPU 按半流速沿 sim 时间积分）、
// 噪声空间尺度（1/λ）与调制深度（0 = 关闭 = 无调制）。
uniform float uCrestRimSlidePhase;
uniform float uCrestRimSlideScale;
uniform float uCrestRimSlideDepth;
// 太阳柱（v17）：可见跨度起点与宽度（本地 px），供顶点高亮换算 x01。
uniform float uCrestRimSpanX0Px;
uniform float uCrestRimSpanPx;
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

// 波峰银边：剪影掠射镜面线。物理依据——剪影处 N·V→0、菲涅耳→1（近乎全反射
// 天空/太阳，颜色与水色无关故近白）；高光沿最小曲率方向（沿脊）拉长成细线，
// 横向宽度∝1/横向曲率（峰越尖线越细亮）；半角对准门 + 微法线扰动（normal.x
// 已含风梳微法线）带来沿边亮度涨落、间歇与随波"游动"。
// v16（2026-07-17 用户裁决）：剖面形状恒定——v15 的顶点线宽膨大 +
// 光晕铺展在细线上形成"打结"般的光斑，不好看。顶点强调只走能量
// （调用方按 apex01 做亮度渐变），线的粗细与光晕形状全程不变。
float crestRimProfile() {
    // 到本层上轮廓（自身剪影）的屏幕像素距离，与 waterEdgeCoverage 同源。
    float insideDistance = vFrontFill == 1
        ? -vDepth01
        : float(uStartLayer) / 8.0 - vDepth01;
    float pixelDepth = max(fwidth(vDepth01), 1e-6);
    float distancePx = max(insideDistance / pixelDepth, 0.0);
    // 空气透视变细（2026-07-17 三轮目测加陡）：远层银丝按层级权重收窄，
    // 近层最粗最明显、越远越纤细。
    float width = max(
        uCrestRimWidthPx * (0.45 + 0.55 * clamp(vCrestRimWeight, 0.0, 1.0)),
        0.5);
    float cutoff = width * 7.0;
    if (distancePx > cutoff) {
        return 0.0;
    }
    float core = exp(-0.5 * distancePx * distancePx / (width * width));
    // 晕幅经 uniform 可调（2026-07-17 用户定档基准 0.16）。
    float halo = uCrestRimHaloAmp * exp(-distancePx / (width * 3.2));
    // 平滑窗：晕尾在 cutoff 处连续归零——硬截止会在水体内部留下一条
    // 可见的等距边界（2026-07-17 首轮目测缺陷）。
    float window = 1.0 - smoothstep(width * 3.5, cutoff, distancePx);
    return min(core + halo, 1.0) * window;
}

// 太阳对准度原始值（0..1）：调用方须传平滑 sheen 坡度派生的 normal.x——
// 掺入风梳微法线会把线切碎成短虚线。
float crestRimSunAlignRaw(float normalX) {
    float sunSide = clamp(sin(uLightAzimuthRad) * 4.0, -1.0, 1.0);
    return clamp(0.5 + 1.1 * sunSide * normalX, 0.0, 1.0);
}

// 波峰包络（2026-07-17 v13 用户裁决）：方向门做主门会让银丝只挂单侧翼、
// 恰好错过波峰顶点。判据用**局部凸性**——"高出层均值"对宽缓涌包失效
// （均值被涌包本身抬走，t45 实测银丝整场消失）：y 向下时波峰 = y 局部
// 极小 → 沿 x 的坡度导数为正；配宽坡度窗覆盖两翼（顶点为中心、左右
// 完整），坡度窗外/凹段（波谷）落到 30% 底——长翼跑者不消失、
// 凸顶最亮。物理：唇线高光聚于剪影凸段（镜面沿最小曲率方向成线）。
// 波峰显著度（可为负）：vThicknessSurface 减去 D154 近层覆盖偏置后的
// 纯"高出层均值"量——平滑插值场，波谷为负、平段近零。
float crestRimProminence() {
    float nearBias = 0.45 * clamp(1.0 - vDepth01 * 4.0, 0.0, 1.0);
    return vThicknessSurface - nearBias;
}

float crestRimCrestGate() {
    // v18（2026-07-17 真机裁决"过渡突兀"）：凸性判据弃用 dFdx——插值
    // varying 的屏幕导数逐三角形恒定、跨列跳变（列宽 3~6px），亮度在
    // 相邻列间跳台阶。覆盖门只由显著度（平滑高度场，波谷负值天然排除）
    // 决定；坡度窗（wings）已删——陡坡把它的过渡带空间压缩成硬边，
    // 且与显著度职责重复。底 0.55：凸顶满强、其余过半亮度不硬裁。
    return 0.55 + 0.45 * smoothstep(0.0, 0.18, crestRimProminence());
}

// 亮结存在度（0..1）：银丝滑动的调制形态（2026-07-17 用户行为需求）。
// 物理依据——深水群速 = 相速/2，镜面事件包络随波群走、相对波峰恒向后
// 滑移（glint 逆流跑的同源现象）。正弦承载亮结（保证全动态范围——大
// seed 行的值噪声在 GPU sin() 大参数精度下整行偏平，实测 on/off 比值
// 恒 1 的根因）；小输入值噪声只做相位抖动，亮结间距/宽度有机化；seed
// 相位项让各层亮结互不同步。滑动关闭（depth=0）时恒 1。
float crestRimKnot01() {
    if (uCrestRimSlideDepth <= 0.0001) {
        return 1.0;
    }
    float u = (vSurfacePositionPx.x - uCrestRimSlidePhase) *
        uCrestRimSlideScale;
    float seed = clamp(vCrestRimWeight, 0.0, 1.0) * 3.7;
    float jitter = (valueNoiseDerivative(
        vec2(u * 0.53 + 3.7, seed)).x - 0.5) * 2.4;
    float wave = 0.5 + 0.5 * sin(6.2831853 * u + jitter + seed * 5.1);
    // v18：过渡带加宽（0.30~0.72 → 0.24~0.78），明暗过渡更绵长。
    return smoothstep(0.24, 0.78, wave);
}

// 太阳柱包络（v17，与 sun_glitter_policy.path_center01 同构）：顶点高亮
// 是**光滑波唇上的宏观镜面点**——光滑凸面每凸段至多一个镜面点，且只有
// 波峰位于太阳柱内所需坡度才可达；没有微面片（Cox–Munk）兜底，故比
// 闪点更严格地集中于柱内 → 每层自然 1~2 处（用户裁决"不是任何高波峰
// 都亮"的物理依据）。柱半宽取 0.15→0.07（窄于闪点的 0.24→0.11）。
// 银丝本体 = 天空掠射反射（宽光源），保持沿峰连续，不受柱限制。
float crestRimSunColumn() {
    float depth = clamp(vDepth01, 0.0, 1.0);
    float azimuth = clamp(uLightAzimuthRad, -0.9599, 0.9599);
    float center = clamp(
        0.5 + tan(azimuth) * 0.28 * (depth - 0.5), 0.18, 0.82);
    // v18 收窄（0.15→0.11、0.07→0.055）：配合显著度门，每层高亮 1~2 处。
    float halfWidth = mix(0.11, 0.055, depth);
    float x01 = clamp(
        (vSurfacePositionPx.x - uCrestRimSpanX0Px) /
            max(uCrestRimSpanPx, 1.0),
        0.0, 1.0);
    float d = (x01 - center) / halfWidth;
    return exp(-0.5 * d * d);
}

// 顶点度（0..1，连续场）：物理 = 凸顶镜面焦散——反射在坡度过零、
// 波峰显著处聚焦。v18 全平滑判据（弃 dFdx 阶跃）：坡度近零（宽过渡带
// 渐出）× 波峰显著度（波谷/平段天然排除）× 太阳柱包络（柱外无高亮）。
float crestRimApexMask() {
    float flatTop = 1.0 - smoothstep(0.03, 0.30, abs(vSheenSlope.x));
    float lifted = smoothstep(0.05, 0.35, crestRimProminence());
    return flatTop * lifted * crestRimSunColumn();
}

vec3 crestRimColor() {
    vec3 subsurfaceLinear = uSceneLinear
        ? vSubsurfaceColor
        : srgbToLinear(vSubsurfaceColor);
    float maximum = max(max(subsurfaceLinear.r, subsurfaceLinear.g),
        max(subsurfaceLinear.b, 0.001));
    // 近白、略带身份色相的镜面银——反射天空的银线不吃水体吸收。
    return mix(subsurfaceLinear / maximum, vec3(1.0), 0.72);
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
        // 迎光信号来自顶边复制的 sheen slope（未接线的平台为 0 → 中性 0.5）。
        vec3 fillNormal = normalize(
            vec3(-vSheenSlope.x, 1.0, -vSheenSlope.y)
        );
        // D155（2026-07-16 用户裁决，适当放宽 D6）：前景水体在水线下
        // uThicknessRangePx 内接受同一厚度透光，让第 0 层的透光可见；
        // vThickness01 随深度衰减为 0，大面积主体仍保持身份纯色。
        if (uThicknessGlowStrength > 0.0001) {
            frontLinear = thicknessGlow(frontLinear, fillNormal.x, 1.35);
        }
        // 波峰银边：第 0 层水线即其剪影。波峰包络做主门（顶点为中心、
        // 两翼完整覆盖），方向只做 ±28% 倾斜；HDR 超白仍锐方向门控；
        // 亮结掠过峰顶时叠加镜面焦散闪光（SDR+HDR 双路）。
        float fillRimSun = 0.0;
        float fillRim = 0.0;
        if (uCrestRimStrength > 0.0001) {
            float knot01 = crestRimKnot01();
            float apex01 = crestRimApexMask();
            // v16：粗细恒定，顶点强调只走亮度——顶端最亮、随 apex01
            // 场向两翼平滑衰减；亮结滑到顶点时自然到达最亮。
            float base = uCrestRimStrength * crestRimProfile() *
                clamp(vCrestRimWeight, 0.0, 1.0) * crestRimCrestGate();
            float body = base *
                mix(1.0 - uCrestRimSlideDepth, 1.0, knot01);
            float alignRaw = crestRimSunAlignRaw(fillNormal.x);
            fillRim = body * (0.80 + 0.20 * alignRaw) *
                (1.0 + 0.9 * apex01);
            fillRimSun = body *
                (smoothstep(0.40, 0.82, alignRaw) + 2.2 * apex01);
        }
        if (fillRim > 0.0001) {
            frontLinear += crestRimColor() * fillRim;
        }
        vec3 frontOutput = mix(
            edgeBehindBaseline(),
            boundedReferenceWhite(frontLinear),
            coverage
        );
        // 银边太阳对准段在录音态进入超白：峰值 = 1 + uCrestRimPeakBoost×
        // weight（默认档第 0 层 3.6 = 闪点核心同档）；随 coverage 收边。
        if (uSceneLinear && uHdrGain > 0.0001 && uHdrHeadroom > 1.001 &&
                fillRimSun > 0.0001) {
            frontOutput += crestRimColor() * (fillRimSun * uCrestRimPeakBoost *
                uHdrGain * coverage);
            frontOutput = min(frontOutput, vec3(uHdrHeadroom));
        }
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
    float crestRim = 0.0;
    float crestRimSun = 0.0;
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
        // 波峰银边（自身剪影的掠射镜面线），SDR 部分入参考白钳制。
        // 波峰包络做主门（v13：顶点为中心、两翼完整覆盖，方向门只留
        // ±28% 倾斜——方向做主门会让银丝只挂单侧、错过顶点）；
        // HDR 超白仍锐方向门控；亮结掠过峰顶时叠加镜面焦散闪光。
        // 迎光信号用平滑 sheen 坡度（不含微法线）。
        if (uCrestRimStrength > 0.0001) {
            vec3 rimNormal = normalize(
                vec3(-vSheenSlope.x, 1.0, -vSheenSlope.y)
            );
            float knot01 = crestRimKnot01();
            float apex01 = crestRimApexMask();
            // v16：粗细恒定，顶点强调只走亮度——顶端最亮、随 apex01
            // 场向两翼平滑衰减；亮结滑到顶点时自然到达最亮。
            float base = uCrestRimStrength * crestRimProfile() *
                clamp(vCrestRimWeight, 0.0, 1.0) * crestRimCrestGate();
            float body = base *
                mix(1.0 - uCrestRimSlideDepth, 1.0, knot01);
            float alignRaw = crestRimSunAlignRaw(rimNormal.x);
            crestRim = body * (0.80 + 0.20 * alignRaw) *
                (1.0 + 0.9 * apex01);
            crestRimSun = body *
                (smoothstep(0.40, 0.82, alignRaw) + 2.2 * apex01);
            if (crestRim > 0.0001) {
                linearColor += crestRimColor() * crestRim;
            }
        }
    }
    // 录音门控关闭时，即使浅色主体叠加直射/微法线/SDR SSS，也不得越过 reference white。
    // HDR 只由下面的录音增益门控局部 excess 开放。
    vec3 outLinear = boundedReferenceWhite(linearColor);
    if (uSceneLinear && vFrontFill == 0 && uHdrGain > 0.0001 &&
            uHdrHeadroom > 1.001) {
        if (vHdrTransmissionPeak > 1.001) {
            float thicknessMask = uThicknessGlowStrength > 0.0001
                ? thicknessExcessMask(normal)
                : 0.0;
            outLinear += backlitTransmissionExcess(transmissionFresnel, thicknessMask);
        }
        // 银边太阳对准段的掠射反射超白：峰值 = 1 + uCrestRimPeakBoost×
        // weight（默认档第 0 层 3.6 = 闪点核心同档），逐层随权重衰减守
        // 近层充足约定；与透射 excess 相互独立。
        if (crestRimSun > 0.0001) {
            outLinear += crestRimColor() *
                (crestRimSun * uCrestRimPeakBoost * uHdrGain);
        }
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
