package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap

/** 出现与消散共用的材料、几何和播放输入。 */
internal class ParticleDismissSpec(
    val snapshot: Bitmap,
    /** 快照左上角在动画层（Activity DecorView）内的像素位置。 */
    val originXPx: Float,
    val originYPx: Float,
    /** 按中心至该点求消散方向；出现沿同一轨迹倒序归入面板。 */
    val virtualTouchXPx: Float,
    val virtualTouchYPx: Float,
    val durationScale: Float,
    val hashSeed: Int,
    /** 为 true 时完整倒放当前共同消散模型。 */
    val reverse: Boolean = false,
    /** 当前出现与消散均使用一秒完整过程，另外应用系统动画时长缩放。 */
    val playbackDurationS: Float = ParticleMicroflakeModel.DURATION,
    val requestedAtNanos: Long = System.nanoTime(),
    /** 真实触点到控件边缘的距离，以短边为单位。 */
    val touchGap: Float? = null,
    /** 真实触点在可触摸背景中的相对远近；无触点使用中等强度。 */
    val touchStrength: Float? = null,
    /**
     * 快照需要的圆角半径（像素）；0 表示不裁角。>0 时遮罩在材料线程完成（取像素之前），
     * 主线程不再做这一遍全图软件绘制；承接快照的 ImageView 另按同一半径裁自己的轮廓，
     * 遮罩尚未落到位的那一两帧也不会露出直角（见 ParticleDismissOverlay）。
     */
    val roundedCornerRadiusPx: Float = 0f,
    /**
     * 出现动画的真实触点（动画层坐标，与 [virtualTouchXPx] 同一坐标系），创建页是新建按钮中心。
     * **无论 2b 的开关是否打开都记录**：关掉开关时它不参与材料输入，只留给对比工具重建两种输入。
     */
    val appearanceTouchXPx: Float? = null,
    val appearanceTouchYPx: Float? = null,
    /** 2c：本次出现允许跳过倒放尾段几乎无粒子的部分（当前只有创建页传 true）。 */
    val skipEmptyTail: Boolean = false
)
