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
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.protocol.TapHandshake
import com.ywwynm.everythingdone.taptransfer.transport.TapFailure
import com.ywwynm.everythingdone.taptransfer.transport.TapIncomingOffer
import com.ywwynm.everythingdone.taptransfer.transport.TapReceiverTransport
import com.ywwynm.everythingdone.taptransfer.ui.card.TapThingCards
import com.ywwynm.everythingdone.thingfile.ThingFileWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 调试包里的模拟接收方：进入接收状态几秒后，模拟一台手机碰上来、发来几条样例记事
 * （标题写明是模拟），走完整的导入与拼出（决策 2026-09-24）。
 *
 * 发来哪几条由调试配置 `simulate_samples` 决定（[TapTransfer.simulatedSamples]），每一项是一种
 * 卡片样式（[Variant]）：纯文字（普通或通栏），或者带一张图片、封面在上 / 下 / 左 / 右 / 作为卡片背景。
 * 图片在本机现画，不依赖设备上已有的文件。碰上时与真实发送方一样，把样例卡片按本机列表的列宽量出的
 * 形状放进来件信息（接收方据此预计落点）。
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
        WIDE("wide");

        companion object {
            fun parse(spec: String?): List<Variant> {
                val parsed = spec.orEmpty().split(',').mapNotNull { key ->
                    values().firstOrNull { it.key == key.trim() }
                }
                return parsed.ifEmpty { listOf(PLAIN, PLAIN) }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var listener: TapReceiverTransport.Listener? = null
    private var generation = 0
    private var touched = false
    private var sample: Future<Sample>? = null

    /** 样例记事与写好的记事文件（没写出来时为 null）。 */
    private class Sample(val things: List<Thing>, val file: File?)

    override fun start(receiverName: String, listener: TapReceiverTransport.Listener) {
        this.listener = listener
        val g = ++generation
        val variants = Variant.parse(TapTransfer.simulatedSamples(context))
        val colors = IntArray(variants.size) { ContextCompat.getColor(context, PALETTE[it % PALETTE.size]) }
        sample = io.submit<Sample> { sampleThings(variants, colors).let { Sample(it, writeSample(it)) } }
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
        val things = sample?.takeIf { it.isDone }?.let { runCatching { it.get().things }.getOrNull() }.orEmpty()
        val (aspects, fullSpan) = TapThingCards.listShapes(activity, things, TapHandshake.MAX_COLORS)
        listener?.onTouched(
            TapIncomingOffer(context.getString(R.string.tap_simulated_peer_name), colors.size, colors, aspects,
                fullSpan)
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
    private fun sampleThings(variants: List<Variant>, colors: IntArray): List<Thing> {
        val now = System.currentTimeMillis()
        val dir = TapTransfer.workDir(context, "sim")
        return variants.mapIndexed { k, variant ->
            val attachment = if (variant == Variant.PLAIN || variant == Variant.WIDE) "" else {
                val image = File(dir, "sample-image-$now-$k.jpg")
                drawSampleImage(image, variant, colors[k])
                AttachmentHelper.SIGNAL + AttachmentHelper.IMAGE + image.absolutePath
            }
            val thing = Thing(
                0L, Thing.NOTE, Thing.UNDERWAY, colors[k],
                context.getString(R.string.tap_simulated_thing_title, k + 1),
                context.getString(R.string.tap_simulated_thing_content),
                attachment, 0L, now, now, 0L
            )
            thing.setBackground(ThingBackground.pure(colors[k]))
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
            }
            thing
        }
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

    private fun writeSample(things: List<Thing>): File? {
        val dst = File(TapTransfer.workDir(context, "sim"), "sample-${System.currentTimeMillis()}.edthing")
        val result = ThingFileWriter.write(context, things, dst)
        // 图片已经打进记事文件，现画的原图不再需要
        things.forEach { thing -> AttachmentHelper.getOriginalFiles(thing.attachment)?.forEach { it?.delete() } }
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
