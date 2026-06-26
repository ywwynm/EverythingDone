package com.ywwynm.everythingdone.helpers

import android.graphics.Color

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R

import java.util.ArrayList
import java.util.Locale

/**
 * Created by ywwynm on 2015/9/17.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Multi-level support added by ywwynm and Claude Opus 4.8 on 2026/6/26.
 *
 * Convert content string into check list items and vice versa.
 *
 * 存储/内存中每个"真实项"的格式是 `<状态位><层级位><文本>`：
 *   - 状态位 `0` = 未完成、`1` = 已完成
 *   - 层级位 `1`/`2`/`3` = 清单项层级
 *   - 其后是文本
 * 控制标记 `2`(添加新项行) / `3`(分隔线) / `4`(已完成头部) 仍是单字符、不带层级位。
 * 见 docs/adr/0010-checklist-item-level-encoding.md。
 */
object CheckListHelper {

    const val TAG: String = "CheckListHelper"

    const val SIGNAL_LENGTH: Int   = 4
    const val CHECK_STATE_NUM: Int = 5

    const val MAX_LEVEL: Int = 3

    const val STATE_UNFINISHED: Char = '0'
    const val STATE_FINISHED: Char   = '1'

    // ---- 分级排版：三个独立比例常量，都从 0.9 起步，方便后续分别微调 ----
    const val LEVEL_SIZE_RATIO: Float            = 0.9f  // 字号，所有项通用
    const val LEVEL_ALPHA_RATIO_UNFINISHED: Float = 0.9f  // 未完成项透明度
    const val LEVEL_ALPHA_RATIO_FINISHED: Float   = 0.9f  // 已完成项透明度

    /** 第 [level] 级相对一级的字号系数（level=1 → 1.0）。 */
    @JvmStatic
    fun sizeRatioForLevel(level: Int): Float {
        var r = 1f
        repeat((level - 1).coerceAtLeast(0)) { r *= LEVEL_SIZE_RATIO }
        return r
    }

    /** 第 [level] 级相对一级的透明度系数，已完成与未完成各用独立常量。 */
    @JvmStatic
    fun alphaRatioForLevel(level: Int, finished: Boolean): Float {
        val base = if (finished) LEVEL_ALPHA_RATIO_FINISHED else LEVEL_ALPHA_RATIO_UNFINISHED
        var r = 1f
        repeat((level - 1).coerceAtLeast(0)) { r *= base }
        return r
    }

    /** 把 [color] 的 alpha 按层级系数缩放后返回。 */
    @JvmStatic
    fun colorForLevel(color: Int, level: Int, finished: Boolean): Int {
        if (level <= 1) return color
        val a = (Color.alpha(color) * alphaRatioForLevel(level, finished)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    // cannot write hardcoded signal after updated to Jack compiler with Java 8
    @JvmField
    val SIGNAL: String = App.getApp()!!.getString(R.string.base_signal_upper)

    // ---------------------------------------------------------------------
    // 单项字段访问：所有界面都应通过这些方法读写清单项，而不是直接 substring。
    // ---------------------------------------------------------------------

    /** 是否是一个真实清单项（未完成或已完成），而不是控制标记 2/3/4。 */
    @JvmStatic
    fun isItem(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        val c = s[0]
        return c == STATE_UNFINISHED || c == STATE_FINISHED
    }

    /** 状态位（'0'/'1'）。对控制标记返回其本身的首字符。 */
    @JvmStatic
    fun stateOf(item: String?): Char = item!![0]

    @JvmStatic
    fun isFinished(item: String?): Boolean = item != null && item.isNotEmpty() && item[0] == STATE_FINISHED

    @JvmStatic
    fun isUnfinished(item: String?): Boolean = item != null && item.isNotEmpty() && item[0] == STATE_UNFINISHED

    /** 层级，1~3；非真实项或缺层级位时返回 1。 */
    @JvmStatic
    fun levelOf(item: String?): Int {
        if (!isItem(item) || item!!.length < 2) return 1
        val c = item[1]
        return if (c in '1'..'3') c - '0' else 1
    }

    /** 文本部分（跳过状态位与层级位）。非真实项返回空串。 */
    @JvmStatic
    fun textOf(item: String?): String {
        if (!isItem(item)) return ""
        return if (item!!.length <= 2) "" else item.substring(2)
    }

    /** 该真实项是否文本为空（用于退格删除判断，等价于旧的 length == 1）。 */
    @JvmStatic
    fun isEmptyItem(item: String?): Boolean = isItem(item) && item!!.length <= 2

    /** 拼一个真实项字符串。 */
    @JvmStatic
    fun makeItem(state: Char, level: Int, text: String): String {
        val lv = level.coerceIn(1, MAX_LEVEL)
        return "" + state + ('0' + lv) + text
    }

    @JvmStatic
    fun withState(item: String, state: Char): String = makeItem(state, levelOf(item), textOf(item))

    @JvmStatic
    fun withLevel(item: String, level: Int): String = makeItem(stateOf(item), level, textOf(item))

    @JvmStatic
    fun withText(item: String, text: String): String = makeItem(stateOf(item), levelOf(item), text)

    @JvmStatic
    fun isCheckListStr(s: String?): Boolean {
        return s!!.length >= SIGNAL_LENGTH && s.substring(0, SIGNAL_LENGTH) == SIGNAL
    }

    @JvmStatic
    fun toCheckListItems(s: String?, convert: Boolean): MutableList<String?> {
        var str: String = s!!
        if (convert) {
            str = toCheckListStr(str)
        }
        val strs: Array<String> = str.split(SIGNAL.toRegex()).toTypedArray()
        val items: MutableList<String?> = ArrayList()
        items.addAll(listOf(*strs).subList(1, strs.size))

        // 归一化层级与完成态，消除历史数据或异常操作留下的跳空/孤儿、以及"已完成项挂未完成下属"的
        // 不一致（此时列表里只有真实项、还没插入控制标记）。
        normalizeLevels(items)
        normalizeCompletion(items)

        // 底部已完成区的边界是第一个"已完成组根"（不是第一个已完成项）——顶部可能有就地完成
        // 的深层项，它们的状态位也是 1，但不应被当成边界。见 decisions.md（顶/底边界的真理来源）。
        var boundary: Int = -1
        val size: Int = items.size
        for (i in 0 until size) {
            if (isFinished(items[i]) && ownerIndexOf(items, i) == -1) {
                boundary = i
                break
            }
        }
        if (boundary != -1) {
            items.add(boundary, "2")
            items.add(boundary + 1, "3")
            items.add(boundary + 2, "4")
        } else {
            items.add("2")
        }

        return items
    }

    @JvmStatic
    fun toContentStr(items: List<String?>?): String {
        val checkListStr: String = toCheckListStr(items)
        return toContentStr(checkListStr, "", "")
    }

    /**
     * 把清单串转成纯文本。`unchecked`/`checked` 非空时为"带标记"模式（通知 / 分享导出），
     * 此时按层级加每级两个空格的前导缩进；二者皆空时为"拍平"模式（清单转普通文本记事），
     * 不加标记也不加缩进。见 docs/features/multi-level-checklist/decisions.md。
     */
    @JvmStatic
    fun toContentStr(checkListStr: String?, unchecked: String?, checked: String?): String {
        if (!checkListStr!!.contains(SIGNAL + 0) && !checkListStr.contains(SIGNAL + 1)) {
            return ""
        }
        val marked: Boolean = !unchecked.isNullOrEmpty() || !checked.isNullOrEmpty()
        val items = toCheckListItems(checkListStr, false)
        val sb = StringBuilder()
        var first = true
        for (item in items) {
            if (!isItem(item)) continue
            if (!first) sb.append('\n')
            first = false
            if (marked) {
                val level = levelOf(item)
                for (i in 1 until level) sb.append("  ")
                sb.append(if (item!![0] == STATE_UNFINISHED) unchecked else checked)
            }
            sb.append(textOf(item))
        }
        return sb.toString()
    }

    @JvmStatic
    fun toCheckListStr(items: List<String?>?): String {
        val sb: StringBuilder = StringBuilder()
        for (s in items!!) {
            if (s!!.startsWith("0") || s.startsWith("1")) {
                sb.append(SIGNAL).append(s)
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun toCheckListStr(content: String?): String {
        // 普通文本转清单：每一行都是一个一级未完成项。
        val prefix = SIGNAL + STATE_UNFINISHED + "1"
        return prefix + content!!.replace("\n".toRegex(), prefix)
    }

    /**
     * 把"无层级位"的旧清单串升级为带层级位（全部置一级）的新格式。
     * 供数据库迁移（ADR-0010）调用，也用于导入旧数据的兜底归一。纯字符串变换、可单测。
     */
    @JvmStatic
    fun migrateToLeveledFormat(content: String?): String {
        if (content.isNullOrEmpty() || !isCheckListStr(content)) return content ?: ""
        val parts = content.split(SIGNAL.toRegex()).toTypedArray()
        val sb = StringBuilder()
        for (i in 1 until parts.size) {
            val piece = parts[i]
            if (piece.isEmpty()) continue
            val state = piece[0]
            if (state == STATE_UNFINISHED || state == STATE_FINISHED) {
                sb.append(SIGNAL).append(state).append('1').append(piece.substring(1))
            } else {
                sb.append(SIGNAL).append(piece)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // 归属 / 组 / 子树：由"位置 + 层级"派生，不存父指针。
    // 这些方法接受去掉控制标记后的真实项列表（或自动跳过控制标记）。
    // ---------------------------------------------------------------------

    /**
     * 第 [index] 个真实项的归属（owner）下标 —— 它上方最近的、层级严格更浅的真实项；
     * 没有则返回 -1（该项是组根，即一级项）。会跳过控制标记。
     */
    @JvmStatic
    fun ownerIndexOf(items: List<String?>?, index: Int): Int {
        val list = items!!
        if (index < 0 || index >= list.size || !isItem(list[index])) return -1
        val level = levelOf(list[index])
        for (i in index - 1 downTo 0) {
            // 控制标记（分隔/已完成头部/添加行）是区域边界：归属不跨越它，
            // 否则底部已完成区第一项会"认领"到上方未完成区的项上。
            if (!isItem(list[i])) break
            if (levelOf(list[i]) < level) return i
        }
        return -1
    }

    /** 第 [index] 项所属组的组根下标（一路向上找无 owner 的祖先）。孤儿/一级项返回自身。 */
    @JvmStatic
    fun groupRootIndexOf(items: List<String?>?, index: Int): Int {
        var cur = index
        while (true) {
            val owner = ownerIndexOf(items, cur)
            if (owner == -1) return cur
            cur = owner
        }
    }

    /**
     * 第 [index] 项子树的结束下标（含），即它名下连续的、层级更深的真实项一直延伸到哪。
     * 遇到层级 <= 自身、或控制标记即停止。叶子项返回 index。
     */
    @JvmStatic
    fun subtreeEndIndexOf(items: List<String?>?, index: Int): Int {
        val list = items!!
        if (index < 0 || index >= list.size || !isItem(list[index])) return index
        val level = levelOf(list[index])
        var end = index
        var i = index + 1
        while (i < list.size) {
            val it = list[i]
            if (!isItem(it)) break          // 控制标记（分隔/已完成头部/添加行）—— 子树到此为止
            if (levelOf(it) <= level) break  // 同级或更浅 —— 子树结束
            end = i
            i++
        }
        return end
    }

    /** 第 [index] 项子树包含的后代数量（不含自身），用于拖拽收束角标。 */
    @JvmStatic
    fun descendantCountOf(items: List<String?>?, index: Int): Int {
        return subtreeEndIndexOf(items, index) - index
    }

    /**
     * 第 [index] 项能否反缩进：是真实项且层级 > 1。
     */
    @JvmStatic
    fun canOutdent(items: List<String?>?, index: Int): Boolean {
        val list = items!!
        if (index < 0 || index >= list.size || !isItem(list[index])) return false
        return levelOf(list[index]) > 1
    }

    /**
     * 第 [index] 项能否缩进：层级 < 3，且它存在一个"同级的上一个兄弟"（缩进后正好成为该兄弟的子项、
     * 只深一级，不产生跳空或孤儿）。判定方式：自该项向上扫描，遇到的第一个"层级不深于自身"的真实项
     * 必须恰好与自身同级；若它比自身更浅（说明自身已是某父项的第一个子项），则不能缩进。
     * 清单第一项、底部已完成区第一项（上方是控制标记）也都不能缩进。
     */
    @JvmStatic
    fun canIndent(items: List<String?>?, index: Int): Boolean {
        val list = items!!
        if (index < 0 || index >= list.size || !isItem(list[index])) return false
        val level = levelOf(list[index])
        if (level >= MAX_LEVEL) return false
        for (i in index - 1 downTo 0) {
            if (!isItem(list[i])) break  // 控制标记是区域边界，不跨越
            val lv = levelOf(list[i])
            if (lv <= level) {
                return lv == level  // 必须是同级的上一个兄弟，否则缩进会跳空
            }
        }
        return false
    }

    /**
     * 归一化层级：自上而下把每个真实项的层级钳到"它的 owner 层级 + 1"以内（owner 不存在则钳到一级）。
     * 消除"层级跳空 / 孤儿"——反缩进父项、删除中间项、或历史遗留数据里出现的没有直接上一级父项的深层项，
     * 会被自动提升到合法层级。原地修改 [items]，会跳过控制标记。
     */
    @JvmStatic
    fun normalizeLevels(items: MutableList<String?>) {
        for (i in items.indices) {
            if (!isItem(items[i])) continue
            val owner = ownerIndexOf(items, i)
            val maxLevel = if (owner == -1) 1 else levelOf(items[owner]) + 1
            if (levelOf(items[i]) > maxLevel) {
                items[i] = withLevel(items[i]!!, maxLevel)
            }
        }
    }

    /**
     * 归一化完成态，维持不变量「已完成项的所有下属都已完成」：把任何"下属里还有未完成项"的已完成项
     * 改回未完成。自下而上处理，于是被改回的项会连带它的已完成祖先链一起改回（祖先在它之后被处理时，
     * 会发现自己的子树里有了未完成项）；旁系（非祖先）的已完成项不受影响。原地修改 [items]，跳过控制标记。
     * 在缩进（把未完成项移到已完成项下）、原位取消子项、删除等会让已完成项获得未完成下属的操作后调用。
     */
    @JvmStatic
    fun normalizeCompletion(items: MutableList<String?>) {
        for (i in items.indices.reversed()) {
            if (!isItem(items[i]) || !isFinished(items[i])) continue
            val end = subtreeEndIndexOf(items, i)
            var hasUnfinishedDescendant = false
            for (j in i + 1..end) {
                if (isItem(items[j]) && isUnfinished(items[j])) {
                    hasUnfinishedDescendant = true
                    break
                }
            }
            if (hasUnfinishedDescendant) {
                items[i] = withState(items[i]!!, STATE_UNFINISHED)
            }
        }
    }

    @JvmStatic
    fun toggleChecklistItem(checklistStr: String?, itemPos: Int): String? {
        if (itemPos < 0) {
            return checklistStr
        }
        val items: MutableList<String?> = toCheckListItems(checklistStr, false)
        items.remove("2")
        items.remove("3")
        items.remove("4")
        if (itemPos > items.size - 1) {
            return checklistStr
        }

        ChecklistCompletion.toggle(items, itemPos)
        return toCheckListStr(items)
    }

    @JvmStatic
    fun getFirstFinishedItemIndex(items: List<String?>?): Int {
        val size: Int = items!!.size
        for (i in 0 until size) {
            if (items[i]!!.startsWith("1")) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun getLastUnfinishedItemIndex(items: List<String?>?): Int {
        val size: Int = items!!.size
        for (i in size - 1 downTo 0) {
            if (items[i]!!.startsWith("0")) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun onlyOneFinishedItem(items: List<String?>?): Boolean {
        var count = 0
        for (item in items!!) {
            if (item!!.startsWith("1")) {
                count++
                if (count > 1) {
                    return false
                }
            }
        }
        return true
    }

    @JvmStatic
    fun isSignalContainsStrIgnoreCase(str: String?): Boolean {
        if (str!!.isEmpty()) {
            return false
        }
        for (i in 0 until 5) {
            if ((SIGNAL + i).lowercase(Locale.getDefault()).contains(str.lowercase(Locale.getDefault()))) {
                return true
            }
        }
        return false
    }
}
