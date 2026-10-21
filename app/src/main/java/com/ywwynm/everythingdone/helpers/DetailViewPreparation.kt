package com.ywwynm.everythingdone.helpers

import android.app.Activity
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.asynclayoutinflater.appcompat.AsyncAppCompatFactory
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.views.ThingDetailTransition

/**
 * 首页显示后异步创建一份空详情布局。只持有应用 Context，未绑定任何记事。
 * 取用时换成真实详情 Activity；配置不符或尚未完成就直接同步创建，不等待预创建任务。
 * 控件从未附着过窗口，尺寸、inset、焦点和保存状态都由真实详情正常初始化。
 */
internal object DetailViewPreparation {
    internal class Prepared(
        val root: View,
        val sections: LinearLayout,
        val quickPicker: View,
        val context: MutableContextWrapper,
        val configuration: Configuration
    )

    private var generation = 0
    private var preparing = false
    private var prepared: Prepared? = null
    internal var lastPreparationMs = 0L
        private set

    fun prepare(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val configuration = Configuration(activity.resources.configuration)
        if (prepared?.configuration == configuration || preparing) return
        prepared = null
        preparing = true
        val request = ++generation
        val started = SystemClock.uptimeMillis()
        val appContext = activity.applicationContext.createConfigurationContext(configuration)
        val theme = ContextThemeWrapper(appContext, R.style.EverythingDoneTheme_Detail)
        theme.theme.applyStyle(R.style.UiInteractionPolicy, true)
        val context = MutableContextWrapper(theme)
        AsyncLayoutInflater(context, AsyncAppCompatFactory()).inflate(R.layout.detail_prepared_views, null) { view, _, _ ->
            preparing = false
            if (request != generation) return@inflate
            lastPreparationMs = SystemClock.uptimeMillis() - started
            val container = view as ViewGroup
            val root = container.getChildAt(0)
            val sections = container.getChildAt(1) as LinearLayout
            val quickPicker = container.getChildAt(2)
            container.removeAllViews()
            prepared = Prepared(root, sections, quickPicker, context, configuration)
        }
    }

    fun take(activity: Activity): Prepared? {
        val result = prepared
        prepared = null
        if (result == null || result.configuration != activity.resources.configuration) {
            ThingDetailTransition.mark("prepared-layout-miss")
            return null
        }
        result.context.baseContext = activity
        ThingDetailTransition.mark("prepared-layout-hit")
        return result
    }

    fun clear() {
        generation++
        // 已提交的任务不可取消；等其回调释放，避免快速进出首页时累积多个后台任务。
        prepared = null
    }
}
