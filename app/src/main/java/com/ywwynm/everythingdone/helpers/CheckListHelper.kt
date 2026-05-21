package com.ywwynm.everythingdone.helpers

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R

import java.util.ArrayList
import java.util.Arrays
import java.util.Locale

/**
 * Created by ywwynm on 2015/9/17.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Convert content string into check list items and vice versa.
 */
object CheckListHelper {

    const val TAG: String = "CheckListHelper"

    const val SIGNAL_LENGTH: Int   = 4
    const val CHECK_STATE_NUM: Int = 5

    // cannot write hardcoded signal after updated to Jack compiler with Java 8
    @JvmField
    val SIGNAL: String = App.getApp()!!.getString(R.string.base_signal_upper)

    @JvmStatic
    fun isCheckListStr(s: String?): Boolean {
        return s!!.length >= SIGNAL_LENGTH && s.substring(0, SIGNAL_LENGTH) == SIGNAL
    }

    @JvmStatic
    fun toCheckListItems(s: String?, convert: Boolean): MutableList<String?> {
        var str: String = s!!
        if (convert) {
            str = toCheckListStr(str)!!
        }
        val strs: Array<String> = str.split(SIGNAL.toRegex()).toTypedArray()
        val items: MutableList<String?> = ArrayList()
        items.addAll(Arrays.asList(*strs).subList(1, strs.size))

        var firstFinishedIndex: Int = -1
        val size: Int = items.size
        for (i in 0 until size) {
            if (items[i]!!.startsWith("1")) {
                firstFinishedIndex = i
                break
            }
        }
        if (firstFinishedIndex != -1) {
            items.add(firstFinishedIndex, "2")
            items.add(firstFinishedIndex + 1, "3")
            items.add(firstFinishedIndex + 2, "4")
        } else {
            items.add("2")
        }

        return items
    }

    @JvmStatic
    fun toContentStr(items: List<String?>?): String {
        val checkListStr: String = toCheckListStr(items)!!
        return toContentStr(checkListStr, "", "")
    }

    @JvmStatic
    fun toContentStr(checkListStr: String?, unchecked: String?, checked: String?): String {
        if (!checkListStr!!.contains(SIGNAL + 0) && !checkListStr.contains(SIGNAL + 1)) {
            return ""
        } else {
            val signal: Char = checkListStr[SIGNAL_LENGTH]
            var result: String = checkListStr.substring(SIGNAL_LENGTH + 1, checkListStr.length)
            result = if (signal == '0') {
                unchecked + result
            } else checked + result

            result = result.replace((SIGNAL + 0).toRegex(), "\n" + unchecked)
            result = result.replace((SIGNAL + 1).toRegex(), "\n" + checked)
            result = result.replace(SIGNAL + 2, "")
            result = result.replace(SIGNAL + 3, "")
            result = result.replace(SIGNAL + 4, "")

            return result
        }
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
        return SIGNAL + 0 + content!!.replace("\n".toRegex(), SIGNAL + 0)
    }

    @JvmStatic
    fun toggleChecklistItem(checklistStr: String?, itemPos: Int): String? {
        if (itemPos < 0) {
            return checklistStr
        }
        val items: MutableList<String?> = toCheckListItems(checklistStr, false)!!
        items.remove("2")
        items.remove("3")
        items.remove("4")
        if (itemPos > items.size - 1) {
            return checklistStr
        }

        val oldItem: String = items[itemPos]!!
        items.removeAt(itemPos)
        if (oldItem.startsWith("0")) { // unfinished to finished
            val newItem: String = "1" + oldItem.substring(1, oldItem.length)
            val firstFinishedIndex: Int = getFirstFinishedItemIndex(items)
            if (firstFinishedIndex == -1) {
                items.add(newItem)
            } else {
                items.add(firstFinishedIndex, newItem)
            }
        } else {
            val newItem: String = "0" + oldItem.substring(1, oldItem.length)
            items.add(0, newItem)
        }
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
