package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import com.ywwynm.everythingdone.BuildConfig
import java.util.WeakHashMap

/** debug 页面或用户主动开启的实际页面诊断；不读取设备品牌、不持久化开关。 */
internal object ParticlePresentationDiagnostics {
    enum class DialogMode { ORIGINAL, EARLY_WINDOW, CONTENT }
    data class Options(val rootForeground: Boolean = false,
                       val dialogMode: DialogMode = DialogMode.ORIGINAL,
                       val images: Boolean = true,
                       val glForeground: Boolean = false,
                       val synchronizedGlHandoff: Boolean = false,
                       val surfaceRecovery: Boolean = false,
                       val opaqueGlHandoff: Boolean = false,
                       val fastOpaqueHandoff: Boolean = false,
                       val unified: Boolean = false,
                       val fixture: Boolean = true)

    interface Run {
        val options: Options
        fun event(name: String, values: Map<String, Any?> = emptyMap())
        /** 返回 false 表示本样本已取得或已达到上限。 */
        fun claimImage(name: String): Boolean
        /** producer 在诊断工作线程执行，返回值由诊断端负责回收。 */
        fun image(name: String, producer: () -> Bitmap?)
    }

    private val runs = java.util.Collections.synchronizedMap(WeakHashMap<Activity, Run>())
    private val roots = java.util.Collections.synchronizedMap(WeakHashMap<View, Run>())
    fun bind(activity: Activity, run: Run) {
        if (BuildConfig.DEBUG) { runs[activity] = run; roots[activity.window.decorView] = run }
    }
    fun unbind(activity: Activity) { runs.remove(activity); roots.remove(activity.window.decorView) }
    fun of(context: Context?): Run? {
        if (!BuildConfig.DEBUG) return null
        var next = context
        while (next is ContextWrapper) {
            if (next is Activity) return runs[next]
            val base = next.baseContext
            if (base === next) break
            next = base
        }
        return null
    }
    // 记事卡片使用独立的主题 Context；宿主 DecorView 提供实际页面的诊断归属。
    fun of(view: View): Run? = of(view.context) ?: if (BuildConfig.DEBUG) roots[view.rootView] else null

    fun sample(run: Run?, name: String, bitmap: Bitmap) {
        if (run == null || !run.options.images || !run.claimImage(name)) return
        // 内容位图下一帧会复用，必须在调用栈内保留自己的副本。
        try {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            run.image(name) { copy }
        } catch (failure: Exception) {
            run.event("image_failed", mapOf("image" to name, "error" to failure.toString()))
        }
    }

    // 卡片交接快照与各前景直接成为窗口根节点的子层时，预留不重叠的层级。
    const val ROOT_CARD_LAYER = Int.MAX_VALUE - 128
    fun rootForegroundLayer(order: Int): Int {
        require(order in 1..64)
        return ROOT_CARD_LAYER + order
    }
}
