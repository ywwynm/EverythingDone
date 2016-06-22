package com.ywwynm.everythingdone.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.mattprecious.swirl.SwirlView;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.helpers.FingerprintHelper;
import com.ywwynm.everythingdone.utils.LocaleUtil;

/**
 * Created by ywwynm on 2016/6/20.
 * A DialogFragment used to authenticate user's id by pattern or fingerprint.
 */
public class AuthenticationDialogFragment extends BaseDialogFragment {

    public static final String TAG = "AuthenticationDialogFragment";

    private Activity mActivity;

    private int mAccentColor;

    private String mTitle;

    private TextView  mTvTitle;
    private TextView  mTvContent;
    private SwirlView mSwirlView;
    private TextView  mTvState;
    private TextView  mTvSecondAsBt;

    private FingerprintManager.CryptoObject mCryptoObject;
    private FingerprintHelper mFingerprintHelper;

    private AuthenticationHelper.AuthenticationCallback mAuthenticationCallback;

    private boolean mAuthenticated = false;

    @Override
    protected int getLayoutResource() {
        return R.layout.dialog_authentication;
    }

    @Override
    public void onResume() {
        super.onResume();
        mFingerprintHelper.startListening(mCryptoObject);
    }

    @Override
    public void onPause() {
        super.onPause();
        mFingerprintHelper.stopListening();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!mAuthenticated && mAuthenticationCallback != null) {
            mAuthenticationCallback.onCancel();
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        mActivity = getActivity();

        mFingerprintHelper = FingerprintHelper.getInstance(getActivity());
        mFingerprintHelper.setFingerprintCallback(new FingerprintHelper.FingerprintCallback() {

            int tryTimes = 5;

            @Override
            public void onAuthenticated() {
                mSwirlView.setState(SwirlView.State.OFF);
                mTvState.setTextColor(ContextCompat.getColor(mActivity, R.color.black_26p));
                mTvState.setText(R.string.fingerprint_verify_success);
                mSwirlView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        dismiss();
                        if (mAuthenticationCallback != null) {
                            mAuthenticationCallback.onAuthenticated();
                        }
                    }
                }, 500);
            }

            @Override
            public void onFailed() {
                mSwirlView.setState(SwirlView.State.ERROR);
                mTvState.setTextColor(ContextCompat.getColor(mActivity, R.color.error));
                if (tryTimes == 1) {
                    mTvState.setText(R.string.fingerprint_cannot_use_to_verify);
                } else {
                    tryTimes--;
                    String warning = getString(R.string.fingerprint_error_part_1);
                    if (LocaleUtil.isEnglish(mActivity)) {
                        warning += " ";
                    }
                    warning += LocaleUtil.getTimesStr(mActivity, tryTimes);
                    mTvState.setText(warning);
                }
            }

            @Override
            public void onError() {
                mSwirlView.setState(SwirlView.State.ERROR);
                mTvState.setTextColor(ContextCompat.getColor(mActivity, R.color.error));
                mTvState.setText(R.string.fingerprint_cannot_use_to_verify);
            }
        });

        findViews();
        initUI();
        setEvents();

        return mContentView;
    }

    private void findViews() {
        mTvTitle      = f(R.id.tv_title_authentication);
        mTvContent    = f(R.id.tv_content_authentication);
        mSwirlView    = f(R.id.swirlview_fingerprint);
        mTvState      = f(R.id.tv_fingerprint_state);
        mTvSecondAsBt = f(R.id.tv_second_as_bt_authentication);
    }

    private void initUI() {
        mTvTitle.setTextColor(mAccentColor);
        mTvSecondAsBt.setTextColor(mAccentColor);

        mTvTitle.setText(mTitle);
        mTvContent.setText(R.string.confirm_fingerprint);

        mSwirlView.postDelayed(new Runnable() {
            @Override
            public void run() {
                mSwirlView.setState(SwirlView.State.ON);
            }
        }, 200);

        mTvState.setText(R.string.touch_sensor);
        mTvSecondAsBt.setText(R.string.use_pattern);
    }

    private void setEvents() {
        f(R.id.tv_cancel_as_bt_authentication).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        final String cp = getActivity().getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, "");
        mTvSecondAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAuthenticated = true;
                dismiss();
                PatternLockDialogFragment pldf = new PatternLockDialogFragment();
                pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE);
                pldf.setAccentColor(mAccentColor);
                pldf.setCorrectPassword(cp);
                pldf.setValidateTitle(getString(R.string.check_private_thing));
                pldf.setAuthenticationCallback(mAuthenticationCallback);
                pldf.show(getFragmentManager(), PatternLockDialogFragment.TAG);
            }
        });
    }

    public void setAccentColor(int accentColor) {
        mAccentColor = accentColor;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public void setAuthenticationCallback(AuthenticationHelper.AuthenticationCallback authenticationCallback) {
        mAuthenticationCallback = authenticationCallback;
    }

    public void setCryptoObject(FingerprintManager.CryptoObject cryptoObject) {
        mCryptoObject = cryptoObject;
    }
}
