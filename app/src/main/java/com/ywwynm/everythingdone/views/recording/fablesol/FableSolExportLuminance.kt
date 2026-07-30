package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * HDR10 / HDR10+ 的全片静态亮度统计（fablesol-video-export D85～D92）。
 *
 * 统计量存的是**相对漫反射白归一化**的值，不是尼特：只调 PQ 漫反射白时可以直接重新缩放，
 * 无需再渲染一遍全片（D92）。会改变场景本身的 HDR 强度仍进指纹。
 *
 * @param maxContentNormalized 全片全部像素 `max(R, G, B)` 的最大值（BT.2020 线性，漫反射白 = 1.0）。
 * @param maxFrameAverageNormalized 逐帧全部可见像素 `max(R, G, B)` 平均值的全片最大值。
 * @param measured 是真实测量还是 D90 的理论回退；两者在界面上必须分得开。
 */
internal data class FableSolExportLuminanceStats(
    val maxContentNormalized: Double,
    val maxFrameAverageNormalized: Double,
    val measured: Boolean
) {

    /**
     * `MaxCLL`（尼特）。
     *
     * 向**上**取整，使写入的整数仍是实际信号的上界；H.274 的语义是"内容不超过这个亮度"，
     * 普通四舍五入会让它偶尔低报（D86）。
     */
    fun maxContentLightLevel(diffuseWhiteNits: Double): Int =
        ceil(maxContentNormalized * diffuseWhiteNits).toInt().coerceIn(0, 65535)

    /**
     * `MaxFALL`（尼特）；未知时为 `0`。
     *
     * 按 H.274，零明确表示"未提供该上界"，不表示零尼特画面（D90）。
     */
    fun maxFrameAverageLightLevel(diffuseWhiteNits: Double): Int = if (measured) {
        ceil(maxFrameAverageNormalized * diffuseWhiteNits).toInt().coerceIn(0, 65535)
    } else {
        0
    }

    companion object {

        /**
         * D90 的回退：统计归约或回读失败、但画面渲染本身仍然有效时使用。
         *
         * `MaxCLL` 取理论峰值——shader 已把场景限制在 `漫反射白 × HDR 强度` 内，BT.2020
         * 线性转换也不放大中性最大分量，所以它是有效但偏保守的上界。`MaxFALL` 写未知：
         * 漫反射白不能保证是每帧平均 `maxRGB` 的上界，直接拿它顶替可能**低报**；而把理论
         * 峰值同时写成 MaxFALL 又会暗示存在接近峰值的全屏亮画面，诱发过度保守的映射。
         */
        fun theoretical(strength: Double): FableSolExportLuminanceStats =
            FableSolExportLuminanceStats(
                maxContentNormalized = strength.coerceAtLeast(1.0),
                maxFrameAverageNormalized = 0.0,
                measured = false
            )
    }
}

/**
 * 全片亮度预分析结果的持久缓存（D92）。
 *
 * 指纹必须覆盖**一切能改变编码前画面的条件**。只写完整成功的结果：取消、部分结果、异常
 * 以及 D90 的回退状态都不得进缓存，否则一次偶然的失败会把错误的统计钉死到下一次导出。
 */
internal object FableSolExportLuminanceCache {

    /**
     * 亮度统计与渲染契约版本。
     *
     * 相关 shader、颜色转换、合成范围或统计定义发生变化时**必须**升级：缓存里的数字本身
     * 看不出它是按哪套定义算的。
     */
    const val CONTRACT_VERSION = 1

    private const val PREFS_NAME = "fablesol_export_luminance"
    private const val MAX_ENTRIES = 12

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 完整渲染指纹。
     *
     * 音频用**整份文件内容**的摘要，不是 URI、文件名、大小或修改时间：本应用的 WAV 里带着
     * 重力轨迹自定义 chunk，换一份轨迹画面就不同，而文件大小可以一模一样。
     */
    fun fingerprint(
        context: Context,
        audioPath: String,
        strength: Float,
        widthPx: Int,
        heightPx: Int,
        frameRate: Int,
        tiltEnabled: Boolean,
        darkAppearance: Boolean,
        backdropColor: Int,
        rimColor: Int
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CONTRACT_VERSION.toString().toByteArray())
        digest.update(audioDigest(audioPath))
        // 全部 FableSol 视觉调节值：它们直接决定水体、银丝与星芒的形状和亮度。
        // 默认值从一个全新的 params 实例读，与渲染器构造时的来源完全相同。
        val defaults = FableSolParams()
        for (group in FableSolTuning.GROUPS) {
            for (spec in group.specs) {
                if (spec.target != FableSolTuning.Target.RENDERER) continue
                val value = FableSolTuning.storedValue(context, spec, defaults.get(spec.key))
                digest.update("${spec.key}=$value;".toByteArray())
            }
        }
        digest.update(
            (
                "strength=$strength;w=$widthPx;h=$heightPx;fps=$frameRate;" +
                    "tilt=$tiltEnabled;dark=$darkAppearance;" +
                    "backdrop=$backdropColor;rim=$rimColor"
                ).toByteArray()
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 整份文件的摘要；读不到时用一个明确的占位，保证不会与真实摘要撞上。 */
    private fun audioDigest(audioPath: String): ByteArray = try {
        val digest = MessageDigest.getInstance("SHA-256")
        File(audioPath).inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest()
    } catch (ignored: Throwable) {
        "unreadable-audio".toByteArray()
    }

    /** @return null 表示没有可用缓存；调用方据此执行一次完整预分析。 */
    fun read(context: Context, fingerprint: String): FableSolExportLuminanceStats? {
        val encoded = prefs(context).getString(fingerprint, null) ?: return null
        val parts = encoded.split('|')
        if (parts.size != 2) return null
        val peak = parts[0].toDoubleOrNull() ?: return null
        val average = parts[1].toDoubleOrNull() ?: return null
        if (peak < 0.0 || average < 0.0) return null
        return FableSolExportLuminanceStats(peak, average, measured = true)
    }

    /** 只接受完整成功的实测结果（D92）。 */
    fun write(
        context: Context,
        fingerprint: String,
        stats: FableSolExportLuminanceStats
    ) {
        if (!stats.measured) return
        val store = prefs(context)
        val entries = store.all
        val editor = store.edit()
        // 指纹逐次变化（改一个滑杆就是一份新指纹），不设上限的话这份偏好会无限长。
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(fingerprint)) {
            entries.keys.take(entries.size - MAX_ENTRIES + 1).forEach { editor.remove(it) }
        }
        editor.putString(
            fingerprint,
            "${stats.maxContentNormalized}|${stats.maxFrameAverageNormalized}"
        )
        editor.apply()
    }
}
