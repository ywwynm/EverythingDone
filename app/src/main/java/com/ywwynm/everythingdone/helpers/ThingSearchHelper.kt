package com.ywwynm.everythingdone.helpers

import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.BackgroundUtil

object ThingSearchHelper {

    @JvmStatic
    fun matches(thing: Thing?, keyword: String?, color: Int): Boolean {
        return matchesKeyword(thing, keyword) && matchesColor(thing, color)
    }

    @JvmStatic
    fun matchesKeyword(thing: Thing?, keyword: String?): Boolean {
        if (keyword.isNullOrEmpty()) return true
        if (thing == null) return false
        if (thing.type == Thing.HEADER) return true
        return searchableTitle(thing).contains(keyword, ignoreCase = true) ||
            searchableContent(thing).contains(keyword, ignoreCase = true)
    }

    @JvmStatic
    fun matchesColor(thing: Thing?, color: Int): Boolean {
        if (!hasColorFilter(color)) return true
        if (thing == null) return false
        if (thing.type == Thing.HEADER || Thing.isLegacyPlaceholderType(thing.type)) return true
        return BackgroundUtil.matchesHueBucket(
            thing.getBackground(),
            BackgroundUtil.hueBucket(color)
        )
    }

    @JvmStatic
    fun hasColorFilter(color: Int): Boolean {
        return color != 0 && color != -1979711488
    }

    @JvmStatic
    fun searchableTitle(thing: Thing): String {
        return thing.title.orEmpty().removePrefix(Thing.PRIVATE_THING_PREFIX)
    }

    @JvmStatic
    fun searchableContent(thing: Thing): String {
        val content = thing.content.orEmpty()
        return if (isChecklistContent(content)) {
            CheckListHelper.toContentStr(content, "", "")
        } else {
            content
        }
    }

    private fun isChecklistContent(content: String): Boolean {
        return content.length >= CheckListHelper.SIGNAL_LENGTH &&
            content.substring(0, CheckListHelper.SIGNAL_LENGTH) == CheckListHelper.SIGNAL
    }
}
