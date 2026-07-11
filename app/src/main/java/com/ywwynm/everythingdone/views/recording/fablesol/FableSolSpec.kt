package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 布局与网格规格常量（对应 audioVisualizerSimulatorFable 的 spec.py）。
 *
 * dp 为设计单位；渲染时 px = dp × density（Android 实际 displayMetrics.density，
 * 取代原版固定 DENSITY=2）。模拟网格以容器中心为原点的 u 轴（重力系水面方向），
 * 覆盖任意倾角下的最大湿润跨度 + 画外余量。
 */
object FableSolSpec {
    // Python 版 320dp 只作为网格采样间距和 View 尚未测量时的回退基准；
    // Android 运行时容器宽度来自 WaveVisualizerFableSol.onSizeChanged 的最终实测宽度。
    const val REFERENCE_WIDTH_DP = 320.0
    const val HEIGHT_DP = 420.0

    const val N_LAYERS = 9
    const val DEEP_LAYER_START = 7   // 深两层“无动于衷”（D16 乐队分层）：只随长积分与段落慢变

    const val VISIBLE_COLS = 96
    const val N_POINTS = 216
    const val MARGIN_DP = 80.0                 // 正常模式海绵区纵深

    const val PHYSICS_HZ = 120
    const val PHYSICS_DT = 1.0 / 120.0
    const val FPS = 60

    const val FLOW_DIR = -1.0                  // 默认流向：右→左

    val DX_DP = REFERENCE_WIDTH_DP / VISIBLE_COLS        // ≈3.333 dp
    val U_HALF_DP = (N_POINTS - 1) / 2.0 * DX_DP   // ≈358 dp
}
