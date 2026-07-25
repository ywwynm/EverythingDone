package com.ywwynm.everythingdone.model

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R

/**
 * Detail Autoplay：记事详情附件网格里动态内容（Animated Image、Motion Photo、
 * Thing Card Video Preview）的自动播放档位。见 ADR-0017 与
 * docs/features/detail-animated-playback/。
 *
 * 与 Cover Autoplay（[Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC]，布尔、只管 Thing Card 面）
 * 互相独立：两者任意组合都合法。全部档位只对当前在滚动视口内的附件生效——详情附件
 * RecyclerView 一次性全量布局、不按视口回收，Glide 的屏外自动暂停在那里是失效的。
 */
object DetailAutoplayMode {

    /** 全部停在静态代表帧；长按仍可手动播一遍。 */
    const val OFF: Int = 0

    /** 可见项按索引升序排队，各播一轮，轮完静默。 */
    const val ONE_BY_ONE: Int = 1

    /** 可见项同时各播一轮后停。 */
    const val ALL_ONCE: Int = 2

    /** 可见项同时无限循环。默认档——与本特性之前"无条件循环"的行为对齐。 */
    const val ALL_LOOP: Int = 3

    const val DEFAULT: Int = ALL_LOOP

    /** 设置项四选一对话框里的选项顺序（值即索引，保持一致以免来回换算）。 */
    val ALL_MODES: IntArray = intArrayOf(OFF, ONE_BY_ONE, ALL_ONCE, ALL_LOOP)

    @JvmStatic
    fun current(): Int = fromValue(
        FrequentSettings.getInt(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, DEFAULT)
    )

    /** 越界/脏值一律回落默认档，避免设置被外部改坏后详情页整片不播。 */
    @JvmStatic
    fun fromValue(value: Int): Int =
        if (value in OFF..ALL_LOOP) value else DEFAULT

    /** 该档位是否会自动起播（OFF 之外都会）。 */
    @JvmStatic
    fun autoPlays(mode: Int): Boolean = mode != OFF

    /** 该档位下播放是否无限循环。 */
    @JvmStatic
    fun loops(mode: Int): Boolean = mode == ALL_LOOP

    /** 该档位下是否一次只允许一项在播。 */
    @JvmStatic
    fun sequential(mode: Int): Boolean = mode == ONE_BY_ONE

    /**
     * 该档位是否响应长按手动播放。ALL_LOOP 下内容本来就在动，没有可触发的东西。
     */
    @JvmStatic
    fun allowsManualPlay(mode: Int): Boolean = mode != ALL_LOOP

    /**
     * 该档位是否需要为视口内的视频 / Motion Photo 请求派生 GIF。OFF 档不做无用功。
     */
    @JvmStatic
    fun requestsPreview(mode: Int): Boolean = mode != OFF

    @JvmStatic
    fun labelResOf(mode: Int): Int = when (fromValue(mode)) {
        OFF        -> R.string.detail_autoplay_off
        ONE_BY_ONE -> R.string.detail_autoplay_one_by_one
        ALL_ONCE   -> R.string.detail_autoplay_all_once
        else       -> R.string.detail_autoplay_all_loop
    }
}
