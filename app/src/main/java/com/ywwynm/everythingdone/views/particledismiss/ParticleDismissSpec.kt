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
    /**
     * 2c：本次出现跳过倒放尾段几乎无粒子的部分。
     *
     * 第四批起弹窗、页内面板与新卡片的出现也走这条路，取值在 `appearContent` 那一层就按
     * [ParticlePerfTuning.creationAppearanceSkipEmptyTail] / [ParticlePerfTuning.dialogAppearanceSkipEmptyTail]
     * 解析完，会话只看这里的结论。
     */
    val skipEmptyTail: Boolean = false,
    /**
     * 本次出现是不是创建页（新建记事入场）。
     *
     * 创建页是唯一一条「快照可以提前预测、轨迹可以提前算」的路径（第四批 ②），也是
     * 2b 真实触点与对比帧工具的唯一作用对象；观察器与 `ParticleAppearanceFrames` 按它区分，
     * 不再靠 [skipEmptyTail] 反推（那一项现在弹窗也为 true）。
     */
    val creationPage: Boolean = false,
    /**
     * 第五批：这一屏对应的缓存键（只有创建页、且经 `ThingCreationTransition.launch` 进来时非空）。
     *
     * 它就是「点新建按钮那一刻的环境指纹」，也正是这一屏被拍下来时的环境。
     * [ParticleCreationPrewarm.remember] 拿它把首屏落盘，冷启动之后按键读回来当预测素材。
     */
    val prewarmKey: ParticleCreationKey? = null,
    /** 卡片及半透明阴影需同步交接；Android 14 起走 Surface 事务，旧系统在窗口内合成。 */
    val inWindow: Boolean = false,
    /** 扩展阴影边界后的面积比例，用于保持卡片本体的粒子密度。 */
    val materialAreaScale: Float = 1f,
    /** 滑动时固定一个局部消逝起点；未提供时仍使用全部随机起点。 */
    val releaseOrigin: ParticleReleaseOrigin? = null
)
