package com.ywwynm.everythingdone.adapters

import androidx.recyclerview.widget.RecyclerView
import android.view.View

/**
 * Created by ywwynm on 2016/6/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * basic ViewHolder which provides f() for a simpler way to findViewById
 */
open class BaseViewHolder(itemView: View?) : RecyclerView.ViewHolder(itemView!!) {

    protected var mContentView: View? = itemView

    @Suppress("UNCHECKED_CAST")
    protected fun <T : View?> f(id: Int): T {
        return mContentView!!.findViewById<View>(id) as T
    }
}
