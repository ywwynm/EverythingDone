package com.ywwynm.everythingdone.helpers;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Created by 张启 on 2017/4/7.
 * Helper class to set line spacing and solve cursor issues.
 */
public class LineSpacingHelper {

    /**
     * Even if we changed line spacing of an EditText, it won't work when we press enter and
     * start a new line. At this situation, the line above last(newly added) will keep original
     * line height, which is a bug above Lollipop.
     * {@see http://stackoverflow.com/questions/36075205/android-textview-edittext-new-line-spacing}
     *
     * This workaround is copied from https://code.google.com/p/android/issues/detail?id=78706#c17
     *
     * added on 2017/3/30
     */
    public static void helpCorrectSpacingForNewLine(final EditText et) {
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                float add = et.getLineSpacingExtra();
                float mul = et.getLineSpacingMultiplier();
                et.setLineSpacing(0f, 1f);
                et.setLineSpacing(add, mul);
            }
        });
    }

    public static void setTextCursorDrawable(
            EditText et, int cursorColor, int cursorWidth,
            int normalLineCursorHeightVary, int lastLineCursorHeightVary) {
        try {
            Method method = TextView.class.getDeclaredMethod("createEditorIfNeeded");
            method.setAccessible(true);
            method.invoke(et);
            Field field1 = TextView.class.getDeclaredField("mEditor");
            Field field2 = Class.forName("android.widget.Editor").getDeclaredField("mCursorDrawable");
            field1.setAccessible(true);
            field2.setAccessible(true);
            Object arr = field2.get(field1.get(et));
            Drawable d = new LineSpacingCursorDrawable(
                    et, cursorColor, cursorWidth,
                    normalLineCursorHeightVary, lastLineCursorHeightVary);
            Array.set(arr, 0, d);
            Array.set(arr, 1, d);
        } catch (Exception ignored) {}
    }

    private static class LineSpacingCursorDrawable extends ShapeDrawable {

        private EditText mEditText;

        private int mNormalLineHeightVary;
        private int mLastLineHeightVary;

        LineSpacingCursorDrawable(
                EditText editText, int color, int width,
                int normalLineHeightVary, int lastLineHeightVary) {
            mEditText = editText;
            setDither(false);
            getPaint().setColor(color);
            setIntrinsicWidth(width);
            mNormalLineHeightVary = normalLineHeightVary;
            mLastLineHeightVary = lastLineHeightVary;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
            int pos = mEditText.getSelectionStart();
            Layout layout = mEditText.getLayout();
            int cursorLine = layout.getLineForOffset(pos);
            int lineCount = mEditText.getLineCount();
            int heightVary = cursorLine != lineCount - 1 ?
                    mNormalLineHeightVary : mLastLineHeightVary;
            super.setBounds(left, top, right, bottom + heightVary);
        }
    }
}
