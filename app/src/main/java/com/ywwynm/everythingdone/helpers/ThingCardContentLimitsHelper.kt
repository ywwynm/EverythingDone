package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.content.SharedPreferences

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.model.ThingCardAppearance

import kotlin.math.max
import kotlin.math.min

/**
 * 记事卡片上「内容文本最多显示几行」「清单项最多显示几个」的取值来源。
 *
 * 两层：设置页的全局默认（本对象读写 SharedPreferences），以及单条记事在
 * [ThingCardAppearance] 里的覆盖。覆盖为 null 即未设定，回退到全局默认。
 * 两者都按卡片宽度分「正常 / 宽」两档。
 *
 * 只服务首页列表与单一记事 widget 配置页（见 ThingsAdapter 的覆写）。
 * DoingActivity、提醒全屏页、文件夹预览小卡片、桌面小组件都不经过这里。
 */
object ThingCardContentLimitsHelper {

    const val MIN_LIMIT: Int = ThingCardAppearance.MIN_CONTENT_LIMIT
    const val MAX_LIMIT: Int = ThingCardAppearance.MAX_CONTENT_LIMIT

    const val DEFAULT_CONTENT_MAX_LINES_NORMAL: Int = 9
    const val DEFAULT_CONTENT_MAX_LINES_FULL: Int = 16
    const val DEFAULT_CHECKLIST_MAX_ITEMS_NORMAL: Int = 8
    const val DEFAULT_CHECKLIST_MAX_ITEMS_FULL: Int = 12

    @JvmStatic
    fun defaultContentMaxLines(fullSpan: Boolean): Int {
        return if (fullSpan) DEFAULT_CONTENT_MAX_LINES_FULL else DEFAULT_CONTENT_MAX_LINES_NORMAL
    }

    @JvmStatic
    fun defaultChecklistMaxItems(fullSpan: Boolean): Int {
        return if (fullSpan) {
            DEFAULT_CHECKLIST_MAX_ITEMS_FULL
        } else {
            DEFAULT_CHECKLIST_MAX_ITEMS_NORMAL
        }
    }

    private fun contentMaxLinesKey(fullSpan: Boolean): String {
        return if (fullSpan) {
            Def.Meta.KEY_CARD_CONTENT_MAX_LINES_FULL
        } else {
            Def.Meta.KEY_CARD_CONTENT_MAX_LINES_NORMAL
        }
    }

    private fun checklistMaxItemsKey(fullSpan: Boolean): String {
        return if (fullSpan) {
            Def.Meta.KEY_CARD_CHECKLIST_MAX_ITEMS_FULL
        } else {
            Def.Meta.KEY_CARD_CHECKLIST_MAX_ITEMS_NORMAL
        }
    }

    /** 全局默认：内容文本最大行数。 */
    @JvmStatic
    fun globalContentMaxLines(fullSpan: Boolean): Int {
        return clamp(
            FrequentSettings.getInt(
                contentMaxLinesKey(fullSpan), defaultContentMaxLines(fullSpan)
            )
        )
    }

    /** 全局默认：清单项最大个数。 */
    @JvmStatic
    fun globalChecklistMaxItems(fullSpan: Boolean): Int {
        return clamp(
            FrequentSettings.getInt(
                checklistMaxItemsKey(fullSpan), defaultChecklistMaxItems(fullSpan)
            )
        )
    }

    @JvmStatic
    fun setGlobalContentMaxLines(context: Context, fullSpan: Boolean, maxLines: Int) {
        write(context, contentMaxLinesKey(fullSpan), clamp(maxLines))
    }

    @JvmStatic
    fun setGlobalChecklistMaxItems(context: Context, fullSpan: Boolean, maxItems: Int) {
        write(context, checklistMaxItemsKey(fullSpan), clamp(maxItems))
    }

    /** 这条记事这一档最终生效的内容文本行数：有覆盖用覆盖，否则用全局默认。 */
    @JvmStatic
    fun resolveContentMaxLines(appearance: ThingCardAppearance?, fullSpan: Boolean): Int {
        val override = appearance?.contentMaxLines(fullSpan)
        return clamp(override ?: globalContentMaxLines(fullSpan))
    }

    /** 这条记事这一档最终生效的清单项个数：有覆盖用覆盖，否则用全局默认。 */
    @JvmStatic
    fun resolveChecklistMaxItems(appearance: ThingCardAppearance?, fullSpan: Boolean): Int {
        val override = appearance?.checklistMaxItems(fullSpan)
        return clamp(override ?: globalChecklistMaxItems(fullSpan))
    }

    @JvmStatic
    fun clamp(value: Int): Int = max(MIN_LIMIT, min(MAX_LIMIT, value))

    private fun write(context: Context, key: String, value: Int) {
        val sp: SharedPreferences = context.applicationContext.getSharedPreferences(
            Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE
        )
        sp.edit().putInt(key, value).apply()
        FrequentSettings.put(key, value)
    }
}
