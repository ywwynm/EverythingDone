package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.graphics.Typeface;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2015/8/22.
 * Contains a TextView as Floating Label and an EditText
 */
public class InputLayout {

    public static final String TAG = "InputLayout";

    private final int black_26p;

    private final Context mContext;
    private final float mScreenDensity;

    private TextView mTextView;
    private EditText mEditText;

    private int mAccentColor;
    private View.OnFocusChangeListener mOnFocusChangeListener;

    private boolean raised;

    public InputLayout(Context context, TextView textView, EditText editText, int accentColor) {
        mContext = context;
        mScreenDensity = DisplayUtil.getScreenDensity(context);

        mTextView = textView;
        mEditText = editText;
        mAccentColor = accentColor;
        black_26p = ContextCompat.getColor(mContext, R.color.black_26p);

        setColors(black_26p);

        mEditText.setSelectAllOnFocus(true);
        mEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    raiseLabel(true);
                    setColors(mAccentColor);
                    mTextView.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    if (mEditText.getText().toString().isEmpty()) {
                        InputLayout.this.fallLabel();
                    }
                    setColors(black_26p);
                    mTextView.setTypeface(Typeface.DEFAULT);
                }
                if (mOnFocusChangeListener != null) {
                    mOnFocusChangeListener.onFocusChange(v, hasFocus);
                }
            }
        });
        DisplayUtil.setSelectionHandlersColor(mEditText, accentColor);
    }

    public void setOnFocusChangeListenerForEditText(View.OnFocusChangeListener listener) {
        mOnFocusChangeListener = listener;
    }

    public String getTextFromEditText() {
        return mEditText.getText().toString();
    }

    public void setTextForEditText(String text) {
        mEditText.setText(text);
        raiseLabel(false);
    }

    public EditText getEditText() {
        return mEditText;
    }

    public void raiseLabel(boolean anim) {
        if (raised) {
            return;
        }
        mTextView.setPivotX(1);
        mTextView.setPivotY(1);

        if (anim) {
            mTextView.animate().scaleX(0.75f).setDuration(96);
            mTextView.animate().scaleY(0.75f).setDuration(96);
            mTextView.animate().translationY(-mScreenDensity * 24).setDuration(96);
        } else {
            mTextView.setScaleX(0.75f);
            mTextView.setScaleY(0.75f);
            mTextView.setTranslationY(-mScreenDensity * 24);
        }
        raised = true;
    }

    public void fallLabel() {
        if (!raised) {
            return;
        }
        mTextView.setPivotX(1);
        mTextView.setPivotY(1);
        mTextView.animate().scaleX(1.0f).setDuration(96);
        mTextView.animate().scaleY(1.0f).setDuration(96);
        mTextView.animate().translationY(0).setDuration(96);
        raised = false;
    }

    public void setColors(int colorTo) {
        mTextView.setTextColor(colorTo);
        int black_54p = ContextCompat.getColor(mContext, R.color.black_54p);
        if (colorTo == black_26p) {
            mEditText.setTextColor(black_54p);
            mEditText.setHighlightColor(black_26p);
        } else {
            mEditText.setTextColor(colorTo);
            mEditText.setHighlightColor(DisplayUtil.getLightColor(colorTo, mContext));
        }
        DisplayUtil.tintView(mEditText, colorTo);
    }
}
