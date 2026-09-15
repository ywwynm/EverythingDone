package com.ywwynm.everythingdone.views.particledismiss

/**
 * 滑动完成记事的方向，以及三处随方向变号的量。
 *
 * 首页只有左滑是完成（右滑是开始做事），正在做事界面左右滑都是完成。两边共用同一套粒子
 * 消散，差别只在方向，因此把变号的部分集中在这里——它们都是纯算式，可以在 JVM 上定死，
 * 不必上真机逐个方向试。
 */
internal object ParticleSwipeDirection {

    /** 还没定向：只有左右都允许时才会出现这个中间状态。 */
    const val UNKNOWN = 0
    const val LEFT = -1
    const val RIGHT = 1

    /** 按位移取方向；恰好为零时保持未定向。 */
    fun of(displacement: Float): Int = when {
        displacement < 0f -> LEFT
        displacement > 0f -> RIGHT
        else -> UNKNOWN
    }

    /**
     * 会话内部一律按「向前推进」记进度（[ParticleGestureProgress.update] 取
     * `-displacement / width`），右滑因此要把位移镜像过来。
     */
    fun progressDisplacement(direction: Int, displacement: Float): Float =
        if (direction == RIGHT) -displacement else displacement

    /**
     * 消散方向由「快照中心指向虚拟触点」决定：触点放在卡片外侧 [reach] 处，粒子就朝手指
     * 滑走的那一侧飞出。[snapshotLeft] 是快照左上角在动画层里的横坐标。
     */
    fun virtualTouchX(direction: Int, snapshotLeft: Float, snapshotWidth: Int, reach: Float): Float =
        snapshotLeft + snapshotWidth / 2f + (if (direction == RIGHT) reach else -reach)

    /**
     * 完成之后把真实卡片停在屏外，避免删除动画重新把它露出来。未定向时按左滑处理：
     * 能走到这一步说明已经发生过真实滑动。
     */
    fun parkTranslationX(direction: Int, width: Int): Float =
        if (direction == RIGHT) width.toFloat() else -width.toFloat()
}
