package com.ywwynm.everythingdone.activities;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.LicenseDialogFragment;
import com.ywwynm.everythingdone.fragments.ThreeActionsAlertDialogFragment;
import com.ywwynm.everythingdone.helpers.SendInfoHelper;
import com.ywwynm.everythingdone.permission.SimplePermissionCallback;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FontCache;

public class AboutActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "AboutActivity";

    private View    mStatusBar;
    private Toolbar mActionbar;

    private FloatingActionButton mFabHead;
    private TextView  mTvYwwynm;
    private TextView  mTvEverythingDone;
    private TextView  mTvVersion;

    private FloatingActionButton mFab;

    private FrameLayout mFlBottom;

    private ThreeActionsAlertDialogFragment mSupportDf;
    private AlertDialogFragment mDonateDf;

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_about;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_about, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.act_share:
                doWithPermissionChecked(
                        new SimplePermissionCallback(this) {
                            @Override
                            public void onGranted() {
                                SendInfoHelper.shareApp(AboutActivity.this);
                            }
                        },
                        Def.Communication.REQUEST_PERMISSION_SHARE_APP,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE);
                break;
            case R.id.act_feedback:
                SendInfoHelper.sendFeedback(this, false);
                break;
            default:break;
        }
        return true;
    }

    @Override
    protected void initMembers() { }

    @Override
    protected void findViews() {
        mStatusBar = f(R.id.view_status_bar);
        mActionbar = f(R.id.actionbar);

        mFabHead          = f(R.id.fab_about_head);
        mTvYwwynm         = f(R.id.tv_ywwynm);
        mTvEverythingDone = f(R.id.tv_everything_done);
        mTvVersion        = f(R.id.tv_version);

        mFab = f(R.id.fab_support);

        mFlBottom = f(R.id.fl_bottom_about);
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this);
        DisplayUtil.expandStatusBarViewAboveKitkat(mStatusBar);
        DisplayUtil.darkStatusBar(this);

        if (DeviceUtil.hasKitKatApi()) {
            if (DisplayUtil.hasNavigationBar(this)) {
                int navHeight = DisplayUtil.getNavigationBarHeight(this);
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mFab.getLayoutParams();
                params.bottomMargin += navHeight;
                mFab.requestLayout();
                mFlBottom.setPadding(0, 0, 0, navHeight);
            }
        }

        Typeface tf = FontCache.get(Def.Meta.ROBOTO_MONO, this);
        mTvYwwynm.setTypeface(tf);
        mTvEverythingDone.setTypeface(tf);

        mTvVersion.append(" " + BuildConfig.VERSION_NAME);

        TextView tvLicense = f(R.id.tv_license_as_bt);
        Paint paint = tvLicense.getPaint();
        paint.setFlags(paint.getFlags() | Paint.UNDERLINE_TEXT_FLAG);
    }

    @Override
    protected void setActionbar() {
        setSupportActionBar(mActionbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        mActionbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void setEvents() {
        mFabHead.setRippleColor(DisplayUtil.getLightColor(
                DisplayUtil.getRandomColor(this), this));
        mFabHead.setOnClickListener(new View.OnClickListener() {

            long[] times = new long[16];

            @Override
            public void onClick(View v) {
                Context context = AboutActivity.this;
                mFabHead.setRippleColor(DisplayUtil.getLightColor(
                        DisplayUtil.getRandomColor(context), context));
                System.arraycopy(times, 1, times, 0, times.length - 1);
                times[times.length - 1] = System.currentTimeMillis();
                if (times[0] >= (System.currentTimeMillis() - 500000)) {
                    Toast.makeText(context, getString(R.string.fly_sing_qiong), Toast.LENGTH_LONG).show();
                    for (int i = 0; i < times.length; i++) {
                        times[i] = 0;
                    }
                }
            }
        });

        mFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mSupportDf == null) {
                    initSupportDialog();
                }
                mSupportDf.show(getFragmentManager(), ThreeActionsAlertDialogFragment.TAG);
            }
        });
    }

    private void initSupportDialog() {
        mSupportDf = new ThreeActionsAlertDialogFragment();
        int color = ContextCompat.getColor(this, R.color.app_pink);
        mSupportDf.setTitleColor(color);
        mSupportDf.setContinueColor(color);
        mSupportDf.setTitle(getString(R.string.act_support));
        mSupportDf.setContent(getString(R.string.support_content));
        mSupportDf.setFirstAction(getString(R.string.support_star));
        mSupportDf.setSecondAction(getString(R.string.support_donate));
        mSupportDf.setOnClickListener(new ThreeActionsAlertDialogFragment.OnClickListener() {
            @Override
            public void onFirstClicked() {
                SendInfoHelper.rateApp(AboutActivity.this);
            }

            @Override
            public void onSecondClicked() {
                if (mDonateDf == null) {
                    initDonateDialog();
                }
                mDonateDf.show(getFragmentManager(), AlertDialogFragment.TAG);
            }

            @Override
            public void onThirdClicked() { }
        });
    }

    private void initDonateDialog() {
        mDonateDf = new AlertDialogFragment();
        int color = ContextCompat.getColor(this, R.color.app_pink);
        mDonateDf.setTitleColor(color);
        mDonateDf.setConfirmColor(color);
        mDonateDf.setTitle(getString(R.string.support_donate));
        mDonateDf.setContent(getString(R.string.support_donate_content));
        mDonateDf.setConfirmText(getString(R.string.support_donate_copy_name));
        mDonateDf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                ClipboardManager clipboardManager = (ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                ClipData clipData = ClipData.newPlainText(
                        getString(R.string.support_donate_clip_title),
                        getString(R.string.support_donate_clip_content));
                clipboardManager.setPrimaryClip(clipData);
                Toast.makeText(AboutActivity.this, R.string.success_clipboard,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void showLicenseDialog(View view) {
        LicenseDialogFragment ldf = new LicenseDialogFragment();
        ldf.show(getFragmentManager(), LicenseDialogFragment.TAG);
    }
}
