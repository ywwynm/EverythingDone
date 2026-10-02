package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.taptransfer.ui.particles.TapDustBand
import com.ywwynm.everythingdone.views.particledismiss.ParticleHandoff
import com.ywwynm.everythingdone.views.particledismiss.ParticleHandoffSource
import com.ywwynm.everythingdone.views.particledismiss.ParticleSink
import java.util.SplittableRandom
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 光尘接手（第三版，与模拟器 `sim_handoff.compute` 相同）：拼卡那一层流向四周的颗粒里，剥离得早的那几片由
 * 扩散进来的光尘就近接手——在流入开始之后那一小段里，从那片光尘此刻的位置出现（那片光尘同时不再画），颜色
 * 从光尘的颜色过渡到卡片上的颜色。就近配对是局部的，不会把卡片上连成一片的区域拆散。只挑正向剥离得早的颗粒
 * （飞行时间够长），否则它们要在一瞬间飞过半个屏幕。
 *
 * 在建材线程上调用。流入开始的时刻那时还不知道，按预计的时刻（[inflow]）算；实际早了或晚了，交接的那片光尘
 * 多走或少走一小段（每秒几十 dp），看不出来。光尘隐去按倒放走到哪儿判定（[hideTau]），不按时刻，两边始终同步。
 *
 * 拼卡那一层沿用进度期间那一层的材料之后，这一步就是它建材阶段的全部，直接压在流入开始之前（三星上约 0.55 秒，
 * 2026-09-28）。几处写法因此按耗时排过，结果与逐项照搬模拟器的写法逐位相同：挑颗粒时先查便宜的条件；候选光尘先
 * 只求相位、过了筛才求位置；就近配对的分桶用原始数组。
 *
 * @param stageLeft 舞台在动画层里的左上角：光尘在舞台坐标里，材料换到动画层像素
 * @param stageOriginMs 舞台时钟的起点（`AnimationUtils` 的毫秒）
 * @param touchedS 碰上的时刻、[completeS] 数据到齐的时刻（秒，与 [inflow] 同一个时钟）
 * @param palette 交接时光尘的调色板（收到的记事的颜色），[paletteCycleS] 转一圈的秒数
 */
internal class TapDustHandoff(
    private val band: TapDustBand,
    private val inflow: TapReceiveTimeline.Inflow,
    private val stageLeft: Float,
    private val stageTop: Float,
    private val stageOriginMs: Long,
    private val touchedS: Double,
    private val completeS: Double,
    private val palette: IntArray,
    private val paletteCycleS: Float,
    /** receive_dust_join：交接之后倒放的起、止（时间轴），只挑正向飞行时间不短于第三项的颗粒。 */
    private val join: FloatArray,
    private val density: Float,
    private val seed: Long = 7L
) : ParticleHandoffSource {

    /** 被接手的光尘各自在倒放走到哪儿之后不再画（没被接手的是正无穷）；算完之前为 null。 */
    @Volatile var hideTau: FloatArray? = null
        private set

    override fun compute(
        values: FloatArray, sink: ParticleSink.Geometry, scale: Float, originXPx: Float, originYPx: Float,
        cardWidth: Float, cardHeight: Float, visible: BooleanArray?
    ): ParticleHandoff {
        val n = values.size / 12
        val started = System.nanoTime()
        val marks = LongArray(5)
        val targets = FloatArray(n * 4)
        val origins = FloatArray(n * 4)
        val random = SplittableRandom(seed)
        val tauH = inflow.tauH
        val lo = join[0]
        val hi = join[1]
        val minFlight = join[2]
        // 正向寿命的终点挪到这一段里：倒放里它们在流入开始之后 [lo, hi] 这一小段出现
        val deathHi = 1f - tauH - lo
        val deathLo = 1f - tauH - hi

        // ---- 流向四周、看得见、剥离得早的那几片。三个条件互不影响：先查便宜的两项，流向要找这一片属于哪张卡片，
        // 放在最后（十几万片里只有剥离得早的那一小部分走到这一步）
        val earliest = deathLo - minFlight
        var eligible = IntArray(1024)
        var eligibleCount = 0
        for (i in 0 until n) {
            val p = i * 12
            if (values[p + 2] <= earliest && (visible == null || visible[i]) &&
                ParticleSink.frameBound(values, p, sink)) {
                if (eligibleCount == eligible.size) eligible = eligible.copyOf(eligibleCount * 2)
                eligible[eligibleCount++] = i
            }
        }
        marks[0] = System.nanoTime()
        val dustCount = band.count
        val hide = FloatArray(dustCount) { Float.POSITIVE_INFINITY }
        var pairs = 0
        var candidateCount = 0
        if (eligibleCount > 0 && dustCount > 0) {
            val evaluator = band.Evaluator()
            val at = FloatArray(2)
            // 流入开始那一刻的光尘：扩散出去（离开边缘最外那一层）、且还看得见的那些当候选。先只求跨带相位与出场
            // 比例（不透明度只取决于这两项），过了筛的才求位置（噪声取点占大头）。出场比例为 0 时不透明度为 0，
            // 筛不过，与整个求值之后再筛的结果相同
            val tIn = inflow.startS
            val clockIn = stageClock(tIn)
            val spreadIn = spreadAt(tIn)
            val diffuseIn = diffuseAt(tIn)
            val candidates = IntArray(dustCount)
            val candidateX = FloatArray(dustCount)
            val candidateY = FloatArray(dustCount)
            for (j in 0 until dustCount) {
                val weight = evaluator.phase(j, spreadIn, diffuseIn)
                if (evaluator.q.pow(1.6f) > .12f && weight > .25f) {
                    evaluator.position(j, clockIn, spreadIn, 0f, at)
                    candidates[candidateCount++] = j
                    candidateX[j] = at[0] + stageLeft
                    candidateY[j] = at[1] + stageTop
                }
            }
            shuffle(candidates, candidateCount, random)
            marks[1] = System.nanoTime()
            // 候选颗粒的默认落点（动画层像素），按格子分桶找最近的
            val radius = 48f * density
            val target = FloatArray(2)
            val ex = FloatArray(eligibleCount)
            val ey = FloatArray(eligibleCount)
            for (k in 0 until eligibleCount) {
                ParticleSink.fieldTarget(values, eligible[k] * 12, sink, cardWidth, cardHeight, target)
                ex[k] = target[0] * scale + originXPx
                ey[k] = target[1] * scale + originYPx
            }
            val grid = Buckets(radius, ex, ey)
            marks[2] = System.nanoTime()
            val chosenFlake = IntArray(eligibleCount) { -1 }
            for (c in 0 until candidateCount) {
                val j = candidates[c]
                val k = grid.takeNearest(candidateX[j], candidateY[j])
                if (k < 0) continue
                chosenFlake[k] = j
                pairs++
            }
            marks[3] = System.nanoTime()
            // ---- 配上的颗粒：改寿命、落点取那片光尘在交接时刻的位置，颜色取那片光尘的颜色
            val turnOrigin = stageOriginMs / 1000.0
            for (k in 0 until eligibleCount) {
                val j = chosenFlake[k]
                if (j < 0) continue
                val i = eligible[k]
                val p = i * 12
                val death = deathLo + (deathHi - deathLo) * random.nextDouble().toFloat()
                values[p + 6] = death - values[p + 2]
                val th = inflow.timeOfTau(1f - death)
                evaluator.evaluate(j, stageClock(th), spreadAt(th), diffuseAt(th), 0f, at)
                val x = at[0] + stageLeft
                val y = at[1] + stageTop
                targets[i * 4] = (x - originXPx) / scale
                targets[i * 4 + 1] = (y - originYPx) / scale
                targets[i * 4 + 2] = KIND_DUST
                val color = evaluator.color(j, at[0], at[1], palette, ((th - turnOrigin) / paletteCycleS).toFloat())
                origins[i * 4] = ((color shr 16) and 0xFF) / 255f
                origins[i * 4 + 1] = ((color shr 8) and 0xFF) / 255f
                origins[i * 4 + 2] = (color and 0xFF) / 255f
                origins[i * 4 + 3] = 1f
                hide[j] = 1f - death
            }
            marks[4] = System.nanoTime()
        }
        if (BuildConfig.DEBUG) {
            // 分段耗时（毫秒）：挑可接手的颗粒、求候选光尘、落点与分桶、就近配对、改写配上的颗粒
            val ms = LongArray(5) { k ->
                if (marks[k] == 0L) 0L else (marks[k] - (if (k == 0) started else marks[k - 1])) / 1_000_000
            }
            Log.i(TAG, "光尘接手 颗粒=$n 可接手=$eligibleCount 光尘=$dustCount 候选光尘=$candidateCount 配上=$pairs " +
                "tauH=$tauH 分段毫秒=${ms.joinToString("/")} 共=${(System.nanoTime() - started) / 1_000_000}")
        }
        hideTau = hide
        return ParticleHandoff(targets, origins)
    }

    /** 舞台时钟（秒）：光尘按它求值。 */
    private fun stageClock(atS: Double): Float = (atS - stageOriginMs / 1000.0).toFloat()

    /** 流入之后光尘的扩散程度：数据早已到齐，进度按 1 算（`TapReceiveTimeline.spread`）。 */
    private fun spreadAt(atS: Double): Float {
        if (completeS.isNaN()) return 1f
        return 1f + min(((atS - completeS) * .25).toFloat().coerceAtLeast(0f), .5f)
    }

    private fun diffuseAt(atS: Double): Float = (atS - touchedS).toFloat().coerceAtLeast(0f)

    /**
     * 按格子分桶的最近邻：格宽等于搜索半径，只看周围 3 × 3 格。各点按格子排进一个数组（同一格里仍是加入的先后），
     * 格子的访问次序、格内次序都与模拟器逐个加进散列表的写法相同，距离相等时取后看到的那一项，结果逐位相同。
     * 取走的点记下，整格都取完了就不再逐个看。
     *
     * 格子太多（落点散得离谱，正常不会发生）时退回散列表，结果仍相同。
     */
    private class Buckets(private val cell: Float, private val xs: FloatArray, private val ys: FloatArray) {
        private val used = BooleanArray(xs.size)
        private val cellOf = IntArray(xs.size)
        private var minX = 0
        private var minY = 0
        private var columns = 0
        private var rows = 0
        private var starts = IntArray(1)
        private var order = IntArray(0)
        private var left = IntArray(0)
        private var sparse: HashMap<Long, MutableList<Int>>? = null

        init {
            val n = xs.size
            val cx = IntArray(n)
            val cy = IntArray(n)
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var y1 = Int.MIN_VALUE
            for (k in 0 until n) {
                cx[k] = floor(xs[k] / cell).toInt()
                cy[k] = floor(ys[k] / cell).toInt()
                x0 = min(x0, cx[k]); x1 = max(x1, cx[k])
                y0 = min(y0, cy[k]); y1 = max(y1, cy[k])
            }
            val span = if (n == 0) 0L else (x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1)
            if (span > MAX_CELLS) {
                val map = HashMap<Long, MutableList<Int>>()
                for (k in 0 until n) map.getOrPut(key(cx[k], cy[k])) { ArrayList() }.add(k)
                sparse = map
            } else if (n > 0) {
                minX = x0
                minY = y0
                columns = x1 - x0 + 1
                rows = y1 - y0 + 1
                val cells = columns * rows
                // 计数排序：先数每格有几项，前缀和即各格的起点；按下标从小到大放进去，格内保持加入的先后
                starts = IntArray(cells + 1)
                for (k in 0 until n) {
                    cellOf[k] = (cx[k] - minX) * rows + (cy[k] - minY)
                    starts[cellOf[k] + 1]++
                }
                for (c in 1..cells) starts[c] += starts[c - 1]
                left = IntArray(cells) { c -> starts[c + 1] - starts[c] }
                val cursor = starts.copyOf()
                order = IntArray(n)
                for (k in 0 until n) order[cursor[cellOf[k]]++] = k
            }
        }

        private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        /** 半径 [cell] 以内、还没取走的最近一项，取走并返回它；没有时返回 -1。 */
        fun takeNearest(x: Float, y: Float): Int {
            val cx = floor(x / cell).toInt()
            val cy = floor(y / cell).toInt()
            var best = -1
            var bestDistance = cell * cell
            val map = sparse
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (map != null) {
                        val list = map[key(cx + dx, cy + dy)] ?: continue
                        for (k in list) {
                            if (used[k]) continue
                            val ddx = xs[k] - x
                            val ddy = ys[k] - y
                            val d = ddx * ddx + ddy * ddy
                            if (d <= bestDistance) {
                                bestDistance = d
                                best = k
                            }
                        }
                        continue
                    }
                    val column = cx.toLong() + dx - minX
                    val row = cy.toLong() + dy - minY
                    if (column < 0 || column >= columns || row < 0 || row >= rows) continue
                    val c = column.toInt() * rows + row.toInt()
                    if (left[c] == 0) continue
                    for (s in starts[c] until starts[c + 1]) {
                        val k = order[s]
                        if (used[k]) continue
                        val ddx = xs[k] - x
                        val ddy = ys[k] - y
                        val d = ddx * ddx + ddy * ddy
                        if (d <= bestDistance) {
                            bestDistance = d
                            best = k
                        }
                    }
                }
            }
            if (best >= 0) {
                used[best] = true
                if (map == null) left[cellOf[best]]--
            }
            return best
        }
    }

    companion object {
        private const val TAG = "TapDustHandoff"

        /** `SINK_TARGETS` 的种类：2 = 光尘接手（着色器里按这一项区分）。 */
        private const val KIND_DUST = 2f

        /** 分桶最多用这么多格（落点都在屏幕附近时只有一两百格）。 */
        private const val MAX_CELLS = 1L shl 20

        /** 前 [count] 项原地洗牌，取数次序与模拟器相同（从后往前，每次取 [0, i] 里的一个）。 */
        private fun shuffle(list: IntArray, count: Int, random: SplittableRandom) {
            for (i in count - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = list[i]
                list[i] = list[j]
                list[j] = t
            }
        }
    }
}
