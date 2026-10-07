package com.ywwynm.everythingdone.views.particledismiss

/**
 * 同一次碰一碰接收的两层粒子之间沿用建好的材料（`TapReceiveController`）。
 *
 * 进度期间那一层按预计落点上的代表色卡片建材；拼卡那一层提前建时按列表里的布局位置、同样大小的代表色卡片建材（占位
 * 快照）。两层共用一个种子，预计准确时两层建材的输入逐项相同，建出来的材料也就逐位相同，拼卡那一层不必再建一遍
 * （三星上约 0.6 秒）。
 *
 * 沿用的是「建好并按终点整形之后、光尘接手之前」的那一份：[ParticleMicroflakeModel.Materials] 与各片出生处看不看得见。
 * 光尘接手只属于拼卡那一层，它要改写被接手的颗粒的寿命，所以沿用方先复制一份值数组再交给接手去改，发布方的数组
 * 一个字节都不动（发布方的渲染器还在读它）。
 *
 * - 发布方（规格的 [ParticleDismissSpec.publishMaterials]）在建材线程建完、整形完之后 [publish]；
 * - 沿用方（规格的 [ParticleDismissSpec.adoptMaterials]）在建材线程建材之前按 [Key] 逐项核对，任何一项不同都照常建材，
 *   结论记在 [Adoption.result]。
 *
 * 这里只放 CPU 数组，不碰 GPU，没有显存要管：[clear] 之后交给垃圾回收即可。倒放要的整段历史不在这里沿用——光尘接手
 * 改了一部分颗粒的寿命与终点（积分着色器也是另一个变体），各片又经压力场互相影响，拼卡那一层的整段轨迹与进度期间
 * 那一层不同，必须重新积分（见 `docs/features/thing-transfer/decisions.md` 2026-09-28）。
 */
internal class ParticleMaterialShare {

    /**
     * 决定材料的全部输入：[ParticleMicroflakeRenderer.fromPixels] 里建材与按终点整形用到的每一项。前景贴图、画面尺寸与
     * 光尘接手不在其中：前两者不参与建材，光尘接手在沿用之后另算。
     */
    class Key(
        /** 算运动用的像素（ARGB）：规格给了 [ParticleDismissSpec.dynamics] 时是它，否则是快照。 */
        val pixels: IntArray,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val density: Float,
        /** 快照左上角（动画层像素）与消散方向的参照点（碰一碰是天线）。 */
        val originX: Float,
        val originY: Float,
        val touchX: Float,
        val touchY: Float,
        val seed: Int,
        val areaScale: Float,
        val releaseOrigin: ParticleReleaseOrigin?,
        /** 终点的全部取值（[ParticleSink.signature]）；没有终点时为 null。 */
        val sink: FloatArray?,
        /** 建材时用的微片尺寸下限（[ParticlePerfTuning.microflakeCellDpNow]）与是否走本地库。 */
        val cellDp: Float,
        val native: Boolean,
        val particleAmount: Float = 1f,
        val particleSizeScale: Float = 1f
    ) {
        /**
         * 与 [built]（已经建好的那一份）第一处不同的那一项；逐项相同时返回 null。浮点逐位比较，像素放在最后（最贵）。
         */
        fun mismatch(built: Key): String? {
            if (pixelWidth != built.pixelWidth || pixelHeight != built.pixelHeight) {
                return "快照尺寸 ${pixelWidth}×$pixelHeight，建好的是 ${built.pixelWidth}×${built.pixelHeight}"
            }
            if (!same(density, built.density)) return "密度 $density，建好的是 ${built.density}"
            if (!same(originX, built.originX) || !same(originY, built.originY)) {
                return "快照原点 ($originX, $originY)，建好的是 (${built.originX}, ${built.originY})"
            }
            if (!same(touchX, built.touchX) || !same(touchY, built.touchY)) {
                return "消散方向的参照点 ($touchX, $touchY)，建好的是 (${built.touchX}, ${built.touchY})"
            }
            if (seed != built.seed) return "种子不同"
            if (!same(areaScale, built.areaScale)) return "材料面积比例 $areaScale，建好的是 ${built.areaScale}"
            val origin = releaseOrigin
            val builtOrigin = built.releaseOrigin
            if ((origin == null) != (builtOrigin == null) || origin != null && builtOrigin != null &&
                (!same(origin.x, builtOrigin.x) || !same(origin.y, builtOrigin.y))) {
                return "剥离起点 $origin，建好的是 $builtOrigin"
            }
            if (!same(cellDp, built.cellDp)) return "微片尺寸设置 $cellDp dp，建好的是 ${built.cellDp} dp"
            if (!same(particleAmount, built.particleAmount)) return "粒子数量倍率 $particleAmount，建好的是 ${built.particleAmount}"
            if (!same(particleSizeScale, built.particleSizeScale)) return "粒子大小倍率 $particleSizeScale，建好的是 ${built.particleSizeScale}"
            if (native != built.native) return "建材实现不同（本地库 $native，建好的 ${built.native}）"
            sinkDifference(sink, built.sink)?.let { return it }
            return pixelDifference(pixels, built.pixels, pixelWidth)
        }

        private fun sinkDifference(mine: FloatArray?, built: FloatArray?): String? {
            if (mine == null && built == null) return null
            if (mine == null || built == null) return "有没有终点不同"
            if (mine.contentEquals(built)) return null
            val shorter = minOf(mine.size, built.size)
            val index = (0 until shorter).firstOrNull { mine[it].toRawBits() != built[it].toRawBits() } ?: shorter
            return "终点不同（${ParticleSink.signatureLabel(index)}）"
        }

        /** 像素逐个比较；不同时给出个数、范围与第一处（区分「边缘差了一像素」与「颜色不同」）。 */
        private fun pixelDifference(mine: IntArray, built: IntArray, width: Int): String? {
            if (mine === built || mine.contentEquals(built)) return null
            if (mine.size != built.size) return "算运动用的像素数 ${mine.size}，建好的是 ${built.size}"
            var count = 0
            var first = -1
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = -1
            var bottom = -1
            for (i in mine.indices) {
                if (mine[i] == built[i]) continue
                if (first < 0) first = i
                count++
                val x = i % width
                val y = i / width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            return "算运动用的像素有 $count 个不同（x $left–$right，y $top–$bottom；第一处 (${first % width}, " +
                "${first / width}) 是 #%08X，建好的是 #%08X）".format(mine[first], built[first])
        }

        private fun same(a: Float, b: Float) = a.toRawBits() == b.toRawBits()
    }

    /**
     * 沿用方这一层的结论（真机核对用）。[reason]：沿用时是「输入逐项相同」，否则是第一处不同的那一项；
     * [publishedSeconds] 是发布方建材与整形用了多久（没有可比的一份时为 0），[materialSeconds] 是这一层材料那一步
     * （沿用时是复制，否则是建材与整形），[handoffSeconds] 是这一层的光尘接手。单位都是秒。
     */
    class Result(
        val adopted: Boolean,
        val reason: String,
        val publishedSeconds: Double,
        val materialSeconds: Double,
        val handoffSeconds: Double
    )

    /**
     * 一层沿用方。每建一层新建一个、结论各记各的：先建的那一层撤掉之后，它的建材线程可能还在跑，不能改到后一层的结论。
     * [take] 与 [finish] 在同一条建材线程上先后调用。
     */
    class Adoption(private val share: ParticleMaterialShare) {

        /** 建材线程写一次、主线程读；这一层的建材还没走到时为 null。 */
        @Volatile var result: Result? = null
            private set

        private var reason = ""
        private var publishedSeconds = 0.0

        /** 输入与建好的那一份逐项相同时给出一份可以改写的材料（值数组是复制的），否则记下原因、返回 null。 */
        internal fun take(key: Key): Taken? {
            val entry = share.entry
            if (entry == null) {
                reason = if (share.cleared) "已经清掉了" else "进度期间那一层的材料还没建好"
                return null
            }
            publishedSeconds = entry.seconds
            key.mismatch(entry.key)?.let {
                reason = it
                return null
            }
            reason = "输入逐项相同"
            return Taken(entry.materials.copy(values = entry.materials.values.copyOf()), entry.visible)
        }

        internal fun finish(adopted: Boolean, materialSeconds: Double, handoffSeconds: Double) {
            result = Result(adopted, reason, publishedSeconds, materialSeconds, handoffSeconds)
        }
    }

    /** 沿用到的一份：[materials] 的值数组已经复制过，可以交给光尘接手改写；[visible] 只读。 */
    internal class Taken(val materials: ParticleMicroflakeModel.Materials, val visible: BooleanArray?)

    private class Entry(
        val key: Key,
        val materials: ParticleMicroflakeModel.Materials,
        val visible: BooleanArray?,
        val seconds: Double
    )

    @Volatile private var entry: Entry? = null
    @Volatile private var cleared = false

    /**
     * 建材线程：放进建好、整形好的材料（光尘接手之前）。此后 [materials] 与 [visible] 只读——沿用方复制之后才改。
     * 已经 [clear] 过（这一次接收结束了、发布方的建材才走完）时不收。
     */
    fun publish(key: Key, materials: ParticleMicroflakeModel.Materials, visible: BooleanArray?, seconds: Double) {
        if (cleared) return
        entry = Entry(key, materials, visible, seconds)
    }

    /** 主线程：这一次接收用不到了（拼卡那一层已经建好、接收失败、退出、下一台碰上）。放掉引用，之后也不再收。 */
    fun clear() {
        cleared = true
        entry = null
    }
}
