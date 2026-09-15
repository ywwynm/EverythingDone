package com.ywwynm.everythingdone.views.particledismiss

/**
 * 「提前算好的轨迹能不能用在这一次新建上」的判定（第四批 ②）。
 *
 * 单独成一个不碰任何 Android 类的对象，好让 JVM 单测直接跑——[ParticleCreationPrewarm] 里
 * 有 Handler 与 Activity，构造就要主线程 Looper。
 */
internal object ParticleCreationMatch {

    /**
     * 返回未命中的原因；null 表示可以用。
     *
     * 判据只有三条，**都与像素无关**：环境指纹相同、快照尺寸相同、快照原点相同。
     * 预测快照是上一次真实创建页的首屏，背景色必然不同——不要求像素相等是这个方案的前提：
     * 末帧由真实贴图按格子拼回，逐像素等于真实页面，预测偏差只影响途中每一片的权重。
     */
    fun reject(expected: ParticleCreationKey, actual: ParticleCreationKey?,
               preparedWidth: Int, preparedHeight: Int, width: Int, height: Int,
               preparedOriginX: Float?, preparedOriginY: Float?,
               originX: Float, originY: Float): String? = when {
        actual == null -> "本次新建没有带缓存键"
        expected != actual -> "环境变了：${difference(expected, actual)}"
        preparedWidth != width || preparedHeight != height ->
            "快照尺寸 ${width}×$height ≠ 预算时 ${preparedWidth}×$preparedHeight"
        preparedOriginX != null && preparedOriginY != null &&
            (preparedOriginX != originX || preparedOriginY != originY) -> "快照原点变了"
        else -> null
    }

    /** 指纹是 `名=值|名=值` 的串；给观察器一句人话：到底是哪几项变了。 */
    fun difference(expected: ParticleCreationKey, actual: ParticleCreationKey): String {
        val before = expected.environment.split('|').associate { it.substringBefore('=') to it }
        val after = actual.environment.split('|').associate { it.substringBefore('=') to it }
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        return if (changed.isEmpty()) "未知" else changed.joinToString(",")
    }
}
