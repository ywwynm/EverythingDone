package com.ywwynm.everythingdone.thingfile

import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
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
 * 版本 2 起可以带文件夹（决策 2026-09-28）：清单多一个 [KEY_FOLDERS] 数组，记事与文件夹各自记着所在的
 * 文件夹（[KEY_IN_FOLDER]，缺省为顶层）、在其中的先后（[KEY_ORDER]）与是否置顶（[KEY_STICKY]）。
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
     *
     * 1：只有记事。2：带文件夹。只含记事的文件仍写 [VERSION_THINGS_ONLY]，还没升级的接收方照样能导入；
     * 带文件夹的写 2，旧版本收到时提示升级，而不是把结构丢掉、把记事平铺进来（决策 2026-09-28 第 10 条）。
     */
    const val VERSION: Int = 2

    /** 只含记事的文件写这个版本。 */
    const val VERSION_THINGS_ONLY: Int = 1

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

    /** 版本 2：文件夹，按先父后子排（上一级总在前面）。 */
    const val KEY_FOLDERS: String = "folders"

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

    /** 文件夹在这份文件里的编号，记事与子文件夹用 [KEY_IN_FOLDER] 指向它。 */
    const val KEY_FOLDER_KEY: String = "key"
    const val KEY_CARD_PRESENTATION: String = "cardPresentation"

    /** 记事与文件夹共用：所在文件夹的 [KEY_FOLDER_KEY]，缺省表示顶层。 */
    const val KEY_IN_FOLDER: String = "folder"

    /** 记事与文件夹共用：在所在的一层里的先后，小的在前（列表顺序）。 */
    const val KEY_ORDER: String = "order"

    /** 记事与文件夹共用：在所在的文件夹里置顶。顶层的项不看它：收到的一律不置顶。 */
    const val KEY_STICKY: String = "sticky"

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
    var duplicate: Boolean = false,
    /** 所在文件夹的编号（[PendingFolder.key]）；null 表示顶层。 */
    val folderKey: Int? = null,
    /** 在所在的一层里的先后，小的在前。 */
    val order: Int = 0,
    /** 在所在的文件夹里置顶（顶层不看）。 */
    val sticky: Boolean = false
)

/**
 * 从记事文件里读出来、尚未写进数据库的一个文件夹（版本 2）。私密标记不随文件走（决策 2026-09-28 第 6 条），
 * 导入的文件夹一律不是私密的。
 */
class PendingFolder(
    /** 这份文件里的编号，只在这一份文件里有意义。 */
    val key: Int,
    /** 上一级文件夹的编号；null 表示顶层。 */
    val parentKey: Int?,
    val title: String,
    val background: ThingBackground,
    val cardPresentation: ThingFolderCardPresentation,
    val createTime: Long,
    val updateTime: Long,
    /** 在所在的一层里的先后，小的在前。 */
    val order: Int,
    /** 在上一级文件夹里置顶（顶层不看）。 */
    val sticky: Boolean
)
