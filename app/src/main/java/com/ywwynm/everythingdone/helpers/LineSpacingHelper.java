package com.ywwynm.everythingdone.helpers;

import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.support.annotation.ColorRes;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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
                et.setLineSpacing(0f, 0f);
                et.setLineSpacing(add, mul);
            }
        });
    }

    public static void setTextCursorDrawable(EditText et, int cursorColor, int cursorWidth, int cursorHeight) {
        try {
            Method method = TextView.class.getDeclaredMethod("createEditorIfNeeded");
            method.setAccessible(true);
            method.invoke(et);
            Field field1 = TextView.class.getDeclaredField("mEditor");
            Field field2 = Class.forName("android.widget.Editor").getDeclaredField("mCursorDrawable");
            field1.setAccessible(true);
            field2.setAccessible(true);
            Object arr = field2.get(field1.get(et));
            Array.set(arr, 0, new LineSpacingCursorDrawable(cursorColor, cursorWidth, cursorHeight));
            Array.set(arr, 1, new LineSpacingCursorDrawable(cursorColor, cursorWidth, cursorHeight));
        } catch (Exception ignored) {}
    }

    private static class LineSpacingCursorDrawable extends ShapeDrawable {

        private int mHeight;

        LineSpacingCursorDrawable(int color, int width, int height) {
            setDither(false);
            getPaint().setColor(color);
            setIntrinsicWidth(width);
            mHeight = height;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
            super.setBounds(left, top, right, bottom + mHeight);
        }
    }
}
