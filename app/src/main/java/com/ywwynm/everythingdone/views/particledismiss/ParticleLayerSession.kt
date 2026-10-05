package com.ywwynm.everythingdone.views.particledismiss

/**
 * 窗口表面（[ParticleWindowSurface]）与动画层（[ParticleDismissOverlay]）驱动一次粒子会话所用的方法。
 *
 * 两种粒子样式各有一个实现：流体（[ParticleSession]，微片 + 压力求解，原有实现）与飘散
 * （[ParticleDriftSession]，按华为 `GEParticleAblationFilter` 源码逐项移植的消蚀 + 风与湍流）。
 * 抓图、表面、节拍、交接都与样式无关，只有会话内部的准备与绘制不同。
 *
 * 除 [cancel]、[setVisible]、[release] 外，所有方法都只在 GL 线程调用。
 */
internal interface ParticleLayerSession {
    val spec: ParticleDismissSpec

    val cancelled: Boolean

    /** 左滑在识别之前不显示粒子；手势收尾时先恢复真实卡片再撤本层。 */
    val visible: Boolean

    /** 已经开始占用 GPU 资源；共享资源尺寸只在没有会话开工时才允许变。 */
    val started: Boolean

    /** 正在逐帧出画（含末帧驻留）。 */
    val presenting: Boolean

    /** 快照像素已全部进入 GPU，主线程可以回收它。 */
    val snapshotConsumed: Boolean

    /** 卡片本体完全消失时的进度（0–1）。 */
    val bodyGoneProgress: Float

    /** 是否需要流体样式的共享资源（累积缓冲、流场、程序）。飘散会话不需要，窗口据此决定要不要建它们。 */
    val needsMicroflakeShared: Boolean

    fun setVisible(value: Boolean)

    /** 本会话是否需要节拍继续跑（与「这一拍出不出帧」分开）。 */
    fun needsBeat(): Boolean

    /** 本拍是否需要窗口出一帧。 */
    fun wantsFrame(): Boolean

    /** 首帧已经备好、还没提交过：这一帧不必等下一次节拍。 */
    fun wantsFirstFrame(): Boolean

    /** 会话还需要 pump 推进（准备、预计算、手势预读）。 */
    fun needsWork(): Boolean

    /** 取证：出拍了而这一层没画时记下原因（debug）。 */
    fun traceSkip(target: Long)

    /** 推进一步准备；返回是否有进展。[shared] 只在 [needsMicroflakeShared] 为真时保证非空。 */
    fun advanceOnce(shared: ParticleMicroflakeRenderer.Shared?): Boolean

    /** 把本会话这一拍的画面叠加到已经清空的默认帧缓冲上；返回 false 表示这一拍没有内容。 */
    fun drawInto(target: Long): Boolean

    /** 窗口 `eglSwapBuffers` 返回之后调用。 */
    fun frameSubmitted(target: Long)

    /** 该帧确认上屏；[displayedAt] 是驱动报的真实呈现时刻，拿不到时为 null。 */
    fun framePresented(target: Long, displayedAt: Long? = null)

    fun cancel()

    /** 宿主确认真实内容上屏后调用：退出并让窗口把这一层抹掉。 */
    fun release()

    /** 释放本会话的 GPU 对象；共享资源不动。只在 GL 线程调用。 */
    fun dispose()

    /** 回调都在 GL 线程触发，实现方自己切主线程。 */
    interface Listener {
        /** 第一帧确认上屏：撤真实弹窗、撤快照、启动暗层都挂在这一刻。 */
        fun onFirstFramePresented()
        /** 每一帧确认上屏：目标呈现时刻 + 该帧进度，用于暗层跟随与手势完成判定。 */
        fun onPresented(target: Long, progress: Float)
        /** 正常播完（true）或失败/中止（false）。 */
        fun onFinished(completed: Boolean)
    }
}
