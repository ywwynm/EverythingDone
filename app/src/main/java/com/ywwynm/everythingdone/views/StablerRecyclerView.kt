package com.ywwynm.everythingdone.views

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import android.util.AttributeSet

import com.ywwynm.everythingdone.helpers.PossibleMistakeHelper

/**
 * Created by ywwynm on 2017/4/16.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [RecyclerView] that ignores possible NPEs when detached from window, which
 * seems a bug of original version.
 */
open class StablerRecyclerView : RecyclerView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    override fun onDetachedFromWindow() {
        try {
            // sometimes this will cause an NPE
            super.onDetachedFromWindow()
        } catch (e: Exception) {
            e.printStackTrace()
            PossibleMistakeHelper.outputNewMistakeInBackground(e)
        }
    }
}
