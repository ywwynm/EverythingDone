package com.ywwynm.everythingdone.helpers

import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView

import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Created by 张启 on 2017/4/7.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Helper class to set line spacing and solve cursor issues.
 */
object LineSpacingHelper {

    /**
     * Even if we changed line spacing of an EditText, it won't work when we press enter and
     * start a new line. At this situation, the line above last(newly added) will keep original
     * line height, which is a bug above Lollipop.
     * see http://stackoverflow.com/questions/36075205/android-textview-edittext-new-line-spacing
     *
     * This workaround is copied from https://code.google.com/p/android/issues/detail?id=78706#c17
     *
     * added on 2017/3/30
     */
    @JvmStatic
    fun helpCorrectSpacingForNewLine(et: EditText?) {
        et!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val add: Float = et.lineSpacingExtra
                val mul: Float = et.lineSpacingMultiplier
                et.setLineSpacing(0f, 1f)
                et.setLineSpacing(add, mul)
            }
        })
    }

    @JvmStatic
    fun setTextCursorDrawable(
            et: EditText?, cursorColor: Int, cursorWidth: Int,
            normalLineCursorHeightVary: Int, lastLineCursorHeightVary: Int) {
        try {
            val method: Method = TextView::class.java.getDeclaredMethod("createEditorIfNeeded")
            method.isAccessible = true
            method.invoke(et)
            val field1: Field = TextView::class.java.getDeclaredField("mEditor")
            val field2: Field = Class.forName("android.widget.Editor").getDeclaredField("mCursorDrawable")
            field1.isAccessible = true
            field2.isAccessible = true
            val arr: Any = field2.get(field1.get(et))!!
            val d: Drawable = LineSpacingCursorDrawable(
                    et, cursorColor, cursorWidth,
                    normalLineCursorHeightVary, lastLineCursorHeightVary)
            Array.set(arr, 0, d)
            Array.set(arr, 1, d)
        } catch (ignored: Exception) {}
    }

    private class LineSpacingCursorDrawable(
            editText: EditText?, color: Int, width: Int,
            normalLineHeightVary: Int, lastLineHeightVary: Int) : ShapeDrawable() {

        private var mEditText: EditText? = editText

        private var mNormalLineHeightVary: Int = normalLineHeightVary
        private var mLastLineHeightVary: Int = lastLineHeightVary

        init {
            setDither(false)
            paint.setColor(color)
            setIntrinsicWidth(width)
        }

        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            val pos: Int = mEditText!!.selectionStart
            val layout: Layout = mEditText!!.layout
            val cursorLine: Int = layout.getLineForOffset(pos)
            val lineCount: Int = mEditText!!.lineCount
            val heightVary: Int = if (cursorLine != lineCount - 1)
                    mNormalLineHeightVary else mLastLineHeightVary
            super.setBounds(left, top, right, bottom + heightVary)
        }
    }
}
