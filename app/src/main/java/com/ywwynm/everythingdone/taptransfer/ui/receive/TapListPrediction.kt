package com.ywwynm.everythingdone.taptransfer.ui.receive

import kotlin.math.max

/**
 * 进度期间预计来件卡片落在列表的哪里（第三版：颗粒从天线处朝那里飘）。碰上时已经知道：列表插入之前的样子
 * （置顶的几项多高、是否通栏）、来件的条数，以及握手里发送方告知的每张卡片的高宽比与是否通栏。按首页的插入
 * 与排列规则排一遍就是落点，与桌面模拟器 `sim_list.py` / `sim_emerge.predict_slots` 相同，列数不限于两列：
 *
 * - 收到的记事插在置顶之后，后收到的排在前面（新记事的 location 最大）；
 * - `StaggeredGridLayoutManager` 往下排时，普通卡片放进当前结束得最早的那一列，一样早放左边那列；通栏卡片
 *   放在各列都结束之后，之后各列从它的底边接着排；
 * - 每列宽 = 列表内容宽 / 列数（取整），卡片四周外边距 [Grid.margin]。
 *
 * 纯计算，坐标系由调用方定（接收用容器坐标）；矩形一律是 `[左, 上, 右, 下]`。
 */
internal object TapListPrediction {

    /** 发送方没有告知形状时（旧版）按这个高宽比估计。 */
    const val DEFAULT_ASPECT: Float = .8f

    /**
     * 列表的几何。[left] 是内容左缘（列表左缘 + 左内边距），[top] 是头部占位项的底边：第一张卡片的上沿
     * = [top] + [margin]。
     */
    class Grid(val left: Float, val contentWidth: Int, val spans: Int, val margin: Float, val top: Float) {
        init {
            require(spans >= 1 && contentWidth > 0) { "列数与内容宽须为正" }
        }

        /** `StaggeredGridLayoutManager` 的每列宽（像素，取整）。 */
        val spanWidth: Int = contentWidth / spans
        val cardWidth: Float get() = spanWidth - 2f * margin
        val fullWidth: Float get() = contentWidth - 2f * margin

        fun widthOf(full: Boolean): Float = if (full) fullWidth else cardWidth

        fun columnLeft(column: Int): Float = left + column * spanWidth + margin
    }

    /** 列表里的一项：高度（像素）与是否通栏。 */
    class Entry(val height: Float, val full: Boolean)

    /** 按列表顺序排 [entries]，从 [Grid.top] 往下；返回每一项的矩形。 */
    fun layout(grid: Grid, entries: List<Entry>): List<FloatArray> {
        val ends = FloatArray(grid.spans) { grid.top }
        return entries.map { entry ->
            if (entry.full) {
                val top = ends.max() + grid.margin
                val left = grid.left + grid.margin
                ends.fill(top + entry.height + grid.margin)
                floatArrayOf(left, top, left + grid.fullWidth, top + entry.height)
            } else {
                var column = 0
                for (k in 1 until grid.spans) {
                    if (ends[k] < ends[column]) column = k
                }
                val top = ends[column] + grid.margin
                val left = grid.columnLeft(column)
                ends[column] = top + entry.height + grid.margin
                floatArrayOf(left, top, left + grid.cardWidth, top + entry.height)
            }
        }
    }

    /**
     * 预计来件的落点，按收到的先后给出 [count] 个矩形。
     *
     * @param pinned 插入位置之前的各项（置顶的记事与文件夹），按列表顺序；为 null 表示它们不全在屏幕上、
     *        量不到——此时插入位置在屏幕下方，接收时列表会先滚过去，第一张卡片（列表顺序）的上沿落在
     *        [scrollTop]，按各列一齐从那里开始估计
     * @param aspects 来件卡片的高宽比（高 / 宽），[fullSpan] 同序；比 [count] 短时缺的按普通宽度、
     *        [DEFAULT_ASPECT] 估计
     * @param visibleTop 新卡片可以露出来的范围的上沿、[visibleBottom] 下沿：第一张卡片放不下时接收会把列表
     *        滚到它的上沿落在 [scrollTop]，预计的位置随之整体平移
     */
    fun predict(
        grid: Grid, pinned: List<Entry>?, aspects: FloatArray, fullSpan: BooleanArray, count: Int,
        visibleTop: Float, visibleBottom: Float, scrollTop: Float
    ): List<FloatArray> {
        if (count <= 0) return emptyList()
        val incoming = List(count) { k ->
            val full = k < fullSpan.size && fullSpan[k]
            val aspect = if (k < aspects.size && aspects[k] > 0f) aspects[k] else DEFAULT_ASPECT
            Entry(grid.widthOf(full) * aspect, full)
        }
        // 列表顺序：置顶的在前，然后是收到的（后收到的在前）
        val ordered = incoming.reversed()
        val placed = if (pinned != null) {
            layout(grid, pinned + ordered).drop(pinned.size)
        } else {
            layout(Grid(grid.left, grid.contentWidth, grid.spans, grid.margin, scrollTop - grid.margin), ordered)
        }
        // 第一张（列表顺序）没有完整露出来：接收时先把它滚到 scrollTop
        val first = placed.first()
        val dy = if (pinned != null && (first[1] < visibleTop || first[3] > visibleBottom)) scrollTop - first[1] else 0f
        return List(count) { k ->
            val rect = placed[count - 1 - k]
            floatArrayOf(rect[0], rect[1] + dy, rect[2], rect[3] + dy)
        }
    }

    /** 几个矩形的外接矩形；空列表时返回 null。 */
    fun union(rects: List<FloatArray>): FloatArray? {
        if (rects.isEmpty()) return null
        val out = rects.first().copyOf()
        for (r in rects) {
            out[0] = minOf(out[0], r[0]); out[1] = minOf(out[1], r[1])
            out[2] = max(out[2], r[2]); out[3] = max(out[3], r[3])
        }
        return out
    }
}
