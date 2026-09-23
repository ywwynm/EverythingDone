package com.ywwynm.everythingdone.thingfile

import com.ywwynm.everythingdone.model.Thing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 记事文件里两个不依赖 Android 的规则：清单类型的规整、分享文件名的截取。
 */
class ThingFileHelpersTest {

    // ------------------------------------------------------------ 类型规整

    @Test
    fun normalizeType_keepsConsistentTypes() {
        assertEquals(Thing.NOTE, ThingFile.normalizeType(Thing.NOTE, false, false))
        assertEquals(Thing.REMINDER, ThingFile.normalizeType(Thing.REMINDER, true, false))
        assertEquals(Thing.GOAL, ThingFile.normalizeType(Thing.GOAL, true, false))
        assertEquals(Thing.HABIT, ThingFile.normalizeType(Thing.HABIT, false, true))
    }

    /**
     * 提醒类型却没带提醒、习惯类型却没带习惯：照应用里 correctIfNoReminder / correctIfNoHabit
     * 的做法改成 NOTE。原样导入的 HABIT 在详情页点开即崩。
     */
    @Test
    fun normalizeType_fallsBackToNoteWhenReminderOrHabitIsMissing() {
        assertEquals(Thing.NOTE, ThingFile.normalizeType(Thing.REMINDER, false, false))
        assertEquals(Thing.NOTE, ThingFile.normalizeType(Thing.GOAL, false, false))
        assertEquals(Thing.NOTE, ThingFile.normalizeType(Thing.HABIT, false, false))
    }

    /** NOTE 不会因为清单里多带了提醒或习惯就变成别的类型；调用方据此把多余的提醒与习惯丢掉。 */
    @Test
    fun normalizeType_noteStaysNoteEvenWithExtras() {
        assertEquals(Thing.NOTE, ThingFile.normalizeType(Thing.NOTE, true, true))
    }

    /** 表头、欢迎、通知、占位这些非用户类型，以及未知整数，一律整条跳过。 */
    @Test
    fun normalizeType_rejectsNonUserTypes() {
        assertNull(ThingFile.normalizeType(Thing.HEADER, false, false))
        assertNull(ThingFile.normalizeType(Thing.WELCOME_UNDERWAY, false, false))
        assertNull(ThingFile.normalizeType(Thing.NOTIFICATION_NOTE, true, false))
        assertNull(ThingFile.normalizeType(Thing.NOTIFY_EMPTY_DELETED, false, false))
        assertNull(ThingFile.normalizeType(99, false, false))
    }

    // ------------------------------------------------------------ 文件名

    /**
     * 15 个字母 + 1 个 emoji：16 个码位、17 个 UTF-16 单元。旧写法 `substring(0, 16)` 会把
     * emoji 切成半个，留下不成对的代理项；按码位数则不需要截。
     */
    @Test
    fun fileNameStem_neverSplitsASurrogatePair() {
        val raw = "abcdefghijklmno😀"
        val stem = ThingFile.fileNameStem(raw)

        assertEquals(raw, stem)
        assertFalse(hasUnpairedSurrogate(stem))
    }

    @Test
    fun fileNameStem_truncatesByCodePoints() {
        val stem = ThingFile.fileNameStem("abcdefghijklmno😀😀xyz")

        assertEquals("abcdefghijklmno😀", stem)
        assertEquals(ThingFile.FILE_NAME_MAX_CODE_POINTS, stem.codePointCount(0, stem.length))
        assertFalse(hasUnpairedSurrogate(stem))
    }

    @Test
    fun fileNameStem_collapsesBlanksAndControlCharacters() {
        assertEquals("a b c d", ThingFile.fileNameStem("  a\nb\r\n\tc\u0007d  "))
    }

    @Test
    fun fileNameStem_trimsSpaceLeftAtTheCut() {
        assertEquals("123456789012345", ThingFile.fileNameStem("123456789012345 6789"))
    }

    @Test
    fun fileNameStem_handlesNull() {
        assertEquals("", ThingFile.fileNameStem(null))
    }

    private fun hasUnpairedSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }
}
