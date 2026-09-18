package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import com.ywwynm.everythingdone.R

/** 使用真实 contextual toolbar 布局及渐变阴影，不读取或修改业务数据。 */
internal object ParticleChromeProbe {
    fun addNormal(root: FrameLayout, title: String, bodyHeightDp: Int): List<View> {
        val context = root.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val status = View(context).apply {
            id = R.id.view_status_bar
            setBackgroundColor(0xffefcf62.toInt())
        }
        val bar = Toolbar(context).apply {
            id = R.id.actionbar
            this.title = title
            setTitleTextColor(Color.BLACK)
            setBackgroundColor(0xffefcf62.toInt())
        }
        val shadow = View(context).apply {
            id = R.id.actionbar_shadow
            setBackgroundResource(R.drawable.actionbar_shadow)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, dp(bodyHeightDp - 56)))
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(bodyHeightDp - 56) })
        root.addView(shadow, FrameLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(bodyHeightDp) })
        return listOf(status, bar, shadow)
    }

    fun add(root: FrameLayout, title: String, bodyHeightDp: Int): View {
        val context = root.context
        val density = context.resources.displayMetrics.density
        val chrome = View.inflate(context, R.layout.include_contextual_toolbar_things, null)
        chrome.visibility = View.VISIBLE
        chrome.findViewById<View>(R.id.view_contextual_status_bar).apply {
            layoutParams.height = ((bodyHeightDp - 56) * density).toInt()
            setBackgroundColor(0xffefcf62.toInt())
        }
        chrome.findViewById<Toolbar>(R.id.contextual_toolbar).apply {
            this.title = title
            setTitleTextColor(Color.BLACK)
            setBackgroundColor(0xffefcf62.toInt())
        }
        root.addView(chrome, FrameLayout.LayoutParams(-1, -2))
        return chrome
    }
}
