package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.graphics.Typeface;
import androidx.core.content.ContextCompat;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.ThingBackground;
import com.ywwynm.everythingdone.utils.BackgroundUtil;
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
    /** Phase 8: full accent so the floating label and focused EditText text
     *  can render gradient. When null, focused colours fall back to the plain
     *  int {@link #mAccentColor}. Highlight tint, cursor / selection-handle
     *  tint and the EditText underline tint always stay int (PorterDuff API
     *  limit) — they consume {@link #mAccentColor} via representative. */
    private ThingBackground mAccentBackground;
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

    /** Phase 8: upgrade the accent signal to a full {@link ThingBackground}.
     *  Refreshes selection handler tint to the new representative colour and,
     *  if the EditText is already focused, repaints the label/text right away
     *  so the gradient shader picks up the new stops. */
    public void setAccentBackground(ThingBackground bg) {
        mAccentBackground = bg;
        if (bg != null) {
            mAccentColor = bg.representativeColor();
            DisplayUtil.setSelectionHandlersColor(mEditText, mAccentColor);
            if (mEditText.hasFocus()) {
                setColors(mAccentColor);
            }
        }
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
        int black_54p = ContextCompat.getColor(mContext, R.color.black_54p);
        boolean useGradientLine = colorTo != black_26p
                && mAccentBackground != null
                && mAccentBackground.mode == ThingBackground.Mode.GRADIENT;
        if (colorTo == black_26p) {
            // Unfocused: plain int colours. Clear any leftover gradient shader
            // from a previous focused bind so the label/text revert to grey.
            clearShader(mTextView);
            clearShader(mEditText);
            mTextView.setTextColor(colorTo);
            mEditText.setTextColor(black_54p);
            mEditText.setHighlightColor(black_26p);
            BackgroundUtil.clearEditTextUnderline(mEditText);
        } else {
            // Focused: label + EditText text adopt the accent. Use the gradient
            // shader when we have a ThingBackground; otherwise plain int.
            if (useGradientLine) {
                BackgroundUtil.applyTextBackground(mTextView, mAccentBackground);
                BackgroundUtil.applyTextBackground(mEditText, mAccentBackground);
                BackgroundUtil.applyEditTextUnderline(mEditText, mAccentBackground);
            } else {
                clearShader(mTextView);
                clearShader(mEditText);
                mTextView.setTextColor(colorTo);
                mEditText.setTextColor(colorTo);
                BackgroundUtil.clearEditTextUnderline(mEditText);
            }
            mEditText.setHighlightColor(DisplayUtil.getLightColor(colorTo, mContext));
        }
        // Native underline tint: transparent when the foreground gradient strip
        // is taking over, otherwise show the native single-int underline (grey
        // when blurred, accent when focused + PURE).
        DisplayUtil.tintView(mEditText, useGradientLine ? android.graphics.Color.TRANSPARENT : colorTo);
    }

    private static void clearShader(TextView tv) {
        if (tv.getPaint().getShader() != null) {
            tv.getPaint().setShader(null);
            tv.invalidate();
        }
    }
}
