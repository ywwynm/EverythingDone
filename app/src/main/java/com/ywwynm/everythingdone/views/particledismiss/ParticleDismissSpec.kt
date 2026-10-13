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
    /** 容器整体浓度与材料 alpha 分开，不能让半透明容器把全部飘散粒子判为空。 */
    val sourceAlpha: Float = 1f,
    /** 滑动时固定一个局部消逝起点；未提供时仍使用全部随机起点。 */
    val releaseOrigin: ParticleReleaseOrigin? = null,
    /**
     * 消散的终点：非空时颗粒汇成流被吸进这一点，倒放时从这一点流出（碰一碰）。
     * 为空（其余所有动画）时运动与改动前逐位相同：着色器不编进这一段。
     */
    val sink: ParticleSink? = null,
    /**
     * 算运动用的位图（与 [snapshot] 同尺寸）：非空时材料按它建，前景贴图仍是 [snapshot]。接收时拼卡那一层
     * 用同样大小的代表色卡片算运动、只从真实卡片取颜色，与进度期间那一层（同样的代表色卡片）逐片重合。
     */
    val dynamics: Bitmap? = null,
    /** 光尘接手（`SINK_TARGETS`）：建材整形之后按它给出逐片终点与接手颜色。 */
    val handoff: ParticleHandoffSource? = null,
    /** 整层的不透明度由会话每帧给出（`LAYER_ALPHA`，见 [ParticleGestureProgress.layerAlpha]）。 */
    val layerAlpha: Boolean = false,
    /**
     * [snapshot] 只是占位（与真实快照同尺寸，材料按它建）：真实快照稍后由
     * [ParticleGestureProgress.supplyForeground] 交进来，会话算完历史之后等它、换上前景贴图才出第一帧。
     * 只用于按时间轴取帧的手势会话（碰一碰接收时拼卡那一层提前建）。
     */
    val deferForeground: Boolean = false,
    /**
     * 碰一碰接收：建完、按终点整形完的材料放进这里，供同一次接收的拼卡那一层沿用（进度期间那一层）。
     * 见 [ParticleMaterialShare]；其余动画恒为 null。
     */
    val publishMaterials: ParticleMaterialShare? = null,
    /**
     * 碰一碰接收：建材之前先核对这里有没有输入逐项相同、已经建好的材料，有就复制一份沿用（拼卡那一层提前建的那一次），
     * 结论记在里面。见 [ParticleMaterialShare]；其余动画恒为 null。
     */
    val adoptMaterials: ParticleMaterialShare.Adoption? = null,
    /**
     * 粒子样式（设置 → 粒子动画设置）。碰一碰用到的终点、接手、材料共享、整层不透明度、稍后交进的快照、
     * 单独的运动位图都是流体样式专有的，带了其中任何一项的规格固定流体。
     */
    val style: ParticleStyle = if (sink != null || handoff != null || publishMaterials != null ||
        adoptMaterials != null || deferForeground || layerAlpha || dynamics != null) ParticleStyle.FLOW
        else ParticleStyle.current
)

/** 光尘接手的结果：逐片终点（材料坐标 xy、种类、0）与接手颜色（sRGB、方式），各为每片 4 个 float。 */
internal class ParticleHandoff(val targets: FloatArray, val origins: FloatArray)

/**
 * 按建好、整形好的材料（[values]，每片 12 个 float）算光尘接手：挑出被接手的颗粒、改写它们的寿命，给出逐片终点
 * 与接手颜色。在建材线程调用；必须给出结果（没有一片被接手时两组全零），渲染器按规格编译了逐片终点。
 *
 * 材料坐标换到动画层像素：`x × scale + originXPx`。[visible] 标出出生处看得见的颗粒（为 null 时全部看得见）。
 */
internal fun interface ParticleHandoffSource {
    fun compute(values: FloatArray, sink: ParticleSink.Geometry, scale: Float, originXPx: Float, originYPx: Float,
                cardWidth: Float, cardHeight: Float, visible: BooleanArray?): ParticleHandoff
}
