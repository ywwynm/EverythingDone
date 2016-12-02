package com.ywwynm.everythingdone.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.views.PatternLockView;

import java.util.List;

/**
 * Created by ywwynm on 2016/5/23.
 * A dialog fragment provides pattern lock/unlock user interface including set and validate
 * pattern.
 */
public class PatternLockDialogFragment extends BaseDialogFragment {

    public static final String TAG = "PatternLockDialogFragment";

    public static final int TYPE_SET      = 0;
    public static final int TYPE_VALIDATE = 1;
    private int mType;

    private String mCorrectPassword;
    private String mPassword;

    private int mAccentColor;
    private String mValidateTitle;

    private TextView mTvTitle;
    private TextView mTvContent;
    private PatternLockView mLockView;
    private TextView mTvLeftAsBt;
    private TextView mTvRightAsBt;

    private View.OnClickListener mPasswordSetDoneListener;

    private AuthenticationHelper.AuthenticationCallback mAuthenticationCallback;

    private boolean mValidated = false;

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_pattern_lock;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        findViews();
        initUI();
        setEvents();

        return mContentView;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (mType == TYPE_VALIDATE && !mValidated && mAuthenticationCallback != null) {
            mAuthenticationCallback.onCancel();
        }
        mPasswordSetDoneListener = null;
        mAuthenticationCallback  = null;
    }

    private void findViews() {
        mTvTitle     = f(R.id.tv_title_pattern_lock);
        mTvContent   = f(R.id.tv_content_pattern_lock);
        mLockView    = f(R.id.pattern_lock_view);
        mTvLeftAsBt  = f(R.id.tv_left_as_bt_pattern);
        mTvRightAsBt = f(R.id.tv_right_as_bt_pattern);
    }

    private void initUI() {
        mTvTitle.setTextColor(mAccentColor);
        mTvRightAsBt.setTextColor(mAccentColor);
        mLockView.setPathColor(ContextCompat.getColor(getActivity(), R.color.black_54));
        mLockView.setCorrectColor(mAccentColor);

        if (mType == TYPE_SET) {
            initUiSet();
        } else if (mType == TYPE_VALIDATE) {
            initUiValidate();
        }
    }

    private void initUiSet() {
        mTvTitle.setText(R.string.set_app_password);
        updateUiSetStep1();
    }

    private void updateUiSetStep1() {
        mTvContent.setText(R.string.draw_pattern);
        mTvLeftAsBt.setText(R.string.cancel);
        mTvRightAsBt.setText(R.string.continue_for_alert);
    }

    private void updateUiSetStep2() {
        mTvContent.setText(R.string.draw_pattern_again);
        mTvLeftAsBt.setText(R.string.last);
        mTvRightAsBt.setText(R.string.done);
    }

    private void initUiValidate() {
        mTvTitle.setText(mValidateTitle);
        mTvContent.setText(R.string.confirm_pattern);
        f(R.id.rl_pattern_lock_control).setVisibility(View.GONE);
    }

    private void setEvents() {
        if (mType == TYPE_SET) {
            setEventsSetStep1();
        } else if (mType == TYPE_VALIDATE) {
            setEventsValidate();
        }
    }

    private void setEventsSetStep1() {
        mLockView.setOnPatternListener(new PatternLockView.OnPatternListener() {

            @Override
            public void onPatternCellAdded(List<PatternLockView.Cell> pattern, String simplePattern) {
                mTvContent.setText(R.string.relax_finger_when_finish);
            }

            @Override
            public void onPatternDetected(List<PatternLockView.Cell> pattern, String simplePattern) {
                mLockView.setDisplayMode(PatternLockView.DisplayMode.Correct);
                mPassword = simplePattern;
                mTvContent.setText(R.string.draw_pattern_finished);
            }

        });

        mTvLeftAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        mTvRightAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mLockView.getSimplePattern().isEmpty()) {
                    return;
                }
                mLockView.clearPattern();
                updateUiSetStep2();
                setEventsSetStep2();
            }
        });
    }

    private void setEventsSetStep2() {
        mLockView.setOnPatternListener(new PatternLockView.OnPatternListener() {
            @Override
            public void onPatternCellAdded(List<PatternLockView.Cell> pattern, String simplePattern) {
                mTvContent.setText(R.string.relax_finger_when_finish);
            }

            @Override
            public void onPatternDetected(List<PatternLockView.Cell> pattern, String simplePattern) {
                if (!mPassword.equals(simplePattern)) {
                    mTvContent.setText(R.string.pattern_not_same);
                    mLockView.setDisplayMode(PatternLockView.DisplayMode.Wrong);
                } else {
                    mTvContent.setText(R.string.draw_pattern_finished);
                    mLockView.setDisplayMode(PatternLockView.DisplayMode.Correct);
                }
            }
        });

        mTvLeftAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLockView.clearPattern();
                updateUiSetStep1();
                setEventsSetStep1();
            }
        });
        mTvRightAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String password = mLockView.getSimplePattern();
                if (!mPassword.equals(password)) {
                    return;
                }

                dismiss();
                if (mPasswordSetDoneListener != null) {
                    mPasswordSetDoneListener.onClick(v);
                }
            }
        });
    }

    private void setEventsValidate() {
        mLockView.setOnPatternListener(new PatternLockView.OnPatternListener() {
            @Override
            public void onPatternCellAdded(List<PatternLockView.Cell> pattern, String simplePattern) {
                mTvContent.setText(R.string.confirm_pattern);
            }

            @Override
            public void onPatternDetected(List<PatternLockView.Cell> pattern, String simplePattern) {
                if (!mCorrectPassword.equals(simplePattern)) {
                    mTvContent.setText(R.string.wrong_pattern);
                    mLockView.setDisplayMode(PatternLockView.DisplayMode.Wrong);
                } else {
                    mValidated = true;
                    dismiss();
                    if (mAuthenticationCallback != null) {
                        mAuthenticationCallback.onAuthenticated();
                    }
                }
            }
        });
    }

    public void setType(int type) {
        mType = type;
    }

    public void setAccentColor(int accentColor) {
        mAccentColor = accentColor;
    }

    public void setCorrectPassword(String correctPassword) {
        mCorrectPassword = correctPassword;
    }

    public void setValidateTitle(String validateTitle) {
        mValidateTitle = validateTitle;
    }

    public void setPasswordSetDoneListener(View.OnClickListener passwordSetDoneListener) {
        mPasswordSetDoneListener = passwordSetDoneListener;
    }

    public void setAuthenticationCallback(
            AuthenticationHelper.AuthenticationCallback authenticationCallback) {
        mAuthenticationCallback = authenticationCallback;
    }

    public String getPassword() {
        return mPassword;
    }
}
