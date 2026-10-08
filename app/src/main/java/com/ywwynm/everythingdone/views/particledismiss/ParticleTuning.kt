package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.content.SharedPreferences
import com.ywwynm.everythingdone.BuildConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 粒子动画设置：样式、各样式独立的数量、大小和颜色精度，以及流体的精细度与性能自动调节。
 *
 * 设置作用于所选样式的全部粒子路径（弹窗、页内对话框、创建页、左滑卡片），存在自己的
 * SharedPreferences 文件里。粒子大小保存倍率的百分位整数，旧档位按原取值迁移；
 * 流动精细度存的是**取值本身**（它是 [FLOW_MIN]–[FLOW_MAX] 的连续整数，
 * 没有档位表）。
 *
 * 与 [ParticlePerfTuning] 的关系：那个对象是进程里唯一的读点，本对象只负责
 * 「从设置读出来、推给它」。debug 包的 A/B 配置文件写过同名键时以文件为准
 * （见 [ParticlePerfTuning.applyUserTuning]）。
 */
internal object ParticleTuning {

    const val PREFS_NAME = "particle_tuning"

    /** 流体大小倍率的百分位整数，与飘散独立保存。 */
    const val KEY_FLOW_SIZE = "flow_size_hundredths"

    /**
     * 流动精细度的键。
     *
     * 键名与上一版（六档下标 `jacobi_iterations_index`）不同同样是**有意的**：这一版存的是
     * 取值本身（0–100），沿用同一个键会把「第 2 档」读成「2」。迁移见 [migrateLegacyFlow]。
     */
    const val KEY_FLOW_DETAIL = "flow_detail"

    /** 颜色精度：true = 标准（8 位定点），false = 高精度（16 位浮点，默认）。 */
    const val KEY_COLOR_STANDARD = "color_precision_standard"
    const val KEY_DRIFT_COLOR_STANDARD = "drift_color_precision_standard"

    /** 性能自动调节：连续掉帧时自动降低粒子数量与流动精细度。默认关。 */
    const val KEY_AUTO_TUNE = "auto_performance_tuning"

    /** 粒子样式：`flow`（流体，原有实现）或 `drift`（飘散，缺省）。见 [ParticleStyle]。 */
    const val KEY_STYLE = "particle_style"

    /** 飘散数量以倍率的十分位整数保存（1～16）；不再依赖梯度下标，旧值按倍率迁移。 */
    const val KEY_DRIFT_AMOUNT = "drift_amount_tenths"

    /** 流体数量以倍率的十分位整数保存，与飘散独立。 */
    const val KEY_FLOW_AMOUNT = "flow_amount_tenths"

    /** 飘散大小倍率的百分位整数。 */
    const val KEY_DRIFT_SIZE = "drift_size_hundredths"

    /** 仅调试包：钉住某段参考样式的名字，或 `random`（一律随机生成）。见 [ParticleDriftStyles.forcedForTests]。 */
    const val KEY_DEBUG_FORCE_DRIFT_STYLE = "debug_drift_force_style"

    /** 仅调试包：飘散零拷贝时把导入的纹理与样本逐字节核对一遍（慢）。见 [ParticleDriftHardwareImport.verifyForTests]。 */
    const val KEY_DEBUG_VERIFY_ZERO_COPY = "debug_drift_verify_zero_copy"

    /** 「粒子数量」的档位（倍率）：0.1×～1.6×，每档 0.1。 */
    val AMOUNT_LEVELS = floatArrayOf(
        .1f, .2f, .3f, .4f, .5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f)
    const val DEFAULT_AMOUNT_INDEX = 9

    /** 早期飘散大小已使用倍率，直接迁入新倍率梯度。 */
    private const val LEGACY_KEY_DRIFT_SIZE = "drift_size_index"
    private val LEGACY_DRIFT_SIZE_LEVELS = floatArrayOf(
        .5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f, 2f)

    /** 上一版流体大小的十六档，迁移按 dp 值进行。 */
    private const val LEGACY_KEY_FLOW_SIZE = "particle_size_dp_index"
    private val LEGACY_FLOW_SIZE_LEVELS = floatArrayOf(
        .64f, .72f, .84f, .96f, 1.08f, 1.29f, 1.5f, 1.6f,
        1.8f, 2f, 2.1f, 2.4f, 2.56f, 2.7f, 3f, 3.2f)

    /** 0.2～1.6 倍的旧下标键；新梯度插入最低档，不能原样读取下标。 */
    private val LEGACY_AMOUNT_INDEX_KEYS = mapOf(
        "drift_amount_index" to KEY_DRIFT_AMOUNT, "flow_amount_index" to KEY_FLOW_AMOUNT)

    /** 上一版「粒子数量」的键与它的十一档梯度，只用于迁移。 */
    private const val LEGACY_KEY_DRIFT_AMOUNT = "drift_particle_amount"
    private val LEGACY_DRIFT_AMOUNT_LEVELS = floatArrayOf(.5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)

    /** 更早那一版的粒子大小键与它的九档梯度，只用于迁移。 */
    private const val LEGACY_KEY_CELL_INDEX = "microflake_cell_dp_index"
    private val LEGACY_CELL_DP_LEVELS = floatArrayOf(.9f, 1f, 1.2f, 1.6f, 2f, 2.4f, 2.7f, 3f, 3.2f)

    /** 上一版的流动精细度键与它的六档梯度，只用于迁移。 */
    private const val LEGACY_KEY_FLOW_INDEX = "jacobi_iterations_index"
    private val LEGACY_FLOW_LEVELS = intArrayOf(20, 30, 40, 60, 80, 100)

    private const val LEGACY_KEY_FLOW_DP = "flow_size_dp_index"
    private const val LEGACY_KEY_DRIFT_DP = "drift_size_dp_index"
    private val LEGACY_DP_LEVELS = floatArrayOf(
        .5f, .54f, .56f, .6f, .64f, .72f, .84f, .96f, 1.08f, 1.29f, 1.5f, 1.6f,
        1.8f, 2f, 2.1f, 2.4f, 2.56f, 2.7f, 3f, 3.2f,
        3.6f, 4f, 4.2f, 4.5f, 4.8f, 5f, 5.1f, 5.4f, 5.6f, 6f
    )
    /** 共用 0.5～6 倍；保留用户选择的数值，补上两种样式都需要的 1 倍默认档。 */
    val SIZE_LEVELS = floatArrayOf(
        .5f, .54f, .56f, .6f, .64f, .72f, .84f, .96f, 1f, 1.08f, 1.29f, 1.5f, 1.6f,
        1.8f, 2f, 2.1f, 2.4f, 2.56f, 2.7f, 3f, 3.2f,
        3.6f, 4f, 4.2f, 4.5f, 4.8f, 5f, 5.1f, 5.4f, 5.6f, 6f)
    val FLOW_SIZE_LEVELS get() = SIZE_LEVELS
    val DRIFT_SIZE_LEVELS get() = SIZE_LEVELS
    const val DEFAULT_SIZE_INDEX = 8
    const val DEFAULT_CELL_INDEX = DEFAULT_SIZE_INDEX

    fun nearestSizeIndex(scale: Float): Int = SIZE_LEVELS.indices.minByOrNull { abs(SIZE_LEVELS[it] - scale) } ?: 0

    fun sizeScale(index: Int): Float = SIZE_LEVELS[index.coerceIn(0, SIZE_LEVELS.lastIndex)]

    fun sizeLabel(index: Int): String = numberLabel(sizeScale(index)) + "×"

    private fun sizeKey(style: ParticleStyle): String = if (style == ParticleStyle.FLOW) KEY_FLOW_SIZE else KEY_DRIFT_SIZE

    internal fun sizeIndex(preferences: SharedPreferences, style: ParticleStyle): Int =
        nearestSizeIndex(preferences.getInt(sizeKey(style), 100) / 100f)

    /**
     * 流动精细度的取值范围：0–100 的连续整数，直接就是压力投影的迭代数。
     *
     * **0 表示完全不做流动求解**：整段压力更新都不跑，修正场保持构造时清空的零场
     * （见 `ParticleMicroflakeRenderer` 里 `skipPressure` 那一行）。
     */
    const val FLOW_MIN = 0
    const val FLOW_MAX = 100
    const val DEFAULT_FLOW = FLOW_MAX

    /**
     * 性能自动调节一次降多少流动精细度。取 20 是沿用上一版梯度相邻两档之差
     * （80 → 100），第四批实测「粒子大小与流动精细度各调一档」正是按这个幅度量的。
     */
    const val FLOW_EASE_STEP = 20

    /** 规则文件里自带的格子下限；只用于迁移旧流体绝对值设置。 */
    const val RULES_CELL_FLOOR_UNITS = 1.65

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cellIndex(context: Context): Int = sizeIndex(preferences(context), ParticleStyle.FLOW)

    fun flowDetail(context: Context): Int = preferences(context)
        .getInt(KEY_FLOW_DETAIL, DEFAULT_FLOW).coerceIn(FLOW_MIN, FLOW_MAX)

    /** 颜色精度是不是「标准」（8 位定点）；默认 false，即高精度的 16 位浮点。 */
    fun isColorStandard(context: Context, style: ParticleStyle = style(context)): Boolean =
        isColorStandard(preferences(context), style)

    private fun colorKey(style: ParticleStyle) =
        if (style == ParticleStyle.FLOW) KEY_COLOR_STANDARD else KEY_DRIFT_COLOR_STANDARD

    internal fun isColorStandard(preferences: SharedPreferences, style: ParticleStyle): Boolean =
        preferences.getBoolean(colorKey(style), false)

    internal fun setColorStandard(preferences: SharedPreferences, style: ParticleStyle, standard: Boolean) {
        preferences.edit().putBoolean(colorKey(style), standard).apply()
    }

    /** 性能自动调节是否打开；默认关。 */
    fun isAutoTuneOn(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTO_TUNE, false)

    fun style(context: Context): ParticleStyle =
        ParticleStyle.of(preferences(context).getString(KEY_STYLE, null))

    fun setStyle(context: Context, style: ParticleStyle) {
        preferences(context).edit().putString(KEY_STYLE, style.key).apply()
        apply(context)
    }

    private fun amountKey(style: ParticleStyle): String =
        if (style == ParticleStyle.FLOW) KEY_FLOW_AMOUNT else KEY_DRIFT_AMOUNT

    fun amountIndex(context: Context, style: ParticleStyle = style(context)): Int =
        amountIndex(preferences(context), style)

    internal fun amountIndex(preferences: SharedPreferences, style: ParticleStyle): Int =
        (preferences.getInt(amountKey(style), 10).coerceIn(1, 16) - 1)

    fun setAmountIndex(context: Context, index: Int) {
        val style = style(context)
        if (style == ParticleStyle.FLOW) markUserChanged(context)
        preferences(context).edit()
            .putInt(amountKey(style), (AMOUNT_LEVELS[index.coerceIn(0, AMOUNT_LEVELS.lastIndex)] * 10f).roundToInt()).apply()
        apply(context)
    }

    /** 「粒子数量」档位的文案，例如 0.1× / 1.0× / 1.6×。 */
    fun amountLabel(index: Int): String =
        String.format("%.1f×", AMOUNT_LEVELS[index.coerceIn(0, AMOUNT_LEVELS.lastIndex)])

    fun driftSizeIndex(context: Context): Int = sizeIndex(preferences(context), ParticleStyle.DRIFT)

    fun setDriftSizeIndex(context: Context, index: Int) {
        preferences(context).edit()
            .putInt(KEY_DRIFT_SIZE, (sizeScale(index) * 100f).roundToInt()).apply()
        apply(context)
    }

    fun setCellIndex(context: Context, index: Int) {
        markUserChanged(context)
        preferences(context).edit()
            .putInt(KEY_FLOW_SIZE, (sizeScale(index) * 100f).roundToInt()).apply()
        apply(context)
    }

    fun setFlowDetail(context: Context, value: Int) {
        markUserChanged(context)
        preferences(context).edit()
            .putInt(KEY_FLOW_DETAIL, value.coerceIn(FLOW_MIN, FLOW_MAX)).apply()
        apply(context)
    }

    fun setColorStandard(context: Context, standard: Boolean) {
        setColorStandard(preferences(context), style(context), standard)
        apply(context)
    }

    fun setAutoTune(context: Context, on: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_TUNE, on).apply()
        apply(context)
    }

    /** 恢复默认：全部设置项退回「未设置」（粒子样式回到缺省的飘散），也就是各项的默认行为。 */
    fun restoreDefaults(context: Context) {
        preferences(context).edit()
            .remove(KEY_FLOW_SIZE)
            .remove(KEY_FLOW_DETAIL)
            .remove(KEY_COLOR_STANDARD)
            .remove(KEY_DRIFT_COLOR_STANDARD)
            .remove(KEY_AUTO_TUNE)
            .remove(KEY_STYLE)
            .remove(KEY_DRIFT_AMOUNT)
            .remove(KEY_FLOW_AMOUNT)
            .remove(KEY_DRIFT_SIZE)
            .remove(LEGACY_KEY_FLOW_DP)
            .remove(LEGACY_KEY_DRIFT_DP)
            .remove(LEGACY_KEY_DRIFT_SIZE)
            .remove(LEGACY_KEY_FLOW_SIZE)
            .remove(LEGACY_KEY_CELL_INDEX)
            .remove(LEGACY_KEY_DRIFT_AMOUNT)
            .also { editor -> LEGACY_AMOUNT_INDEX_KEYS.keys.forEach { editor.remove(it) } }
            .remove(KEY_AUTO_STEPS)
            .remove(KEY_AUTO_ROUNDS)
            .remove(KEY_USER_CHANGED)
            .apply()
        apply(context)
    }

    // ---- 性能自动调节：按实测帧率自动降一档（默认关，规则见 [ParticleAutoDowngrade]）----

    private const val KEY_AUTO_STEPS = "auto_eased_steps"
    private const val KEY_AUTO_ROUNDS = "auto_poor_rounds"
    private const val KEY_USER_CHANGED = "user_changed_after_auto"

    /** 已经自动调低过几档；界面用它决定要不要显示那条提示。 */
    fun autoEasedSteps(context: Context): Int = preferences(context).getInt(KEY_AUTO_STEPS, 0)

    /** 用户自己动过数量、大小或精细度之后就不再自动调整。 */
    private fun markUserChanged(context: Context) {
        if (autoEasedSteps(context) <= 0) return
        preferences(context).edit().putBoolean(KEY_USER_CHANGED, true).apply()
    }

    /**
     * 一轮动画收尾时把「呈现帧数 / 应有帧数」交给规则判定；判定说要调就写设置。
     * 返回 true 表示这一次真的改了档位（界面据此刷新）。
     */
    fun onRoundMeasured(context: Context, ratio: Float): Boolean {
        if (!ParticlePerfTuning.autoDowngrade) return false
        val preferences = preferences(context)
        val decision = ParticleAutoDowngrade.decide(ParticleAutoDowngrade.State(
            ratio, preferences.getInt(KEY_AUTO_ROUNDS, 0), amountIndex(context, ParticleStyle.FLOW), flowDetail(context),
            autoEasedSteps(context),
            preferences.getBoolean(KEY_USER_CHANGED, false)))
        val editor = preferences.edit().putInt(KEY_AUTO_ROUNDS, decision.poorRounds)
        if (decision.downgrade) {
            editor.putInt(KEY_FLOW_AMOUNT, decision.amountIndex + 1)
                .putInt(KEY_FLOW_DETAIL, decision.flowDetail)
                .putInt(KEY_AUTO_STEPS, autoEasedSteps(context) + decision.appliedDelta)
        }
        editor.apply()
        if (decision.downgrade) apply(context)
        return decision.downgrade
    }

    /** 整十分位显示一位小数，其余保留两位。 */
    private fun numberLabel(value: Float): String {
        val hundredths = (value * 100f).roundToInt()
        return if (hundredths % 10 == 0) String.format("%.1f", value)
        else String.format("%.2f", value)
    }

    /** 新倍率键优先；旧 dp 按当时的基准换算，旧下标只在对应的旧梯度中解释。 */
    private fun migrateSizeLevels(preferences: SharedPreferences, editor: SharedPreferences.Editor, density: Float) {
        fun oldValue(key: String, levels: FloatArray): Float =
            levels[preferences.getInt(key, 0).coerceIn(0, levels.lastIndex)]
        fun putScale(key: String, scale: Float?) {
            if (scale != null && !preferences.contains(key))
                editor.putInt(key, (sizeScale(nearestSizeIndex(scale)) * 100f).roundToInt())
        }
        val flowDp = when {
            preferences.contains(LEGACY_KEY_FLOW_DP) -> oldValue(LEGACY_KEY_FLOW_DP, LEGACY_DP_LEVELS)
            preferences.contains(LEGACY_KEY_FLOW_SIZE) -> oldValue(LEGACY_KEY_FLOW_SIZE, LEGACY_FLOW_SIZE_LEVELS)
            preferences.getInt(LEGACY_KEY_CELL_INDEX, 0) in 1 until LEGACY_CELL_DP_LEVELS.size ->
                oldValue(LEGACY_KEY_CELL_INDEX, LEGACY_CELL_DP_LEVELS)
            else -> null
        }
        val flowBaseDp = (RULES_CELL_FLOOR_UNITS / ParticleMicroflakeModel.DP_UNITS).toFloat()
        putScale(KEY_FLOW_SIZE, flowDp?.div(flowBaseDp))
        val driftScale = when {
            preferences.contains(LEGACY_KEY_DRIFT_DP) -> ParticleDriftModel.sizeScaleForDp(
                oldValue(LEGACY_KEY_DRIFT_DP, LEGACY_DP_LEVELS), density)
            preferences.contains(LEGACY_KEY_DRIFT_SIZE) -> oldValue(LEGACY_KEY_DRIFT_SIZE, LEGACY_DRIFT_SIZE_LEVELS)
            else -> null
        }
        putScale(KEY_DRIFT_SIZE, driftScale)
        listOf(LEGACY_KEY_FLOW_DP, LEGACY_KEY_DRIFT_DP, LEGACY_KEY_FLOW_SIZE,
            LEGACY_KEY_DRIFT_SIZE, LEGACY_KEY_CELL_INDEX).forEach { editor.remove(it) }
    }

    /**
     * 把上一版六档梯度里的流动精细度**按取值**搬过来：第 k 档存的是下标，新键存的是迭代数。
     * 最高一档（100）本来就是默认值，未写入新键时只删旧键。
     */
    private fun migrateLegacyFlow(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        if (!preferences.contains(LEGACY_KEY_FLOW_INDEX)) return
        val legacy = preferences.getInt(LEGACY_KEY_FLOW_INDEX, LEGACY_FLOW_LEVELS.lastIndex)
        editor.remove(LEGACY_KEY_FLOW_INDEX)
        if (legacy in LEGACY_FLOW_LEVELS.indices && legacy != LEGACY_FLOW_LEVELS.lastIndex &&
            !preferences.contains(KEY_FLOW_DETAIL)) {
            editor.putInt(KEY_FLOW_DETAIL, LEGACY_FLOW_LEVELS[legacy])
        }
    }

    /**
     * 历代数量键按原来的倍率迁移；新键优先，其次 0.2～1.6 倍下标，最后才是 0.5～1.5 倍下标。
     */
    private fun migrateLegacyAmount(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        for ((legacyKey, key) in LEGACY_AMOUNT_INDEX_KEYS) {
            if (!preferences.contains(legacyKey)) continue
            editor.remove(legacyKey)
            if (!preferences.contains(key)) {
                editor.putInt(key, preferences.getInt(legacyKey, 8).coerceIn(0, 14) + 2)
            }
        }
        if (!preferences.contains(LEGACY_KEY_DRIFT_AMOUNT)) return
        val legacy = preferences.getInt(LEGACY_KEY_DRIFT_AMOUNT, 5)
        editor.remove(LEGACY_KEY_DRIFT_AMOUNT)
        if (legacy in LEGACY_DRIFT_AMOUNT_LEVELS.indices && !preferences.contains(KEY_DRIFT_AMOUNT) &&
            !preferences.contains("drift_amount_index")) {
            val amount = LEGACY_DRIFT_AMOUNT_LEVELS[legacy]
            editor.putInt(KEY_DRIFT_AMOUNT, (amount * 10f).roundToInt())
        }
    }

    internal fun migrate(preferences: SharedPreferences, density: Float) {
        require(density.isFinite() && density > 0f)
        if (preferences.contains(LEGACY_KEY_CELL_INDEX) || preferences.contains(LEGACY_KEY_FLOW_INDEX) ||
            preferences.contains(LEGACY_KEY_DRIFT_AMOUNT) || LEGACY_AMOUNT_INDEX_KEYS.keys.any { preferences.contains(it) } ||
            preferences.contains(LEGACY_KEY_FLOW_SIZE) || preferences.contains(LEGACY_KEY_DRIFT_SIZE) ||
            preferences.contains(LEGACY_KEY_FLOW_DP) || preferences.contains(LEGACY_KEY_DRIFT_DP)) {
            val editor = preferences.edit()
            migrateSizeLevels(preferences, editor, density)
            migrateLegacyFlow(preferences, editor)
            migrateLegacyAmount(preferences, editor)
            // `apply()` 对内存里的那份映射是同步生效的，紧接着的读取拿到的就是迁移后的值；
            // 磁盘写入异步进行，不占主线程。
            editor.apply()
        }
    }

    /** 进程启动时与每次改动之后各调一次。 */
    fun apply(context: Context) {
        val preferences = preferences(context)
        val density = context.resources.displayMetrics.density
        migrate(preferences, density)
        ParticleStyle.current = style(context)
        ParticleStyle.driftAmount = AMOUNT_LEVELS[amountIndex(context, ParticleStyle.DRIFT)]
        ParticleStyle.flowAmount = AMOUNT_LEVELS[amountIndex(context, ParticleStyle.FLOW)]
        ParticleStyle.driftSizeScale = sizeScale(driftSizeIndex(context))
        ParticleStyle.flowSizeScale = sizeScale(cellIndex(context))
        ParticleStyle.driftColorStandard = isColorStandard(preferences, ParticleStyle.DRIFT)
        // 仅调试包：验收时把某段参考样式钉住（键由 adb 经 run-as 写入，正式包不读）。
        ParticleDriftStyles.forcedForTests =
            if (BuildConfig.DEBUG) preferences.getString(KEY_DEBUG_FORCE_DRIFT_STYLE, null) else null
        ParticleDriftHardwareImport.verifyForTests =
            BuildConfig.DEBUG && preferences.getBoolean(KEY_DEBUG_VERIFY_ZERO_COPY, false)
        ParticlePerfTuning.applyUserTuning(
            0f,
            flowDetail(context),
            isColorStandard(preferences, ParticleStyle.FLOW),
            isAutoTuneOn(context)
        )
    }
}
