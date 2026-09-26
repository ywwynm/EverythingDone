package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.taptransfer.ui.particles.TapDustBand
import com.ywwynm.everythingdone.views.particledismiss.ParticleHandoff
import com.ywwynm.everythingdone.views.particledismiss.ParticleHandoffSource
import com.ywwynm.everythingdone.views.particledismiss.ParticleSink
import java.util.SplittableRandom
import kotlin.math.floor
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

        // ---- 流向四周、看得见、剥离得早的那几片
        val eligible = ArrayList<Int>()
        for (i in 0 until n) {
            val p = i * 12
            if (ParticleSink.frameBound(values, p, sink) && (visible == null || visible[i]) &&
                values[p + 2] <= deathLo - minFlight) {
                eligible.add(i)
            }
        }
        val dustCount = band.count
        val hide = FloatArray(dustCount) { Float.POSITIVE_INFINITY }
        var pairs = 0
        if (eligible.isNotEmpty() && dustCount > 0) {
            val evaluator = band.Evaluator()
            val at = FloatArray(2)
            // 流入开始那一刻的光尘：扩散出去（离开边缘最外那一层）、且还看得见的那些当候选
            val tIn = inflow.startS
            val clockIn = stageClock(tIn)
            val spreadIn = spreadAt(tIn)
            val diffuseIn = diffuseAt(tIn)
            val candidates = ArrayList<Int>()
            val candidateX = FloatArray(dustCount)
            val candidateY = FloatArray(dustCount)
            for (j in 0 until dustCount) {
                evaluator.evaluate(j, clockIn, spreadIn, diffuseIn, 0f, at)
                val weight = band.baseAlpha[j] * band.innerFade(evaluator.q) * evaluator.edgeFade
                if (evaluator.q.pow(1.6f) > .12f && weight > .25f) {
                    candidates.add(j)
                    candidateX[j] = at[0] + stageLeft
                    candidateY[j] = at[1] + stageTop
                }
            }
            shuffle(candidates, random)
            // 候选颗粒的默认落点（动画层像素），按格子分桶找最近的
            val radius = 48f * density
            val grid = Buckets(radius)
            val target = FloatArray(2)
            val ex = FloatArray(eligible.size)
            val ey = FloatArray(eligible.size)
            eligible.forEachIndexed { k, i ->
                ParticleSink.fieldTarget(values, i * 12, sink, cardWidth, cardHeight, target)
                ex[k] = target[0] * scale + originXPx
                ey[k] = target[1] * scale + originYPx
                grid.add(k, ex[k], ey[k])
            }
            val used = BooleanArray(eligible.size)
            val chosenFlake = IntArray(eligible.size) { -1 }
            for (j in candidates) {
                val k = grid.nearestUnused(candidateX[j], candidateY[j], ex, ey, used)
                if (k < 0) continue
                used[k] = true
                chosenFlake[k] = j
                pairs++
            }
            // ---- 配上的颗粒：改寿命、落点取那片光尘在交接时刻的位置，颜色取那片光尘的颜色
            val turnOrigin = stageOriginMs / 1000.0
            eligible.forEachIndexed { k, i ->
                val j = chosenFlake[k]
                if (j < 0) return@forEachIndexed
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
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "光尘接手 可接手=${eligible.size} 候选光尘=${candidates.size} 配上=$pairs tauH=$tauH")
            }
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

    /** 按格子分桶的最近邻：格宽等于搜索半径，只看周围 3 × 3 格。 */
    private class Buckets(private val cell: Float) {
        private val cells = HashMap<Long, MutableList<Int>>()

        private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        fun add(index: Int, x: Float, y: Float) {
            cells.getOrPut(key(floor(x / cell).toInt(), floor(y / cell).toInt())) { ArrayList() }.add(index)
        }

        /** 半径 [cell] 以内、还没用过的最近一项；没有时返回 -1。 */
        fun nearestUnused(x: Float, y: Float, xs: FloatArray, ys: FloatArray, used: BooleanArray): Int {
            val cx = floor(x / cell).toInt()
            val cy = floor(y / cell).toInt()
            var best = -1
            var bestDistance = cell * cell
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val list = cells[key(cx + dx, cy + dy)] ?: continue
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
                }
            }
            return best
        }
    }

    companion object {
        private const val TAG = "TapDustHandoff"

        /** `SINK_TARGETS` 的种类：2 = 光尘接手（着色器里按这一项区分）。 */
        private const val KIND_DUST = 2f

        private fun shuffle(list: MutableList<Int>, random: SplittableRandom) {
            for (i in list.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = list[i]
                list[i] = list[j]
                list[j] = t
            }
        }
    }
}
