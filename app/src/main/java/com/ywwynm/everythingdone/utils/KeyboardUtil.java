package com.ywwynm.everythingdone.utils;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

/**
 * Created by ywwynm on 2015/7/27.
 * show/hide keyboard and etc
 */
public class KeyboardUtil {

    public static final String TAG = "EverythingDone$KeyboardUtil";

    public static final int HIDE_DELAY = 120;

    public static void showKeyboard(View view) {
        if (view == null) {
            return;
        }

        view.requestFocus();
        InputMethodManager imm = (InputMethodManager) view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(view, InputMethodManager.SHOW_FORCED);
    }

    public static void hideKeyboard(View view) {
        if (view == null) {
            return;
        }
        view.clearFocus();

        InputMethodManager imm = (InputMethodManager) view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (!imm.isActive()) {
            return;
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public static void hideKeyboard(Window window) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    public interface KeyboardCallback {
        void onKeyboardShow(float keyboardHeight);
        void onKeyboardHide();
    }

    public static void addKeyboardCallback(Window window, final KeyboardCallback callback) {
        final View decorView = window.getDecorView();
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {

                    private Rect r = new Rect();
                    private float initialDiff = -1;
                    private float possibleKeyboardHeight =
                            96 * DisplayUtil.getScreenDensity(decorView.getContext());

                    @Override
                    public void onGlobalLayout() {
                        //r will be populated with the coordinates of your view that area still visible.
                        decorView.getWindowVisibleDisplayFrame(r);

                        //get the height diff as dp
                        float heightDiff = decorView.getRootView().getHeight() - (r.bottom - r.top);

                        //set the initialDiff at the beginning.
                        if (initialDiff == -1) {
                            initialDiff = heightDiff;
                        }

                        //if it could be a keyboard add the padding to the view
                        if (heightDiff - initialDiff > possibleKeyboardHeight) {
                            if (callback != null) {
                                callback.onKeyboardShow(heightDiff - initialDiff);
                            }
                        } else {
                            if (callback != null) {
                                callback.onKeyboardHide();
                            }
                        }
                    }
                });
    }
}
