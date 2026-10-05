// 飘散样式（华为 GEParticleAblationFilter 逐项移植）的公共部分：索引哈希与消蚀噪声。
// 来源：libgraphics_effect_ext.z.so 文件偏移 0x411e9（运动着色器）与 0x48b8a（消蚀着色器），
// 桌面实现 docs/features/dialog-particle-dismiss/tuning-0909-gpt6/harmony_native.py。

// 源码运动着色器的 hash / getRandomValue，逐位相同。
uint driftHash(uint x) {
    x ^= x >> 15;
    x *= 0x85ebca6bu;
    x ^= x >> 13;
    x *= 0xc2b2ae35u;
    x ^= x >> 16;
    return x;
}

float driftRandom(float mn, float mx, uint seed) {
    return mn + float(driftHash(seed)) / float(0xFFFFFFFFu) * (mx - mn);
}

// 消蚀噪声的格点值由 CPU 给出（样式库或随机），不在着色器里用源码的 sin 哈希：
// 各家 GPU 的 sin 精度不同，同一个随机数会画出不同的图案。
uniform int u_latticeW;
uniform int u_latticeH;
uniform float u_lattice[64];

float driftLattice(vec2 p) {
    ivec2 q = clamp(ivec2(p), ivec2(0), ivec2(u_latticeW - 1, u_latticeH - 1));
    return u_lattice[q.y * u_latticeW + q.x];
}

// 源码消蚀着色器的 noise()：平滑插值的值噪声。
float driftNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(driftLattice(i + vec2(0.0, 0.0)), driftLattice(i + vec2(1.0, 0.0)), f.x),
               mix(driftLattice(i + vec2(0.0, 1.0)), driftLattice(i + vec2(1.0, 1.0)), f.x), f.y);
}

// 快照像素 px（整数坐标，像素中心在 +0.5）处的噪声值 n = noise × 0.8 + 0.2；u_scaleU = 205 / (W·H) + 0.002。
uniform float u_scaleU;

float driftMaskValue(ivec2 px) {
    return driftNoise((vec2(px) + 0.5) * u_scaleU) * 0.8 + 0.2;
}

// 源码：threshold = iTime × rate，iTime = T / 2（HDS 进度动画 2 秒），rate = 2.1；alpha = (n − threshold) / 0.15。
float driftMaskAlpha(float n, float deviceTime) {
    float diff = n - min(deviceTime * 0.5, 1.0) * 2.1;
    if (diff <= 0.0) return 0.0;
    if (diff < 0.15) return diff / 0.15;
    return 1.0;
}
