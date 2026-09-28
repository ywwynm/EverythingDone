package com.ywwynm.everythingdone.taptransfer.transport.sim

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapIncomingOffer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import com.ywwynm.everythingdone.taptransfer.ui.card.TapThingCards
import com.ywwynm.everythingdone.thingfile.ThingFileWriter
import com.ywwynm.everythingdone.thingfile.ThingShareContent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 调试包里的模拟接收方：进入接收状态几秒后，模拟一台手机碰上来、发来几条样例记事
 * （标题写明是模拟），走完整的导入与拼出（决策 2026-09-24）。样例也可以是文件夹（[Variant.FOLDER]、
 * [Variant.BIGFOLDER]），里面带一条置顶的记事与一个子文件夹（子文件夹里第二条是一条已过期的提醒，用来核对按类型
 * 筛选分享），用来核对文件夹的传输（决策 2026-09-28）。
 *
 * 发来哪几条由调试配置 `simulate_samples` 决定（[TapTransfer.simulatedSamples]），每一项是一种
 * 卡片样式（[Variant]）：纯文字（普通或通栏），或者带一张图片、封面在上 / 下 / 左 / 右 / 作为卡片背景。
 * 每一项还可以带封面的比例与宽度（[Spec]），用来核对各种封面设置下的卡片。图片在本机现画，不依赖设备上
 * 已有的文件。碰上时与真实发送方一样，把样例卡片按本机列表的列宽量出的形状放进来件信息（接收方据此预计落点）。
 *
 * 样例记事真的写成记事文件、真的经 [com.ywwynm.everythingdone.thingfile.ThingFileReader] 读、
 * 经导入器写进数据库——除了 NFC 与 Wi-Fi Direct，走的都是真实的路径。每次进入接收状态只模拟
 * 一次，不会一直往列表里添样例。
 */
class SimulatedReceiverTransport(private val activity: Activity) : TapReceiverTransport {

    private val context: Context = activity.applicationContext

    /** 一条样例的卡片样式。 */
    enum class Variant(val key: String) {
        PLAIN("plain"),
        TOP("top"),
        BOTTOM("bottom"),

        /** 封面在左、右只对宽卡片有效，这两种样例都是宽卡片。 */
        LEFT("left"),
        RIGHT("right"),

        /** 图片作为卡片背景。 */
        BACKGROUND("background"),

        /** 通栏的纯文字卡片。 */
        WIDE("wide"),

        /** 十几项的清单（有两级、有已完成的），看清单条数上限。 */
        LIST("list"),

        /** 二十来行的正文，看正文行数上限。 */
        TEXT("text"),

        /**
         * 文件夹（「正常」形态）：几条纯文字记事，第一条置顶，最后是一个带两条记事的子文件夹。
         * `/N` 表示里面几条记事（默认 [SAMPLE_FOLDER_THINGS]），`!` 表示通栏。
         */
        FOLDER("folder"),

        /** 同上，「大」形态（带缩略图）。 */
        BIGFOLDER("bigfolder")
    }

    /**
     * 一条样例：样式，加上可选的封面比例（宽 / 高）、侧边封面的宽度百分比、是否通栏与内容。写法是
     * `样式[@比例][/宽度百分比][!][+内容]`，`!` 表示通栏，内容是 `text`（长正文）或 `list`（长清单），
     * 例如 `background@0.6`、`background@1.8!`、`left@0.8/35`、`top@2`、`top@0.8+text`。比例按样式落到
     * 对应的封面呈现上：上 / 下是缩略图、左 / 右是侧栏、背景是封面背景。
     */
    class Spec(val variant: Variant, val ratio: Double?, val sidePercent: Int?, val fullSpan: Boolean,
               val content: Variant? = null) {
        companion object {
            private val PATTERN = Regex("([a-z]+)(?:@([0-9.]+))?(?:/([0-9]+))?(!)?(?:\\+(text|list))?")

            fun parse(spec: String?): List<Spec> {
                val parsed = spec.orEmpty().split(',').mapNotNull { token ->
                    val m = PATTERN.matchEntire(token.trim()) ?: return@mapNotNull null
                    val variant = Variant.values().firstOrNull { it.key == m.groupValues[1] }
                        ?: return@mapNotNull null
                    Spec(variant, m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0.0 },
                        m.groupValues[3].toIntOrNull(), m.groupValues[4] == "!",
                        Variant.values().firstOrNull { it.key == m.groupValues[5] })
                }
                return parsed.ifEmpty { List(2) { Spec(Variant.PLAIN, null, null, false) } }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var listener: TapReceiverTransport.Listener? = null
    private var generation = 0
    private var touched = false
    private var sample: Future<Sample>? = null

    /** 样例（每一项在首页上的样子，握手时量形状用）与写好的记事文件（没写出来时为 null）。 */
    private class Sample(val items: List<TapThingCards.Item>, val file: File?)

    override fun start(receiverName: String, listener: TapReceiverTransport.Listener) {
        this.listener = listener
        val g = ++generation
        val specs = Spec.parse(TapTransfer.simulatedSamples(context))
        val colors = IntArray(specs.size) { ContextCompat.getColor(context, PALETTE[it % PALETTE.size]) }
        sample = io.submit<Sample> {
            sampleContent(specs, colors).let { Sample(itemsOf(it), writeSample(it)) }
        }
        main.postDelayed({
            if (g == generation && !touched) {
                touch(g, colors)
            }
        }, TOUCH_DELAY_MS)
    }

    override fun resume() {
        // 每次进入接收状态只模拟一次
    }

    override fun stop() {
        generation++
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        listener = null
    }

    private fun touch(g: Int, colors: IntArray) {
        touched = true
        // 样例早在几秒前就写好了；万一还没写完就不带形状（与旧版发送方相同）
        val items = sample?.takeIf { it.isDone }?.let { runCatching { it.get().items }.getOrNull() }.orEmpty()
        val (aspects, fullSpan) = TapThingCards.listShapes(activity, items, TapHandshake.MAX_COLORS)
        // 颜色与真实发送方一样取卡片的代表色：「大」文件夹是偏灰的底
        val offerColors = if (items.size == colors.size) {
            IntArray(items.size) { TapThingCards.representativeColor(activity, items[it]) }
        } else colors
        listener?.onTouched(
            TapIncomingOffer(context.getString(R.string.tap_simulated_peer_name), offerColors.size, offerColors,
                aspects, fullSpan)
        )
        main.postDelayed({
            if (g != generation) {
                return@postDelayed
            }
            val future = sample ?: return@postDelayed
            io.execute {
                val file = try {
                    future.get(SAMPLE_TIMEOUT_MS, TimeUnit.MILLISECONDS).file
                } catch (e: Exception) {
                    Log.w(TAG, "样例记事文件没写出来", e)
                    null
                }
                main.post {
                    if (g != generation) {
                        return@post
                    }
                    if (file == null) {
                        listener?.onFailed(TapFailure.FILE)
                        return@post
                    }
                    val total = file.length().coerceAtLeast(1L)
                    listener?.onConnected(total)
                    stepProgress(g, file, total, 1)
                }
            }
        }, JOIN_DELAY_MS)
    }

    private fun stepProgress(g: Int, file: File, total: Long, step: Int) {
        if (g != generation) {
            return
        }
        if (step > STEPS / 2 && TapTransfer.isSimulatedFailure(context)) {
            file.delete()
            listener?.onFailed(TapFailure.TRANSFER)
            return
        }
        listener?.onProgress(total * step / STEPS, total)
        if (step >= STEPS) {
            listener?.onReceived(file)
            return
        }
        main.postDelayed({ stepProgress(g, file, total, step + 1) }, TRANSFER_MS / STEPS)
    }

    /** 在后台线程调用：带图片的样例要先把图片画出来、存成文件。 */
    private fun sampleContent(specs: List<Spec>, colors: IntArray): ThingShareContent {
        val now = System.currentTimeMillis()
        val dir = TapTransfer.workDir(context, "sim")
        return ThingShareContent(specs.mapIndexed { k, spec ->
            when (spec.variant) {
                Variant.FOLDER, Variant.BIGFOLDER -> sampleFolder(k, spec, colors[k], now)
                else -> ThingShareContent.ThingNode(sampleThing(k, spec, colors[k], now, dir))
            }
        })
    }

    /**
     * 一个样例文件夹：[Spec.sidePercent] 条纯文字记事（第一条置顶），最后是一个带两条记事的子文件夹。
     * 颜色取调色板里的下几个，与文件夹自己的颜色分得开。
     */
    private fun sampleFolder(k: Int, spec: Spec, color: Int, now: Long): ThingShareContent.FolderNode {
        val count = (spec.sidePercent ?: SAMPLE_FOLDER_THINGS).coerceIn(1, 12)
        val children = ArrayList<ThingShareContent.Node>()
        for (i in 0 until count) {
            val tint = ContextCompat.getColor(context, PALETTE[(k + 1 + i) % PALETTE.size])
            children.add(ThingShareContent.ThingNode(plainThing(i + 1, tint, now), sticky = i == 0))
        }
        val subColor = ContextCompat.getColor(context, PALETTE[(k + count + 1) % PALETTE.size])
        val sub = ThingFolder(0L, null, context.getString(R.string.tap_simulated_folder_title, (k + 1) * 10 + 1),
            Thing.UNDERWAY, subColor, 0L, false, now, now)
        sub.setBackground(ThingBackground.pure(subColor))
        // 子文件夹里第二条是提醒：时刻取一天之前，导入时按已过期处理、不设闹钟
        val reminder = plainThing(count + 2, subColor, now).also { it.type = Thing.REMINDER }
        children.add(ThingShareContent.FolderNode(sub, listOf(
            ThingShareContent.ThingNode(plainThing(count + 1, subColor, now)),
            ThingShareContent.ThingNode(reminder, reminder = Reminder(0L, now - DAY_MS))
        )))
        val presentation = ThingFolderCardPresentation(
            mode = if (spec.variant == Variant.BIGFOLDER) ThingFolderCardPresentation.MODE_THUMBNAILS
            else ThingFolderCardPresentation.MODE_SUMMARY,
            spanMode = if (spec.fullSpan) ThingFolderCardPresentation.SPAN_FULL else ThingFolderCardPresentation.SPAN_NORMAL
        )
        val folder = ThingFolder(0L, null, context.getString(R.string.tap_simulated_folder_title, k + 1),
            Thing.UNDERWAY, color, 0L, false, now, now, presentation)
        folder.setBackground(ThingBackground.pure(color))
        return ThingShareContent.FolderNode(folder, children)
    }

    private fun plainThing(n: Int, color: Int, now: Long): Thing {
        val thing = Thing(0L, Thing.NOTE, Thing.UNDERWAY, color, context.getString(R.string.tap_simulated_thing_title, n),
            context.getString(R.string.tap_simulated_thing_content), "", 0L, now, now, 0L)
        thing.setBackground(ThingBackground.pure(color))
        return thing
    }

    /**
     * 每一项在首页上的样子：文件夹的计数与缩略图按样例的子树给（它还不在数据库里，首页适配器取不到）。
     */
    private fun itemsOf(content: ThingShareContent): List<TapThingCards.Item> = content.items.map { node ->
        when (node) {
            is ThingShareContent.ThingNode -> TapThingCards.Item.ThingItem(node.thing)
            is ThingShareContent.FolderNode -> TapThingCards.Item.FolderItem(folderEntry(node))
        }
    }

    private fun folderEntry(node: ThingShareContent.FolderNode): ThingListEntry.FolderEntry {
        val previews = node.children.map { child ->
            when (child) {
                is ThingShareContent.ThingNode -> ThingListEntry.ThingEntry(child.thing)
                is ThingShareContent.FolderNode -> folderEntry(child)
            }
        }
        val limit = node.folder.cardPresentation.effectiveThumbnailPreviewLimit()
        return ThingListEntry.FolderEntry(
            folder = node.folder,
            recursiveThingCount = ThingShareContent(node.children).things.size,
            directFolderCount = node.children.count { it is ThingShareContent.FolderNode },
            thumbnailEntries = if (node.folder.cardPresentation.mode == ThingFolderCardPresentation.MODE_THUMBNAILS)
                previews.take(limit) else emptyList(),
            thumbnailEntryCount = previews.size
        )
    }

    private fun sampleThing(k: Int, spec: Spec, color: Int, now: Long, dir: File): Thing {
        return run {
            val variant = spec.variant
            var sourceKey: String? = null
            val textOnly = variant == Variant.PLAIN || variant == Variant.WIDE || variant == Variant.LIST ||
                variant == Variant.TEXT
            val attachment = if (textOnly) "" else {
                val image = File(dir, "sample-image-$now-$k.jpg")
                drawSampleImage(image, variant, color)
                sourceKey = AttachmentHelper.IMAGE.toString() + image.absolutePath
                AttachmentHelper.SIGNAL + sourceKey
            }
            val thing = Thing(
                0L, Thing.NOTE, Thing.UNDERWAY, color,
                context.getString(R.string.tap_simulated_thing_title, k + 1),
                when (spec.content ?: variant) {
                    Variant.LIST -> sampleChecklist()
                    Variant.TEXT -> List(SAMPLE_TEXT_LINES) { line ->
                        context.getString(R.string.tap_simulated_thing_content) + " " + (line + 1)
                    }.joinToString("\n")
                    else -> context.getString(R.string.tap_simulated_thing_content)
                },
                attachment, 0L, now, now, 0L
            )
            thing.setBackground(ThingBackground.pure(color))
            thing.thingCardAppearance = when (variant) {
                Variant.PLAIN -> ThingCardAppearance()
                Variant.TOP -> ThingCardAppearance(imagePlacement = Thing.THING_CARD_IMAGE_PLACEMENT_TOP)
                Variant.BOTTOM -> ThingCardAppearance(imagePlacement = Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM)
                Variant.LEFT -> ThingCardAppearance(
                    spanMode = Thing.THING_CARD_SPAN_FULL, imagePlacement = Thing.THING_CARD_IMAGE_PLACEMENT_LEFT
                )
                Variant.RIGHT -> ThingCardAppearance(
                    spanMode = Thing.THING_CARD_SPAN_FULL, imagePlacement = Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
                )
                Variant.BACKGROUND -> ThingCardAppearance(mediaBackgroundEnabled = true)
                Variant.WIDE -> ThingCardAppearance(spanMode = Thing.THING_CARD_SPAN_FULL)
                Variant.LIST, Variant.TEXT, Variant.FOLDER, Variant.BIGFOLDER -> ThingCardAppearance()
            }.let { base -> withSpec(base, spec, sourceKey) }
            thing
        }
    }

    /** 清单样例：一级、二级交替，其中几项已完成。 */
    private fun sampleChecklist(): String {
        val items = List(SAMPLE_CHECKLIST_ITEMS) { k ->
            val state = if (k % 5 == 4) CheckListHelper.STATE_FINISHED else CheckListHelper.STATE_UNFINISHED
            CheckListHelper.makeItem(state, if (k % 3 == 2) 2 else 1,
                context.getString(R.string.tap_simulated_thing_title, k + 1))
        }
        return CheckListHelper.toCheckListStr(items)
    }

    /** 把 [spec] 里的通栏、侧边宽度与封面比例加到 [base] 上。 */
    private fun withSpec(base: ThingCardAppearance, spec: Spec, sourceKey: String?): ThingCardAppearance {
        var appearance = base
        if (spec.fullSpan) {
            appearance = appearance.copy(spanMode = Thing.THING_CARD_SPAN_FULL)
        }
        spec.sidePercent?.let { appearance = appearance.copy(sideMediaWidthPercent = it) }
        val ratio = spec.ratio
        if (ratio == null || sourceKey == null) {
            return appearance
        }
        val presentation = when (spec.variant) {
            Variant.TOP, Variant.BOTTOM -> ThingCardAppearance.PRESENTATION_THUMBNAIL
            Variant.LEFT, Variant.RIGHT -> ThingCardAppearance.PRESENTATION_SIDE_PANEL
            Variant.BACKGROUND -> ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND
            else -> return appearance
        }
        val source = ThingCardAppearance.SourceAppearance()
            .withPresentation(presentation, ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = ratio))
        return appearance.copy(mediaSourceKey = sourceKey, sources = mapOf(sourceKey to source))
    }

    /**
     * 一张有明暗层次的风景：渐变的天、一轮太阳、前后两层山。颜色随记事的颜色变，几张样例一眼
     * 分得开；比例随样式变（背景用竖图、上下用横图），卡片按不同比例排版的情况都能看到。
     */
    private fun drawSampleImage(dst: File, variant: Variant, tint: Int) {
        val (w, h) = when (variant) {
            Variant.BACKGROUND -> 900 to 1200
            Variant.BOTTOM -> 1000 to 1000
            else -> 1200 to 800
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), mix(tint, 0xFFFFFFFF.toInt(), .55f),
            mix(tint, 0xFFFFF3E0.toInt(), .2f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFF8E1.toInt()
        canvas.drawCircle(w * .72f, h * .30f, minOf(w, h) * .09f, paint)
        paint.color = mix(tint, 0xFF263238.toInt(), .45f)
        canvas.drawPath(ridge(w, h, .58f, .22f), paint)
        paint.color = mix(tint, 0xFF102027.toInt(), .7f)
        canvas.drawPath(ridge(w, h, .74f, .16f), paint)
        FileOutputStream(dst).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
    }

    private fun ridge(w: Int, h: Int, base: Float, amplitude: Float): Path {
        val path = Path()
        path.moveTo(0f, h.toFloat())
        val steps = 8
        for (i in 0..steps) {
            val x = w * i / steps.toFloat()
            val peak = if (i % 2 == 0) 0f else amplitude * (0.6f + 0.4f * ((i * 37) % 5) / 4f)
            path.lineTo(x, h * (base - peak))
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        return path
    }

    private fun writeSample(content: ThingShareContent): File? {
        val dst = File(TapTransfer.workDir(context, "sim"), "sample-${System.currentTimeMillis()}.edthing")
        val result = ThingFileWriter.write(context, content, dst)
        // 图片已经打进记事文件，现画的原图不再需要
        content.things.forEach { thing ->
            AttachmentHelper.getOriginalFiles(thing.attachment)?.forEach { it?.delete() }
        }
        return when (result) {
            is ThingFileWriter.Result.Ok -> result.file
            is ThingFileWriter.Result.Failed -> {
                Log.w(TAG, "写样例记事文件失败：${result.message}")
                null
            }
        }
    }

    private companion object {
        const val TAG = "TapSimReceiver"
        const val TOUCH_DELAY_MS = 3500L
        const val JOIN_DELAY_MS = 1300L
        const val TRANSFER_MS = 900L
        const val STEPS = 18
        const val SAMPLE_TIMEOUT_MS = 30_000L
        const val SAMPLE_CHECKLIST_ITEMS = 14
        const val SAMPLE_TEXT_LINES = 20
        const val SAMPLE_FOLDER_THINGS = 4
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** 样例记事的颜色，按顺序循环取。 */
        val PALETTE = intArrayOf(
            R.color.my_cyan, R.color.elegant_orange, R.color.pine_green, R.color.my_purple, R.color.blue_deep
        )

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
