package com.ywwynm.everythingdone.views.particledismiss

/**
 * 逐帧成本专项第二阶段的**对照条件表**。离屏墙钟 A/B（[ParticleSegmentBench]）、
 * 逐位/逐通道对照（[ParticleTrajectoryCheck]）与对比帧（[ParticleOptionFrames]）共用同一张表，
 * 免得三处各写一份、名字对不上。
 *
 * 每个条件给出**两侧各自的开关掩码**，而不是「相对默认值的一位」：
 * - 绝大多数项的基线是「这一位关掉、其余按默认」，对照侧是「这一位打开」；
 * - U10a 的基线是 **U4 单开**（它建立在 U4 之上，随机量在 U4 之后片元侧一个都不用了）；
 * - 归因项（B1/B2/B3、单格取样探针）与普通项同一形状，只是默认值一直是关。
 *
 * 名字直接进产物，分析脚本按它取数，不要随意改。
 */
internal object ParticlePhase2Conditions {

    class Condition(val name: String, val baseOn: Int, val baseOff: Int,
                    val testOn: Int, val testOff: Int,
                    /** 画面是否**应当**逐位相同；只用于报告里标注期望，不作断言。 */
                    val expectIdentical: Boolean,
                    /** 会改动 [ParticleMicroflakeRenderer.Shared] 的格式，不能与基线交错跑。 */
                    val sharedState: Boolean = false)

    private fun item(name: String, bit: Int, expectIdentical: Boolean = false,
                     sharedState: Boolean = false) =
        Condition(name, 0, bit, bit, 0, expectIdentical, sharedState)

    private val T = ParticlePerfTuning

    val ALL: List<Condition> = listOf(
        // ---- 批 A：普适、零视觉风险 ----
        item("u1-barriers", T.P2_NARROW_BARRIERS, expectIdentical = true),
        item("u6-shared-half", T.P2_SHARED_HALF, expectIdentical = true),
        item("m1-local-size", T.P2_MALI_LOCAL_SIZE, expectIdentical = true),
        // ---- 批 B：普适 G1 ----
        item("u2-merged-field", T.P2_MERGED_FIELD),
        item("u2b-mediump-sampler", T.P2_MEDIUMP_SAMPLER),
        item("u3-peel-half", T.P2_PEEL_FIELD_HALF),
        item("u5-mediump-varying", T.P2_MEDIUMP_VARYING),
        item("u4-flake-constants", T.P2_FLAKE_CONSTANTS),
        Condition("u10a-single-buffer", T.P2_FLAKE_CONSTANTS, T.P2_SINGLE_INSTANCE_BUFFER,
            T.P2_FLAKE_CONSTANTS or T.P2_SINGLE_INSTANCE_BUFFER, 0, expectIdentical = true),
        item("a1-mediump-alu", T.P2_MEDIUMP_ALU),
        // ---- 批 C：分厂商压力求解（B2 / B3 同时是第一步的归因对照） ----
        item("m2-region-plain", T.P2_REGION_PLAIN_JACOBI, expectIdentical = true),
        item("m3-plain-jacobi", T.P2_PLAIN_JACOBI_ON_MALI, expectIdentical = true),
        item("m4-clear-region", T.P2_CLEAR_ACTIVE_REGION, expectIdentical = true),
        // ---- 批 D：V 级，本轮只出图与数字 ----
        item("u7-srgb8", T.P2_SRGB8_ACCUMULATION, sharedState = true),
        item("u8-nearest-splat", T.P2_NEAREST_SPLAT),
        item("u10-hw-trilinear", T.P2_HARDWARE_TRILINEAR),
        // ---- 归因专用：那 32 次三维取样占积分段多少 ----
        item("probe-single-cell", T.P2_PROBE_SINGLE_CELL),
        // B2 的**公平**版本：逐次 Jacobi 必须同时接上活动区域才与批处理可比
        // （只开 M3 时逐次路径跑的是整网格 × 100 次，那是不对等的对照）。
        Condition("m3-plain-jacobi-region", 0,
            T.P2_PLAIN_JACOBI_ON_MALI or T.P2_REGION_PLAIN_JACOBI,
            T.P2_PLAIN_JACOBI_ON_MALI or T.P2_REGION_PLAIN_JACOBI, 0, expectIdentical = true),
        // 批 A 三项一起：逐项都在噪声边缘时，合起来的量级才看得出来。
        Condition("batch-a", 0,
            T.P2_NARROW_BARRIERS or T.P2_SHARED_HALF or T.P2_MALI_LOCAL_SIZE,
            T.P2_NARROW_BARRIERS or T.P2_SHARED_HALF or T.P2_MALI_LOCAL_SIZE, 0,
            expectIdentical = true),
        // 批 B 里所有不改画面口径的那几项一起（U4 + U10a + U5 + A1）。
        Condition("batch-b-draw", 0,
            T.P2_FLAKE_CONSTANTS or T.P2_SINGLE_INSTANCE_BUFFER or T.P2_MEDIUMP_VARYING or T.P2_MEDIUMP_ALU,
            T.P2_FLAKE_CONSTANTS or T.P2_SINGLE_INSTANCE_BUFFER or T.P2_MEDIUMP_VARYING or T.P2_MEDIUMP_ALU,
            0, expectIdentical = false))

    fun byName(names: Collection<String>): List<Condition> =
        if (names.isEmpty()) ALL else ALL.filter { it.name in names }
}
