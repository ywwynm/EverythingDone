package com.ywwynm.everythingdone.thingfile

import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import java.io.File

/**
 * 记事文件（Thing File）的格式常量。
 *
 * 产物是一个 zip：
 *
 * ```
 * things.json                          清单
 * attachments/<i>/<n>_<原文件名>        第 i 条记事的第 n 个附件
 * ```
 *
 * 它是给另一个人的一次性副本，接收方必须安装本应用才能用。与「导出到 SD 卡」
 * 产出的可阅读 txt 是两回事，后者不可还原。
 *
 * 决策见 `docs/features/thing-transfer/decisions.md`。
 */
object ThingFile {

    /** 清单里的固定标识，用来在导入时快速否掉不是本格式的 zip。 */
    const val FORMAT: String = "everythingdone.thing-file"

    /**
     * 格式版本。读到比本机大的版本一律拒绝并提示升级；小于等于本机版本则接受，
     * 清单里不认识的字段直接忽略。
     */
    const val VERSION: Int = 1

    const val EXTENSION: String = "edthing"

    const val ENTRY_MANIFEST: String = "things.json"

    const val ATTACHMENT_DIR: String = "attachments"

    /** 产物确实是 zip，交给系统分享面板时用这个类型。 */
    const val MIME_TYPE: String = "application/zip"

    // ---- 清单字段名。改动等于改格式，要同步升 VERSION ----

    const val KEY_FORMAT: String = "format"
    const val KEY_VERSION: String = "version"
    const val KEY_APP_VERSION_CODE: String = "appVersionCode"
    const val KEY_EXPORT_TIME: String = "exportTime"
    const val KEY_THINGS: String = "things"

    const val KEY_TYPE: String = "type"
    const val KEY_STATE: String = "state"
    const val KEY_TITLE: String = "title"
    const val KEY_CONTENT: String = "content"
    const val KEY_CREATE_TIME: String = "createTime"
    const val KEY_UPDATE_TIME: String = "updateTime"
    const val KEY_FINISH_TIME: String = "finishTime"
    const val KEY_BACKGROUND: String = "background"
    const val KEY_CARD_APPEARANCE: String = "cardAppearance"
    const val KEY_DETAIL_APPEARANCE: String = "detailAppearance"
    const val KEY_ATTACHMENTS: String = "attachments"
    const val KEY_REMINDER: String = "reminder"
    const val KEY_HABIT: String = "habit"

    const val KEY_ATTACHMENT_TYPE: String = "type"
    const val KEY_ATTACHMENT_NAME: String = "name"
    const val KEY_ATTACHMENT_ENTRY: String = "entry"

    const val KEY_REMINDER_NOTIFY_TIME: String = "notifyTime"
    const val KEY_REMINDER_STATE: String = "state"
    const val KEY_REMINDER_NOTIFY_MILLIS: String = "notifyMillis"

    /**
     * 习惯只带**周期定义**：[KEY_HABIT_TYPE] 与 [KEY_HABIT_DETAIL]。
     *
     * `record`、`intervalInfo`、`firstTime`、`remindedTimes` 都是运行时累积的状态
     * （新建习惯时它们本来就是空串 / 0，`firstTime` 由 `initHabitReminders()` 算出），
     * 属于发送方的履历而不是这条习惯的定义，一律不进文件。
     */
    const val KEY_HABIT_TYPE: String = "type"
    const val KEY_HABIT_DETAIL: String = "detail"

    /** 单条记事文件名取标题或正文的前多少个字符（按 Unicode 码位计，不按 UTF-16 单元）。 */
    const val FILE_NAME_MAX_CODE_POINTS: Int = 16

    private val FILE_NAME_BLANKS = Regex("[\\s\\p{Cntrl}]+")

    /**
     * 把清单里的类型与它带的提醒、习惯对齐，返回应当写入的类型；类型本身不认识时返回 null，
     * 这一条整条跳过。
     *
     * 正常导出的文件三者总是一致。不一致只出现在畸形文件里，或发送方的数据本身就不一致——
     * 应用里有 `RemoteActionHelper.correctIfNoReminder` / `correctIfNoHabit` 专门纠正这种情形，
     * 做法是改成 NOTE，这里照同一规则。原样导入的后果：HABIT 没有习惯行时，详情页
     * `onCreateOptionsMenu` 对习惯做非空断言，点开即崩；NOTE 带着提醒会在笔记上响闹钟。
     *
     * 调用方据返回值决定留不留提醒与习惯：只有 REMINDER / GOAL 留提醒，只有 HABIT 留习惯。
     */
    @JvmStatic
    fun normalizeType(type: Int, hasReminder: Boolean, hasHabit: Boolean): Int? {
        return when (type) {
            Thing.NOTE -> Thing.NOTE
            Thing.REMINDER, Thing.GOAL -> if (hasReminder) type else Thing.NOTE
            Thing.HABIT -> if (hasHabit) Thing.HABIT else Thing.NOTE
            else -> null
        }
    }

    /**
     * 从标题或正文里取文件名主干：空白与控制字符合并成一个空格，再按码位截到
     * [FILE_NAME_MAX_CODE_POINTS] 个。
     *
     * 不能按 UTF-16 单元截：第 16 个单元落在 emoji 中间时会留下不成对的代理项，
     * FileProvider 生成 URI 时 `Uri.encode` 把它换成 `?`，对方按 URI 找不到文件。
     */
    @JvmStatic
    fun fileNameStem(raw: String?): String {
        var s = (raw ?: "").replace(FILE_NAME_BLANKS, " ").trim()
        if (s.codePointCount(0, s.length) > FILE_NAME_MAX_CODE_POINTS) {
            s = s.substring(0, s.offsetByCodePoints(0, FILE_NAME_MAX_CODE_POINTS)).trim()
        }
        return s
    }
}

/**
 * 从记事文件里读出来、尚未写进数据库的一条记事。
 *
 * [thing]、[reminder]、[habit] 的 id 都还是 0，由导入时统一分配；
 * [thing] 的 `attachment` 串此刻指向 [attachmentFiles] 所在的临时目录，
 * 真正导入时要复制到附件目录并重写成最终路径。
 */
class PendingThing(
    val thing: Thing,
    val reminder: Reminder?,
    val habit: Habit?,
    /** 已解压到临时目录的附件，顺序与 [thing] 的 attachment 串一致。 */
    val attachmentFiles: List<File>,
    /** 与 [attachmentFiles] 同序：每个附件的类型（`AttachmentHelper.IMAGE` / `VIDEO` / `AUDIO`）。 */
    val attachmentTypes: List<Int>,
    /**
     * 与 [attachmentFiles] 同序：每个附件在记事文件里的条目名。
     *
     * [thing] 的两份外观设置此刻仍以条目名作键（导出时换过一次），导入器复制完附件后
     * 要用它把键换成本机的新 typePathName，否则附件显示比例、裁剪、卡片封面等会全部丢失。
     */
    val attachmentKeys: List<String>,
    /** 与本机已有记事的内容指纹重复时为 true，预览列表据此默认不勾选。 */
    var duplicate: Boolean = false
)
