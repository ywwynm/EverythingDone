package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 「创建页预测素材」的磁盘缓存（第五批追加项）。
 *
 * 第四批的提前预算用「上一次真实创建页的首屏」当预测素材，所以**进程启动后的第一次新建
 * 必然未命中**。这里把那一屏无损存进应用私有目录，冷启动之后直接读回来，第一次新建也能命中。
 *
 * ## 存什么、不存什么
 *
 * 存的是**空白创建页**的首屏——标题与正文都是空的，没有任何记事内容；只放在
 * `filesDir/particle-prewarm/` 这个应用私有目录里，不经任何导出路径。
 * 与它一起存的是**去掉新记事背景色之后**的缓存键（[ParticleCreationKey.persistent]，
 * 15 项）与来源时间：背景色每次新建都重掷，拿它去要求相等没有意义，而背景偏差只影响途中
 * 每一片的出生、寿命与权重——**末帧照旧由当次真实快照按格子拼回，逐像素等于真实页面**。
 *
 * 键里任何一项变了（深浅色、语言、字号、密度、方向、窗口尺寸、多窗口、系统栏与刘海 inset、
 * 输入法高度、文件夹祖先链、动画档位、时长缩放、刷新率、新建按钮位置、影响轨迹的开关）
 * 就不匹配，直接忽略这张图、走现有路径。
 *
 * ## 时机
 *
 * - **写**：真实创建页首屏被捕获之后，在单独的后台线程上压缩与落盘（[remember]）。
 *   失败只记日志，不影响任何功能。
 * - **读**：首页静置、准备预算的那条后台线程上按键查一次（[load]）。索引很小，图片只在
 *   键匹配时才解码，所以不会挡住首页首帧。
 * - `onTrimMemory` / 退后台只清内存态，**磁盘缓存原样留着**。
 */
internal object ParticlePrewarmStore {

    private const val DIRECTORY = "particle-prewarm"
    private const val INDEX = "index.json"
    private const val VERSION = 1

    /** 落盘用的一份素材：像素 + 建材需要的那几个几何量。 */
    internal class Material(
        val pixels: IntArray, val width: Int, val height: Int,
        val originX: Float, val originY: Float,
        val virtualTouchX: Float, val virtualTouchY: Float,
        val touchGap: Float?, val touchStrength: Float?,
        val savedAt: Long = System.currentTimeMillis()
    )

    private val writer by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ParticlePrewarmStore").apply { isDaemon = true }
        }
    }

    /** 索引读一次就留在内存里；写盘那条线程与读取那条线程都只经这里。 */
    @Volatile private var cached: List<ParticlePrewarmRetention.Record>? = null

    private val lock = Any()

    @Volatile private var lastError: String? = null

    // ---- 写 ----

    /** 把这一屏排进后台线程落盘；调用方线程立即返回。 */
    fun remember(context: Context, key: String, material: Material) {
        val app = context.applicationContext
        runCatching { writer.execute { write(app, key, material) } }
            .onFailure { note("排队落盘失败：${it.javaClass.simpleName}") }
    }

    /**
     * 同一个键已经有一张、而且还新鲜时不重写。
     *
     * 键已经覆盖了版式的全部变量，同键那张就是有效的；每建一条记事都重压一张全屏图纯属磨损。
     * 留一个刷新周期是因为**文件夹的名字与配色不进键**（要逐级查数据库，见 fingerprint 的注释），
     * 隔一阵子换一张能让预测素材跟上这类变化。
     */
    private const val REFRESH_MS = 6L * 60 * 60 * 1000

    private fun write(context: Context, key: String, material: Material) {
        runCatching {
            val directory = directory(context)
            val name = fileName(key)
            val fresh = synchronized(lock) {
                index(context).firstOrNull { it.key == key }
                    ?.let { material.savedAt - it.savedAt in 0 until REFRESH_MS } ?: false
            }
            if (fresh) return
            val target = File(directory, name)
            val temporary = File(directory, "$name.tmp")
            val bitmap = Bitmap.createBitmap(material.pixels, material.width, material.height,
                Bitmap.Config.ARGB_8888)
            try {
                temporary.outputStream().use { out ->
                    // 无损：WEBP_LOSSLESS（API 30+）比 PNG 小一大截；更低版本退回 PNG。
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
                    check(bitmap.compress(format, 100, out)) { "压缩失败" }
                }
            } finally {
                bitmap.recycle()
            }
            // 单张就超过上限时到此为止：**绝不能先改名再淘汰**——同键的文件名是同一个，
            // 那样会把盘上原本好好的那一张覆盖掉再删掉。
            val bytes = temporary.length()
            if (bytes !in 1..ParticlePrewarmRetention.MAX_BYTES) {
                temporary.delete()
                note("单张 ${bytes / 1024} KB 超过上限，不收")
                return
            }
            check(temporary.renameTo(target)) { "改名失败" }
            val incoming = ParticlePrewarmRetention.Record(key, name, material.savedAt, bytes)
            val meta = JSONObject()
                .put("width", material.width).put("height", material.height)
                .put("originX", material.originX.toDouble()).put("originY", material.originY.toDouble())
                .put("touchX", material.virtualTouchX.toDouble())
                .put("touchY", material.virtualTouchY.toDouble())
                .put("touchGap", material.touchGap?.toDouble() ?: JSONObject.NULL)
                .put("touchStrength", material.touchStrength?.toDouble() ?: JSONObject.NULL)
            synchronized(lock) {
                val existing = index(context)
                val kept = ParticlePrewarmRetention.retain(existing, incoming)
                for (record in existing + incoming) {
                    if (kept.none { it.file == record.file }) File(directory, record.file).delete()
                }
                metadata[name] = meta
                persist(context, kept)
            }
            if (BuildConfig.DEBUG) Log.i(ParticleMicroflakeRenderer.TAG,
                "预测素材已落盘 ${target.length() / 1024} KB ${material.width}×${material.height}")
        }.onFailure { note("落盘失败：${it.javaClass.simpleName}: ${it.message}") }
    }

    // ---- 读 ----

    /** 键匹配时读回一份素材；没有、读坏了或尺寸对不上都返回 null（调用方走现有路径）。 */
    fun load(context: Context, key: String): Material? = runCatching {
        val app = context.applicationContext
        val record = synchronized(lock) { index(app).firstOrNull { it.key == key } } ?: return null
        val meta = synchronized(lock) { metadata[record.file] } ?: return null
        val file = File(directory(app), record.file)
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        try {
            val width = meta.getInt("width"); val height = meta.getInt("height")
            if (bitmap.width != width || bitmap.height != height) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            Material(pixels, width, height,
                meta.getDouble("originX").toFloat(), meta.getDouble("originY").toFloat(),
                meta.getDouble("touchX").toFloat(), meta.getDouble("touchY").toFloat(),
                if (meta.isNull("touchGap")) null else meta.getDouble("touchGap").toFloat(),
                if (meta.isNull("touchStrength")) null else meta.getDouble("touchStrength").toFloat(),
                record.savedAt)
        } finally {
            bitmap.recycle()
        }
    }.getOrElse { note("读回失败：${it.javaClass.simpleName}"); null }

    // ---- 索引 ----

    private val metadata = HashMap<String, JSONObject>()

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { if (!isDirectory) mkdirs() }

    /** 只在 [lock] 里调用。 */
    private fun index(context: Context): List<ParticlePrewarmRetention.Record> {
        cached?.let { return it }
        val loaded = runCatching {
            val file = File(directory(context), INDEX)
            if (!file.isFile) return@runCatching emptyList()
            val root = JSONObject(file.readText())
            if (root.optInt("version") != VERSION) return@runCatching emptyList()
            val entries = root.optJSONArray("entries") ?: return@runCatching emptyList()
            val records = ArrayList<ParticlePrewarmRetention.Record>(entries.length())
            for (i in 0 until entries.length()) {
                val row = entries.getJSONObject(i)
                val name = row.getString("file")
                if (!File(directory(context), name).isFile) continue
                records += ParticlePrewarmRetention.Record(row.getString("key"), name,
                    row.getLong("savedAt"), row.getLong("bytes"))
                metadata[name] = row.getJSONObject("meta")
            }
            records
        }.getOrElse { note("索引读取失败：${it.javaClass.simpleName}"); emptyList() }
        cached = loaded
        return loaded
    }

    /** 只在 [lock] 里调用。 */
    private fun persist(context: Context, records: List<ParticlePrewarmRetention.Record>) {
        val entries = JSONArray()
        for (record in records) {
            entries.put(JSONObject().put("key", record.key).put("file", record.file)
                .put("savedAt", record.savedAt).put("bytes", record.bytes)
                .put("meta", metadata[record.file] ?: JSONObject()))
        }
        val directory = directory(context)
        val temporary = File(directory, "$INDEX.tmp")
        temporary.writeText(JSONObject().put("version", VERSION).put("entries", entries).toString())
        check(temporary.renameTo(File(directory, INDEX))) { "索引改名失败" }
        metadata.keys.retainAll(records.map { it.file }.toSet())
        cached = records
    }

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "png"
        return "$hex.$extension"
    }

    private fun note(message: String) {
        lastError = message
        Log.w(ParticleMicroflakeRenderer.TAG, "预测素材磁盘缓存：$message")
    }

    /**
     * 观察器读的诊断：盘上几份、占多少字节、上一次的失败原因。
     *
     * **只看已经读进内存的那一份索引**（`entries` 为 −1 表示还没读过）——这个方法会被 debug
     * 观察器在主线程上调用，不能在这里碰磁盘。
     */
    fun diagnostics(): JSONObject {
        val records = cached
        return JSONObject()
            .put("entries", records?.size ?: -1)
            .put("bytes", records?.sumOf { it.bytes } ?: -1L)
            .put("error", lastError ?: JSONObject.NULL)
    }
}
