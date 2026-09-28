package com.ywwynm.everythingdone.thingfile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.FileUtil

import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 把记事写成 [ThingFile] 并交给系统分享面板。
 *
 * 碰一碰这件事不归我们做：用户在分享面板里选互传，触碰由系统完成。我们只负责产出
 * 那个文件。
 *
 * **私密鉴权由调用方负责，这里不做**（ADR-0011：集中入口在调用方）。详情页能看到内容说明已经
 * 通过鉴权，直接调用（访问即信任，与同一对话框里的「文本与图片」「长截图」一致）；选择模式由
 * `ThingsActivity.shareSelectedThingsAsThingFile` 先按 `ThingPrivacyResolver.isEffectivelyPrivate`
 * 鉴权再调用。
 */
object ThingFileSharer {

    private const val AUTHORITY: String = "com.ywwynm.everythingdone"

    private const val OUTPUT_DIR: String = "/thing_file"

    private val sExecutor = Executors.newSingleThreadExecutor()

    private val sMain = Handler(Looper.getMainLooper())

    /**
     * @param keptAttachments 每条记事保留哪些附件（键是 [Thing.id]），null 表示全部保留。
     */
    @JvmStatic
    @JvmOverloads
    fun share(
        activity: FragmentActivity,
        accentBackground: ThingBackground?,
        things: List<Thing>,
        keptAttachments: Map<Long, Set<String>>? = null
    ) = share(activity, accentBackground, ThingShareContent.ofThings(things), keptAttachments)

    /** 带文件夹的分享（决策 2026-09-28）：文件夹连同子树写进同一个文件。 */
    @JvmStatic
    @JvmOverloads
    fun share(
        activity: FragmentActivity,
        accentBackground: ThingBackground?,
        content: ThingShareContent,
        keptAttachments: Map<Long, Set<String>>? = null
    ) {
        if (content.things.isEmpty()) {
            return
        }
        val background = accentBackground ?: App.defaultAccentBackground
        startGenerating(activity, background, content, keptAttachments)
    }

    private fun startGenerating(
        activity: FragmentActivity,
        background: ThingBackground,
        content: ThingShareContent,
        keptAttachments: Map<Long, Set<String>>?
    ) {
        val ldf = LoadingDialogFragment()
        ldf.setAccentBackground(background)
        ldf.setTitle(activity.getString(R.string.please_wait))
        ldf.setContent(activity.getString(R.string.generating_thing_file))
        ldf.show(activity.supportFragmentManager, LoadingDialogFragment.TAG)

        val wrActivity = WeakReference(activity)
        val wrLdf = WeakReference(ldf)
        val appContext = activity.applicationContext
        // 附件全部内嵌，100 MB 的记事也照写不误，所以一定在后台线程做
        sExecutor.execute {
            val dst = prepareOutputFile(appContext, content)
            val result = if (dst == null) {
                ThingFileWriter.Result.Failed(
                    appContext.getString(R.string.thing_file_error_output_file)
                )
            } else {
                ThingFileWriter.write(appContext, content, dst, keptAttachments)
            }
            sMain.post {
                wrLdf.get()?.dismiss()
                val host = wrActivity.get() ?: return@post
                if (host.isFinishing || host.isDestroyed) {
                    return@post
                }
                when (result) {
                    is ThingFileWriter.Result.Ok -> sendFile(host, result.file)
                    is ThingFileWriter.Result.Failed -> Toast.makeText(
                        host,
                        host.getString(R.string.thing_file_generate_failed, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun sendFile(activity: FragmentActivity, file: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(activity, AUTHORITY, file)
        } catch (e: IllegalArgumentException) {
            val reason = ThingFileMessages.of(
                activity, R.string.thing_file_error_output_file, e.message
            )
            Toast.makeText(
                activity,
                activity.getString(R.string.thing_file_generate_failed, reason),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = ThingFile.MIME_TYPE
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 对话框里放不下副文案（三项等宽、maxLines=2），限制在这里告知
        Toast.makeText(activity, R.string.thing_file_share_hint, Toast.LENGTH_LONG).show()
        activity.startActivity(
            Intent.createChooser(intent, activity.getString(R.string.act_share_thing_file))
        )
    }

    /**
     * 产出目录每次只留当前这一个文件——附件内嵌后单个文件可能上百 MB，堆着会占满存储。
     */
    private fun prepareOutputFile(context: Context, content: ThingShareContent): File? {
        val dir = File(Def.getAppFileDir(context) + OUTPUT_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
        return FileUtil.createFile(dir.absolutePath, fileName(context, content))
    }

    /** 一项时取它的标题（记事没有标题时取正文，文件夹取文件夹名）；多项时是「完事儿-项数-时刻」。 */
    private fun fileName(context: Context, content: ThingShareContent): String {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val items = content.items
        if (items.size > 1) {
            return context.getString(R.string.everythingdone) + "-" +
                    items.size + "-" + stamp + "." + ThingFile.EXTENSION
        }
        val only = items[0]
        if (only is ThingShareContent.FolderNode) {
            var folderName = ThingFile.fileNameStem(only.folder.title)
            if (folderName.isEmpty() || !FileUtil.isAppropriateAsFileName(folderName)) {
                folderName = context.getString(R.string.everythingdone)
            }
            return "$folderName-$stamp.${ThingFile.EXTENSION}"
        }
        val thing = (only as ThingShareContent.ThingNode).thing
        var name = ThingFile.fileNameStem(thing.getTitleToDisplay())
        if (name.isEmpty()) {
            var content = thing.content ?: ""
            if (CheckListHelper.isCheckListStr(content)) {
                content = CheckListHelper.toContentStr(content, "", "")
            }
            name = ThingFile.fileNameStem(content)
        }
        if (name.isEmpty() || !FileUtil.isAppropriateAsFileName(name)) {
            name = context.getString(R.string.everythingdone)
        }
        return "$name-$stamp.${ThingFile.EXTENSION}"
    }
}
