package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context

/**
 * 设置页里那一行本机读数：**上一轮渲染了几帧、应该有几帧、每帧之间隔了多久、折合多少帧率**。
 *
 * 只在粒子动画设置页打开着的时候采集（[begin] / [end] 成对调用），别的时候
 * [onPresented] 第一行就返回，release 与 debug 行为一致。
 *
 * 口径与本专项其余文档一致：记的是**真的上屏了的帧**（[ParticleWindowSurface] 在
 * 确认呈现时逐帧报进来），不是提交了几帧；「应有帧数」按首帧到末帧这一段
 * 除以一拍的长度算，所以一轮不掉帧就是 1.00。渲染间隔与帧率取同一份数据的平均值，
 * 两者互为倒数，不会出现「间隔 16.6 ms 却只有 40 fps」这种自相矛盾的读数。
 */
internal object ParticleFrameStats {

    /** 一拍的名义长度（ns）：粒子层按 60 Hz 投票出帧。 */
    private const val BEAT_NANOS = 1_000_000_000L / 60

    class Summary(val frames: Int, val expected: Int, val spanMs: Float) {
        val ratio get() = if (expected <= 0) 0f else frames.toFloat() / expected

        /** 相邻两帧的平均间隔（ms）。 */
        val intervalMs get() = if (frames <= 1) 0f else spanMs / (frames - 1)

        /** 由平均间隔折算的帧率。 */
        val fps get() = if (intervalMs <= 0f) 0f else 1000f / intervalMs
    }

    @Volatile private var collecting = false

    private val times = ArrayList<Long>(512)

    /** 上一轮的汇总；没有采到足够的帧时为 null。 */
    @Volatile var last: Summary? = null
        private set

    /** 最近一次建材的粒子数量，由 [ParticleMicroflakeModel] 报进来。 */
    @Volatile var flakes = 0

    fun begin() {
        synchronized(times) { times.clear(); collecting = true }
    }

    /**
     * 收尾并汇总；返回 null 表示这一轮没采到两帧以上。
     *
     * 收尾一定把采样清空：不清的话再调一次就会把**上一轮**重新汇总一遍，
     * 读数看上去正常、其实是旧的。[last] 在这种情况下保持不变，界面上仍显示上一次量到的值。
     */
    fun end(): Summary? {
        val samples = synchronized(times) {
            collecting = false
            val out = if (times.size < 2) null else times.toLongArray()
            times.clear()
            out
        }
        if (samples == null) return null
        samples.sort()
        val span = samples.last() - samples.first()
        val summary = Summary(samples.size,
            ((span + BEAT_NANOS / 2) / BEAT_NANOS).toInt() + 1,
            span / 1e6f)
        last = summary
        return summary
    }

    /**
     * [nanos] 优先是驱动报的真实呈现时刻；拿不到时调用方传本拍的目标时刻。
     *
     * 设置页没开着时本来什么都不采；U9 打开之后要在**平时**也知道这一轮掉没掉帧，
     * 所以那一档也采（[ParticlePerfTuning.autoDowngrade] 默认关，常态仍是零开销）。
     */
    fun onPresented(nanos: Long) {
        if (!collecting && !ParticlePerfTuning.autoDowngrade) return
        synchronized(times) { if (times.size < 512) times.add(nanos) }
    }

    /**
     * U9：一轮动画收尾。设置页正开着的时候由它自己 [end]，这里不抢它的采样。
     *
     * 返回 true 表示这一次真的调低了档位。
     */
    fun roundFinished(context: Context): Boolean {
        if (!ParticlePerfTuning.autoDowngrade || collecting) return false
        val summary = end() ?: return false
        return ParticleTuning.onRoundMeasured(context, summary.ratio)
    }

    /**
     * 飘散样式的一轮收尾。自动调档只调流体样式的参数，这一轮的采样不能交给它，直接丢掉；
     * 设置页正开着时留给设置页自己汇总。
     */
    fun roundSkipped() {
        synchronized(times) { if (!collecting) times.clear() }
    }

    fun reportFlakes(count: Int) { flakes = count }
}
