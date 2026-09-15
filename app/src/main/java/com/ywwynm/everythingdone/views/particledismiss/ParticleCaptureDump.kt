package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.ywwynm.everythingdone.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仅 debug：把「离屏重录 + 逐个 Surface 合成」这条**回退**路径合成出来的快照落盘，
 * 用来核对它与窗口 `PixelCopy` 拿到的是不是同一张画面。
 *
 * 只在回退真的触发时写（正常路径一次也不写），因此不设开关文件也不构成常态开销；
 * release 包里 [save] 的调用点本身就在 `BuildConfig.DEBUG` 之内，整段不会执行。
 *
 * 写到应用外部私有目录（`Android/data/<包名>/files/particle-capture/`），
 * 这样 `adb pull` 不必再走 `run-as` + `chmod`。
 */
internal object ParticleCaptureDump {

    private const val KEEP = 8
    private val worker by lazy {
        Handler(HandlerThread("ParticleCaptureDump", Process.THREAD_PRIORITY_BACKGROUND)
            .apply { start() }.looper)
    }

    fun save(context: Context, snapshot: Bitmap?) {
        if (!BuildConfig.DEBUG || snapshot == null || snapshot.isRecycled) return
        // 落盘在后台线程，但位图这一刻就要复制出来：消散会立刻把原图交给 GL 侧建材，
        // 等后台线程排上队时它可能已经被回收。
        val copy = runCatching { snapshot.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val dir = File(context.getExternalFilesDir(null), "particle-capture")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        worker.post {
            runCatching {
                dir.mkdirs()
                val file = File(dir, "fallback-$stamp.png")
                file.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(ParticleMicroflakeRenderer.TAG,
                    "消散抓图回退：合成图已存 ${file.absolutePath} " +
                        "尺寸=${copy.width}x${copy.height} 字节=${file.length()}")
                dir.listFiles()?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP)?.forEach { it.delete() }
            }.onFailure {
                Log.w(ParticleMicroflakeRenderer.TAG, "消散抓图回退：合成图落盘失败", it)
            }
            copy.recycle()
        }
    }
}
