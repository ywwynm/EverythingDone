package com.ywwynm.everythingdone.activities

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.thingfile.PendingThing
import com.ywwynm.everythingdone.thingfile.ThingFileImporter
import com.ywwynm.everythingdone.thingfile.ThingFileMessages
import com.ywwynm.everythingdone.thingfile.ThingFileReader
import com.ywwynm.everythingdone.thingfile.ThingFingerprint
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

import java.io.File
import java.util.concurrent.Executors

/**
 * 记事文件的导入界面。
 *
 * 两条进入路径：文件管理器里把 `.edthing` 分享给完事儿（`ACTION_SEND`），或者
 * 设置里的「导入记事文件」自己挑（`ACTION_VIEW` 带一个 content Uri）。不依赖
 * 文件类型关联——跨应用传来的 `content://` 路径通常不含扩展名，按 `pathPattern`
 * 匹配匹配不上。
 *
 * 导入是不可撤销的（ADR-0005 已全局移除撤销机制），所以一律先列出来让用户逐条确认。
 */
class ThingFileImportActivity : EverythingDoneBaseActivity() {

    companion object {
        private const val REQUEST_PICK_THING_FILE: Int = 1
    }

    private var toolbar: Toolbar? = null
    private var recyclerView: RecyclerView? = null
    private var confirm: TextView? = null
    private var loading: ProgressBar? = null
    private var message: TextView? = null

    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val items = ArrayList<PendingThing>()
    private val checked = HashSet<Int>()
    private var tempDir: File? = null
    private var importing = false

    /** 用户在文件选择器里重选的那一个，优先于 Intent 带进来的。 */
    private var pickedUri: Uri? = null

    override fun getLayoutResource(): Int = R.layout.activity_thing_file_import

    override fun initMembers() = Unit

    override fun findViews() {
        toolbar = f(R.id.actionbar)
        recyclerView = f(R.id.rv_import_things)
        confirm = f(R.id.tv_import_confirm)
        loading = f(R.id.pb_import_loading)
        message = f(R.id.tv_import_message)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(f(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        val accent = App.defaultAccentBackground
        BackgroundUtil.applyBackground(f<View>(R.id.view_status_bar), accent)
        BackgroundUtil.applyBackground(toolbar, accent)

        recyclerView?.layoutManager = LinearLayoutManager(this)
        recyclerView?.adapter = Adapter()
        recyclerView?.visibility = View.GONE
        confirm?.visibility = View.GONE
        confirm?.setTextColor(accent.color)
        // targetSdk 36 强制内容画到导航栏下面。不让开的话，手势导航下按钮压在手势条下，
        // 三键导航下整条被导航栏盖住、点不到，导入就做不了。
        DisplayUtil.applyBottomInsetAsMargin(confirm)

        startReading()
    }

    override fun setActionbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.thing_file_import_title)
        toolbar?.setNavigationOnClickListener { finish() }
        val fg = BackgroundUtil.onColor(App.defaultAccentBackground, BackgroundUtil.ON_ALPHA_PRIMARY)
        toolbar?.setTitleTextColor(fg)
        toolbar?.navigationIcon?.setTint(fg)
    }

    override fun setEvents() {
        confirm?.setOnClickListener { startImporting() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 临时目录里可能躺着上百 MB 的附件，无论导入与否都要清掉
        if (!importing) {
            ThingFileReader.cleanUp(tempDir)
        }
        // 已提交的读取或导入会照常跑完（它们自己负责清理），之后线程随之结束，不再常驻
        background.shutdown()
    }

    // ------------------------------------------------------------------ 读取

    private fun startReading() {
        val uri = extractUri()
        if (uri == null) {
            showMessage(getString(R.string.thing_file_import_not_thing_file))
            return
        }
        loading?.visibility = View.VISIBLE
        message?.visibility = View.VISIBLE
        message?.text = getString(R.string.thing_file_import_reading)

        background.execute {
            // 上一次导入中途进程被杀时留下的临时目录，只有这里还有机会清
            ThingFileReader.cleanUpStale(this)
            val result = try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        ThingFileReader.Result.Broken(
                            getString(R.string.thing_file_error_cannot_open)
                        )
                    } else {
                        ThingFileReader.read(this, input)
                    }
                }
            } catch (e: SecurityException) {
                ThingFileReader.Result.NoAccess
            } catch (e: Exception) {
                // 分区存储下读别的应用目录里的 file:// 路径会以 EACCES 落到这里
                // （通常包在 FileNotFoundException 里）
                if (isPermissionDenied(e)) {
                    ThingFileReader.Result.NoAccess
                } else {
                    ThingFileReader.Result.Broken(
                        ThingFileMessages.of(this, R.string.thing_file_error_cannot_open, e.message)
                    )
                }
            }
            if (result is ThingFileReader.Result.Ok) {
                // 指纹要遍历本机记事，一并放在后台线程
                ThingFingerprint.markDuplicates(this, result.things)
            }
            main.post { onRead(result) }
        }
    }

    private fun onRead(result: ThingFileReader.Result) {
        if (isFinishing || isDestroyed) {
            if (result is ThingFileReader.Result.Ok) {
                ThingFileReader.cleanUp(result.tempDir)
            }
            return
        }
        loading?.visibility = View.GONE
        when (result) {
            is ThingFileReader.Result.Ok -> {
                tempDir = result.tempDir
                items.addAll(result.things)
                // 与本机重复的默认不勾选，其余默认勾上
                items.forEachIndexed { index, item ->
                    if (!item.duplicate) {
                        checked.add(index)
                    }
                }
                message?.visibility = View.GONE
                recyclerView?.visibility = View.VISIBLE
                confirm?.visibility = View.VISIBLE
                recyclerView?.adapter?.notifyDataSetChanged()
                updateConfirm()
            }
            is ThingFileReader.Result.NotThingFile ->
                showMessage(getString(R.string.thing_file_import_not_thing_file))
            is ThingFileReader.Result.TooNew ->
                showMessage(getString(R.string.thing_file_import_too_new))
            is ThingFileReader.Result.NoAccess -> {
                // 直接读不了就给一条走得通的路，而不是只报一句权限不足
                showMessage(getString(R.string.thing_file_import_no_access))
                confirm?.visibility = View.VISIBLE
                confirm?.isEnabled = true
                confirm?.setText(R.string.thing_file_import_pick)
                confirm?.setOnClickListener { startPickFile() }
            }
            is ThingFileReader.Result.Broken ->
                showMessage(getString(R.string.thing_file_import_broken, result.message))
        }
    }

    /**
     * 让用户在系统文件选择器里选中同一个文件。记事文件是自定义扩展名，系统的
     * MimeTypeMap 认不出，按具体 MIME 过滤会把文件灰掉，所以只能开成 * / *。
     */
    private fun startPickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_PICK_THING_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_THING_FILE || resultCode != RESULT_OK) {
            return
        }
        val uri = data?.data ?: return
        pickedUri = uri
        // 重置到初始状态再读一遍：上一次失败留下的提示与按钮都要清掉
        items.clear()
        checked.clear()
        confirm?.setOnClickListener { startImporting() }
        confirm?.visibility = View.GONE
        recyclerView?.adapter?.notifyDataSetChanged()
        startReading()
    }

    private fun showMessage(text: String) {
        loading?.visibility = View.GONE
        recyclerView?.visibility = View.GONE
        confirm?.visibility = View.GONE
        message?.visibility = View.VISIBLE
        message?.text = text
    }

    private fun extractUri(): Uri? {
        pickedUri?.let { return it }
        val i = intent ?: return null
        return when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
    }

    /** `open failed: EACCES` 会包在 FileNotFoundException 之类里，只能按消息认。 */
    private fun isPermissionDenied(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("EACCES") || m.contains("Permission denied")
                || m.contains("has no access")
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    // ------------------------------------------------------------------ 导入

    private fun startImporting() {
        if (importing || checked.isEmpty()) {
            return
        }
        importing = true
        confirm?.isEnabled = false
        val selected = checked.sorted().map { items[it] }
        val dir = tempDir
        background.execute {
            val result = ThingFileImporter.import(this, selected)
            ThingFileReader.cleanUp(dir)
            main.post {
                val text = if (result.failed == 0) {
                    getString(R.string.thing_file_import_done, result.imported)
                } else {
                    getString(
                        R.string.thing_file_import_partial, result.imported, result.failed
                    )
                }
                Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                if (result.imported > 0) {
                    notifyMainUi()
                }
                finish()
            }
        }
    }

    /**
     * 导入直接写了数据库与 ThingManager 的内存列表，首页的列表需要整体重建。
     * `justNotifyAll` 正是「不知道具体改了哪几条，全部重来」这一档，与从详情页回来的
     * 单条更新路径不同。
     */
    private fun notifyMainUi() {
        App.setJustNotifyAll(true)
        val intent = Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun updateConfirm() {
        val count = checked.size
        confirm?.text = if (count == 0) {
            getString(R.string.thing_file_import_confirm_none)
        } else {
            getString(R.string.thing_file_import_confirm, count)
        }
        confirm?.isEnabled = count > 0
    }

    // ------------------------------------------------------------------ 列表

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_thing_file_import, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val thing = item.thing

            holder.checkBox.isChecked = checked.contains(position)
            BackgroundUtil.applyBackground(holder.colorBar, thing.getBackground())

            val title = thing.getTitleToDisplay()?.trim()
            holder.title.text = if (!title.isNullOrEmpty()) {
                title
            } else {
                var content = thing.content ?: ""
                if (CheckListHelper.isCheckListStr(content)) {
                    content = CheckListHelper.toContentStr(content, "", "")
                }
                content = content.replace("\n", " ").trim()
                content.ifEmpty { getString(R.string.thing_file_import_no_title) }
            }

            val parts = ArrayList<String>()
            parts.add(Thing.getTypeStr(thing.type, this@ThingFileImportActivity) ?: "")
            if (item.attachmentFiles.isNotEmpty()) {
                parts.add(
                    getString(
                        R.string.thing_file_import_attachments, item.attachmentFiles.size
                    )
                )
            }
            item.reminder?.let {
                parts.add(
                    getString(
                        R.string.thing_file_import_reminder_at,
                        DateTimeUtil.getDateTimeStrAt(
                            it.notifyTime, this@ThingFileImportActivity, false
                        )
                    )
                )
            }
            if (item.duplicate) {
                parts.add(getString(R.string.thing_file_import_duplicate))
            }
            holder.info.text = parts.filter { it.isNotEmpty() }.joinToString(" · ")

            holder.itemView.setOnClickListener {
                if (checked.contains(position)) {
                    checked.remove(position)
                } else {
                    checked.add(position)
                }
                notifyItemChanged(position)
                updateConfirm()
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.cb_import_thing)
        val colorBar: View = view.findViewById(R.id.v_import_thing_color)
        val title: TextView = view.findViewById(R.id.tv_import_thing_title)
        val info: TextView = view.findViewById(R.id.tv_import_thing_info)
    }
}
