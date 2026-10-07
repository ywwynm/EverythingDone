package com.ywwynm.everythingdone.views.particledismiss

/**
 * 进程内全部飘散动画共用的播放记录。参考原版与其变体按同一个来源去重。
 * 分别记住上一段出现、上一段消散，同时排除紧邻的上一段，避免开关交替时各自仍然重复。
 * 预备动画只创建候选，真正开始播放时才确认；取消预热、按下后改为滚动都不消耗记录。
 */
internal class ParticleDriftSequence {
    private var lastReference: String? = null
    private val lastByDirection = arrayOfNulls<String>(2)

    fun prepare(reverse: Boolean, create: (Set<String>) -> ParticleDriftModel): Playback =
        Playback(this, reverse, create)

    @Synchronized private fun begin(playback: Playback): ParticleDriftModel {
        // 显式钉住样式的调试对照仍须可重复，也不影响正常播放记录。
        if (ParticleDriftStyles.forcedForTests != null) return playback.model
        val lane = if (playback.reverse) 1 else 0
        val excluded = setOfNotNull(lastReference, lastByDirection[lane])
        val candidate = playback.model
        val selected = if (ParticleDriftStyles.referenceName(candidate.styleName) in excluded)
            playback.create(excluded) else candidate
        val reference = ParticleDriftStyles.referenceName(selected.styleName)
        check(reference == null || reference !in excluded)
        lastReference = reference
        lastByDirection[lane] = reference
        return selected
    }

    class Playback internal constructor(
        private val sequence: ParticleDriftSequence,
        internal val reverse: Boolean,
        internal val create: (Set<String>) -> ParticleDriftModel
    ) {
        var model = create(emptySet())
            private set
        private var started = false

        /** 一次会话只确认一次；跟手、回退、末帧重画均继续使用同一个模型。 */
        fun start(): ParticleDriftModel {
            if (!started) {
                model = sequence.begin(this)
                started = true
            }
            return model
        }
    }
}
