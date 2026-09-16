package com.ywwynm.everythingdone.views.particledismiss

/**
 * 卡片被各级容器裁掉之后真正露出来的那一块，坐标以卡片自身左上角为原点。
 *
 * 正在做事界面把列表高度压到「时钟下方到按钮上方」这一段（见
 * `DoingActivity.getThingCardRegionMaxHeight`），比这一段高的卡片会被列表按一条直线截断，
 * 截断线以外的内容根本不在屏幕上。粒子快照抓的是整张卡片，不裁就会在接管的那一帧把被截掉
 * 的部分一并显示出来——观感是卡片突然长高。
 *
 * 截断是直线，而卡片本身是圆角：截在圆角以下时下沿是直角，正好截在圆角那一段时是半截圆角。
 * 圆角早已烤进快照（outline 之外是透明的），因此**按可见矩形裁快照**得到的就是这三种形状，
 * 不需要另外判断圆角。
 *
 * 一律按布局位置算，不看 translation 与 scale：滑动中的位移、按压反馈的缩放都不改变卡片静止
 * 时露出的形状，而粒子要复刻的正是那个形状。
 */
internal object ParticleVisibleBounds {

    fun whole(width: Int, height: Int): Bounds = Bounds(0, 0, width, height)

    /**
     * 并进一层容器的裁剪。[offsetX] / [offsetY] 是卡片左上角在该容器坐标系里的位置，
     * 容器自己的裁剪范围是 [clipLeft]..[clipRight] / [clipTop]..[clipBottom]（容器坐标系）。
     */
    fun clipBy(
        bounds: Bounds, offsetX: Int, offsetY: Int,
        clipLeft: Int, clipTop: Int, clipRight: Int, clipBottom: Int
    ): Bounds = Bounds(
        maxOf(bounds.left, clipLeft - offsetX),
        maxOf(bounds.top, clipTop - offsetY),
        minOf(bounds.right, clipRight - offsetX),
        minOf(bounds.bottom, clipBottom - offsetY))

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /** 一点都没露出来。此时不该抓快照，更不该按它裁。 */
        val isEmpty: Boolean get() = width <= 0 || height <= 0

        /** 整张卡片都露着，走原来那条不裁的路。 */
        fun covers(width: Int, height: Int): Boolean =
            left <= 0 && top <= 0 && right >= width && bottom >= height
    }
}
