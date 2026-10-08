// 我方绘制规则，不是镜像中缺失的粒子后端源码。
// 可见中心直径为外框的一半，外围只保留窄过渡，避免把大面积低覆盖率画成灰雾。
float driftCoverageAt(vec2 q) {
    return 1.0 - smoothstep(0.36, 0.64, length(q));
}

// 在一个工作面像素内取四个覆盖样本；小粒子跨越像素或退场时避免硬阈值闪烁。
float driftParticleCoverage(vec2 q, vec2 dx, vec2 dy) {
    vec2 x = dx * 0.25;
    vec2 y = dy * 0.25;
    return 0.25 * (driftCoverageAt(q - x - y) + driftCoverageAt(q + x - y) +
                   driftCoverageAt(q - x + y) + driftCoverageAt(q + x + y));
}
