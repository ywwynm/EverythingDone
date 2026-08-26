package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.PointF

/**
 * 一次粒子消散动画的全部输入，由 [ParticleDismissController] 组装，经
 * [ParticleDismissOverlay] 传给 [ParticleDismissRenderer]。
 */
internal class ParticleDismissSpec(
    val snapshot: Bitmap,
    /** 快照左上角在动画层（Activity DecorView）内的像素位置。 */
    val originXPx: Float,
    val originYPx: Float,
    /** 粒子网格步长（物理像素）。 */
    val cellPx: Float,
    /** 粒子漂移总距离基准（物理像素）。 */
    val driftPx: Float,
    /** 烟缕流场的空间尺度（物理像素）：决定缕/团的大小。 */
    val noiseScalePx: Float,
    /** 外扩羽流幅度（物理像素）：部分区段的边缘粒子向外推出、超过原 Dialog 宽度。 */
    val flarePx: Float,
    /** 收拢位移的绝对上限（物理像素）：宽 Dialog 边缘不被一口气拉向中轴。 */
    val pinchMaxPx: Float,
    /**
     * 虚拟远触点（动画层坐标）：触点沿"中心→触点"方向推远到约 1.1 倍快照
     * 对角线处。逐粒子主方向 = 指向此点，方向随粒子位置平滑渐变。
     */
    val virtualTouchXPx: Float,
    val virtualTouchYPx: Float,
    /** 波前扩散起点（快照 UV 坐标）。 */
    val waveOriginUv: PointF,
    /** 波前从起点扫到最远角的时长（逻辑秒）：凝聚模式用极小值弱化中心性。 */
    val spreadTime: Float,
    /** 逐粒子激活抖动上限（逻辑秒）：凝聚模式加大，凝实顺序随机化。 */
    val delayJitter: Float,
    /** 锋线低频扭曲幅度（逻辑秒）：凝聚模式加大，斑块状"缕缕"凝实。 */
    val waveWarp: Float,
    /** 系统动画时长缩放。 */
    val durationScale: Float,
    /**
     * 每次动画随机的噪声种子：平移全部噪声场（凝实/消散的斑块图案、烟缕、
     * 弯曲、浓淡每次全新）。静止层与粒子层共用同一种子保证擦除对齐。
     */
    val noiseSeedX: Float,
    val noiseSeedY: Float,
    /** 每次动画随机的逐粒子哈希种子（异或进 PCG 输入）。 */
    val hashSeed: Int,
    /**
     * 面板本体色（快照缩略众数，ARGB）：内容色权重的参照——与它色距大的
     * 粒子（文字、彩色控件）更大、更持久、真实更多，颜色本身不变。
     */
    val panelColor: Int,
    /**
     * 凝聚（出现动画）模式：非 null 时整条渲染管线做时间倒放——逻辑时钟从
     * 该值递减到 0（静止层逐格显现、粒子从散开态落回原位），末帧为完整原图。
     * null = 正常消散。
     */
    val condenseFromT: Float? = null,
    /** 凝聚动画的实际播放时长（逻辑秒，未含 durationScale）。 */
    val condenseDurationS: Float = 0f
)
