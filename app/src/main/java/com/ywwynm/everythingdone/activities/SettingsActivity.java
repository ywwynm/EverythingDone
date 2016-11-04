package com.ywwynm.everythingdone.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment;
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment;
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.helpers.AutoNotifyHelper;
import com.ywwynm.everythingdone.helpers.BackupHelper;
import com.ywwynm.everythingdone.helpers.FingerprintHelper;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.permission.SimplePermissionCallback;
import com.ywwynm.everythingdone.receivers.LocaleChangeReceiver;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;
import com.ywwynm.everythingdone.utils.UriPathConverter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.ywwynm.everythingdone.Def.Communication.REQUEST_CHOOSE_AUDIO_FILE;
import static com.ywwynm.everythingdone.Def.Communication.REQUEST_CHOOSE_IMAGE_FILE;

@SuppressLint("CommitPrefEdits")
public class SettingsActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "SettingsActivity";

    private SharedPreferences mPreferences;

    private int mAccentColor;

    // group ui
    public static final String DEFAULT_DRAWER_HEADER = "default_drawer_header";
    private LinearLayout mLlDrawerHeader;
    private TextView     mTvDrawerHeader;

    private LinearLayout mLlLanguage;
    private TextView     mTvLanguage;

    private RelativeLayout mRlToggleCli; // toggle checklist item
    private CheckBox       mCbToggleCli;
    private boolean        mToggleCliOtc;

    private RelativeLayout mRlTwiceBackAsBt;
    private CheckBox       mCbTwiceBack;

    // group ringtone
    private static String[] sKeysRingtone = {
            Def.Meta.KEY_RINGTONE_REMINDER,
            Def.Meta.KEY_RINGTONE_HABIT,
            Def.Meta.KEY_RINGTONE_GOAL,
            Def.Meta.KEY_RINGTONE_AUTO_NOTIFY
    };
    public static final String      FOLLOW_SYSTEM = "follow_system";
    private static List<String>     sRingtoneTitleList;
    private static List<Uri>        sRingtoneUriList;
    private RingtoneManager         mRingtoneManager;
    private Uri[]                   mChosenRingtoneUris;
    private String[]                mChosenRingtoneTitles;
    private int                     mChoosingIndex;
    private Ringtone                mPlayingRingtone;
    private LinearLayout[]          mLlsRingtone;
    private TextView[]              mTvsRingtone;
    private ChooserDialogFragment[] mCdfsRingtone;

    // group data
    private TextView              mTvBackupAsBt;
    private LinearLayout          mLlRestoreAsBt;
    private TextView              mTvRestoreLastInfo;
    private LoadingDialogFragment mLdfBackup;
    private LoadingDialogFragment mLdfRestore;

    // group privacy
    private LinearLayout   mLlSetPasswordAsBt;
    private TextView       mTvSetPassword;
    private RelativeLayout mRlFgprtAsBt;
    private TextView       mTvFgprtTitle;
    private TextView       mTvFgprtDscrpt;
    private CheckBox       mCbFgprt;

    // group advanced
    private RelativeLayout mRlQuickCreateAsBt;
    private CheckBox       mCbQuickCreate;

    private static List<String>   sANItems;
    private int                   mANPicked;
    private LinearLayout          mLlANAsBt;
    private TextView              mTvAN;
    private ImageView             mIvANAsBt;
    private ChooserDialogFragment mCdfAN;

    private LinearLayout mLlANRingtoneAsBt;
    private TextView     mTvANRingtoneTitle;
    private TextView     mTvANRingtone;

    private void initSystemRingtoneList(final LoadingDialogFragment ldf, final int index) {
        sRingtoneTitleList = new ArrayList<>();
        sRingtoneUriList   = new ArrayList<>();
        new Thread() {
            @Override
            public void run() {
                Context context = App.getApp();
                RingtoneManager manager = new RingtoneManager(context);
                manager.setType(RingtoneManager.TYPE_NOTIFICATION);

                Ringtone dr = RingtoneManager.getRingtone(
                        context, Settings.System.DEFAULT_NOTIFICATION_URI);
                sRingtoneUriList.add(Settings.System.DEFAULT_NOTIFICATION_URI);
                sRingtoneTitleList.add(dr.getTitle(context));

                SharedPreferences preferences = context.getSharedPreferences(
                        Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
                for (String key : sKeysRingtone) {
                    String uriStr = preferences.getString(key, FOLLOW_SYSTEM);
                    if (FOLLOW_SYSTEM.equals(uriStr)) continue;

                    Uri uri = Uri.parse(uriStr);
                    if (manager.getRingtonePosition(uri) != -1) continue;

                    String pathName = UriPathConverter.getLocalPathName(context, uri);
                    if (pathName == null) {
                        preferences.edit().putString(key, FOLLOW_SYSTEM).commit();
                        continue;
                    }

                    File file = new File(pathName);
                    if (!file.exists()) {
                        preferences.edit().putString(key, FOLLOW_SYSTEM).commit();
                    } else if (!sRingtoneUriList.contains(uri)) {
                        sRingtoneUriList.add(uri);
                        sRingtoneTitleList.add(getRingtoneTitle(context, manager, uri));
                    }
                }

                Cursor cursor = manager.getCursor();
                int count = cursor.getCount();
                for (int i = 0; i < count; i++) {
                    sRingtoneUriList.add(manager.getRingtoneUri(i));
                    sRingtoneTitleList.add(manager.getRingtone(i).getTitle(context));
                }
                cursor.close();

                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        ldf.dismiss();
                        showRingtoneDialog(index);
                    }
                });
            }
        }.start();
    }

    private static String getRingtoneTitle(Context context, RingtoneManager ringtoneManager, Uri uri) {
        if (isFileRingtone(ringtoneManager, uri)) {
            String pathName = UriPathConverter.getLocalPathName(context, uri);
            return FileUtil.getNameWithoutPostfix(pathName);
        } else {
            Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
            return ringtone.getTitle(context);
        }
    }

    private static boolean isFileRingtone(RingtoneManager ringtoneManager, Uri uri) {
        return uri != Settings.System.DEFAULT_NOTIFICATION_URI
                && ringtoneManager.getRingtonePosition(uri) == -1;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Uri uri = data.getData();
            String pathName = UriPathConverter.getLocalPathName(this, uri);
            View root = f(R.id.rl_settings_root);
            if (pathName == null) {
                Snackbar.make(root, R.string.error_cannot_add_from_network,
                        Snackbar.LENGTH_SHORT).show();
                return;
            }

            String postfix = FileUtil.getPostfix(pathName);
            if ((requestCode == REQUEST_CHOOSE_IMAGE_FILE
                    && !AttachmentHelper.isImageFile(postfix))
                ||
                (requestCode == REQUEST_CHOOSE_AUDIO_FILE
                        && !AttachmentHelper.isAudioFile(postfix))) {
                Snackbar.make(root, R.string.error_unsupported_file_type,
                        Snackbar.LENGTH_SHORT).show();
                return;
            }

            if (requestCode == REQUEST_CHOOSE_IMAGE_FILE) {
                mTvDrawerHeader.setTextSize(12);
                mTvDrawerHeader.setText(pathName);
            } else if (requestCode == REQUEST_CHOOSE_AUDIO_FILE) {
                String audioName = FileUtil.getNameWithoutPostfix(pathName);
                File srcFile = new File(pathName);
                if (!DeviceUtil.hasNougatApi()) {
                    uri = Uri.fromFile(srcFile);
                } else {
                    File dstFile = FileUtil.createFile(Def.Meta.APP_FILE_DIR + "/ringtone",
                            srcFile.getName());
                    try {
                        FileUtil.copyFile(srcFile, dstFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                        // ignore this for the time being
                    }
                    uri = Uri.fromFile(dstFile);
                }
                if (!sRingtoneUriList.contains(uri)) {
                    sRingtoneTitleList.add(1, audioName);
                    sRingtoneUriList.add(1, uri);
                }
                mCdfsRingtone[mChoosingIndex].pick(1);
                mCdfsRingtone[mChoosingIndex].notifyDataSetChanged();

                final Ringtone ringtone = RingtoneManager.getRingtone(this, uri);
                ringtone.play();
                mPlayingRingtone = ringtone;
                root.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (ringtone.isPlaying()) {
                            ringtone.stop();
                        }
                    }
                }, 6000);

            }
        }
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_settings;
    }

    @Override
    protected void onResume() {
        super.onResume();
        initUiPrivacy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPlayingRingtone != null && mPlayingRingtone.isPlaying()) {
            mPlayingRingtone.stop();
        }
    }

    private void initStaticVariables() {
        if (sANItems == null) {
            sANItems = new ArrayList<>();
            sANItems.add(getString(R.string.auto_notify_off));
            for (int i = 0; i < AutoNotifyHelper.AUTO_NOTIFY_TIMES.length; i++) {
                sANItems.add(DateTimeUtil.getDateTimeStr(
                        AutoNotifyHelper.AUTO_NOTIFY_TYPES[i],
                        AutoNotifyHelper.AUTO_NOTIFY_TIMES[i], this));
            }
        }
    }

    @Override
    protected void initMembers() {
        initStaticVariables();

        mPreferences = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
        mAccentColor = ContextCompat.getColor(this, R.color.blue_deep);

        initMembersRingtone();
    }

    private void initMembersRingtone() {
        mRingtoneManager = new RingtoneManager(this);
        mRingtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION);

        mChosenRingtoneUris   = new Uri[4];
        mChosenRingtoneTitles = new String[4];
        mCdfsRingtone         = new ChooserDialogFragment[4];

        for (int i = 0; i < mChosenRingtoneUris.length; i++) {
            String value = mPreferences.getString(sKeysRingtone[i], FOLLOW_SYSTEM);
            if (FOLLOW_SYSTEM.equals(value)) {
                mChosenRingtoneUris[i] = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                Uri uri = Uri.parse(value);
                mChosenRingtoneUris[i] = uri;
                if (isFileRingtone(mRingtoneManager, uri)) {
                    String pathName = UriPathConverter.getLocalPathName(this, uri);
                    if (pathName == null || !new File(pathName).exists()) {
                        mChosenRingtoneUris[i] = Settings.System.DEFAULT_NOTIFICATION_URI;
                    }
                }
            }

            mChosenRingtoneTitles[i] = getRingtoneTitle(
                    this, mRingtoneManager, mChosenRingtoneUris[i]);
        }
    }

    @Override
    protected void findViews() {
        // ui
        mLlDrawerHeader = f(R.id.ll_change_drawer_header_as_bt);
        mTvDrawerHeader = f(R.id.tv_drawer_header_path);

        mLlLanguage = f(R.id.ll_app_language_as_bt);
        mTvLanguage = f(R.id.tv_app_language);

        mRlToggleCli = f(R.id.rl_toggle_checklist_as_bt);
        mCbToggleCli = f(R.id.cb_toggle_checklist);

        mRlTwiceBackAsBt = f(R.id.rl_twice_back_as_bt);
        mCbTwiceBack     = f(R.id.cb_twice_back);

        // ringtone
        mLlsRingtone    = new LinearLayout[4];
        mLlsRingtone[0] = f(R.id.ll_ringtone_reminder_as_bt);
        mLlsRingtone[1] = f(R.id.ll_ringtone_habit_as_bt);
        mLlsRingtone[2] = f(R.id.ll_ringtone_goal_as_bt);

        mTvsRingtone    = new TextView[4];
        mTvsRingtone[0] = f(R.id.tv_ringtone_reminder);
        mTvsRingtone[1] = f(R.id.tv_ringtone_habit);
        mTvsRingtone[2] = f(R.id.tv_ringtone_goal);

        // data
        mTvBackupAsBt      = f(R.id.tv_backup_as_bt);
        mLlRestoreAsBt     = f(R.id.ll_restore_as_bt);
        mTvRestoreLastInfo = f(R.id.tv_restore_last_info);

        // privacy
        mLlSetPasswordAsBt = f(R.id.ll_set_password_as_bt);
        mTvSetPassword     = f(R.id.tv_set_password_title);
        mRlFgprtAsBt       = f(R.id.rl_use_fingerprint_as_bt);
        mTvFgprtTitle      = f(R.id.tv_use_fingerprint_title);
        mTvFgprtDscrpt     = f(R.id.tv_use_fingerprint_description);
        mCbFgprt           = f(R.id.cb_use_fingerprint);

        // advanced
        mRlQuickCreateAsBt = f(R.id.rl_quick_create_as_bt);
        mCbQuickCreate     = f(R.id.cb_quick_create);

        mLlANAsBt = f(R.id.ll_advanced_auto_notify_as_bt);
        mTvAN     = f(R.id.tv_advanced_auto_notify_time);
        mIvANAsBt = f(R.id.iv_auto_notify_help_as_bt);

        mLlANRingtoneAsBt  = f(R.id.ll_ringtone_auto_notify_as_bt);
        mTvANRingtoneTitle = f(R.id.tv_ringtone_auto_notify_title);
        mTvANRingtone      = f(R.id.tv_ringtone_auto_notify);

        mLlsRingtone[3] = mLlANRingtoneAsBt;
        mTvsRingtone[3] = mTvANRingtone;
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this);
        DisplayUtil.expandStatusBarViewAboveKitkat(f(R.id.view_status_bar));
        DisplayUtil.darkStatusBar(this);

        EdgeEffectUtil.forScrollView((ScrollView) f(R.id.sv_settings),
                ContextCompat.getColor(this, R.color.blue_deep));

        initUiUserInterface();
        initUiRingtone();
        updateUiRestore();
        initUiAdvanced();
    }

    private void initUiUserInterface() {
        String header = mPreferences.getString(
                Def.Meta.KEY_DRAWER_HEADER, DEFAULT_DRAWER_HEADER);
        if (DEFAULT_DRAWER_HEADER.equals(header)) {
            mTvDrawerHeader.setTextSize(14);
            mTvDrawerHeader.setText(R.string.default_drawer_header);
        } else {
            mTvDrawerHeader.setTextSize(12);
            mTvDrawerHeader.setText(header);
        }

        mTvLanguage.setText(LocaleUtil.getLanguageDescription(LocaleUtil.getMyLanguageCode()));

        mToggleCliOtc = mPreferences.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, false);
        mCbToggleCli.setChecked(mToggleCliOtc);

        boolean twiceBack = mPreferences.getBoolean(Def.Meta.KEY_TWICE_BACK, false);
        mCbTwiceBack.setChecked(twiceBack);
    }

    private void initUiRingtone() {
        for (int i = 0; i < 3; i++) {
            mTvsRingtone[i].setText(mChosenRingtoneTitles[i]);
        }
    }

    private void updateUiRestore() {
        mTvRestoreLastInfo.setText(BackupHelper.getLastBackupTimeString());
    }

    private void initUiPrivacy() {
        String password = mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        if (password == null) {
            mTvSetPassword.setText(R.string.set_app_password);
        } else {
            mTvSetPassword.setText(R.string.change_app_password);
        }

        initUiFingerprint();
    }

    private void initUiFingerprint() {
        if (!DeviceUtil.hasMarshmallowApi()) {
            mRlFgprtAsBt.setEnabled(false);
            mCbFgprt.setEnabled(false);
            mCbFgprt.setChecked(false);
            mTvFgprtTitle.setTextColor(ContextCompat.getColor(this, R.color.black_14p));
            mTvFgprtDscrpt.setTextColor(ContextCompat.getColor(this, R.color.black_10p));
            mTvFgprtDscrpt.setText(R.string.not_support_fgprt);
            return;
        }

        FingerprintHelper fph = FingerprintHelper.getInstance();
        String password = mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        if (password == null || !fph.isFingerprintReady()) {
            mRlFgprtAsBt.setEnabled(false);
            mCbFgprt.setEnabled(false);
            mTvFgprtTitle.setTextColor(ContextCompat.getColor(this, R.color.black_14p));
            mTvFgprtDscrpt.setTextColor(ContextCompat.getColor(this, R.color.black_10p));

            if (password == null) {
                mTvFgprtDscrpt.setText(R.string.password_not_set);
            } else {
                mTvFgprtDscrpt.setTextSize(LocaleUtil.isChinese(this) ? 14 : 12);
                if (!fph.supportFingerprint()) {
                    mTvFgprtDscrpt.setText(R.string.not_support_fgprt);
                } else if (!fph.hasSystemFingerprintSet()) {
                    mTvFgprtDscrpt.setText(R.string.system_fgprt_not_set);
                } else if (!fph.hasFingerprintRegistered()) {
                    mTvFgprtDscrpt.setText(R.string.fgprt_not_enrolled);
                }
            }
        } else {
            mRlFgprtAsBt.setEnabled(true);
            mCbFgprt.setEnabled(true);
            mTvFgprtTitle.setTextColor(ContextCompat.getColor(this, R.color.black_54p));
            mTvFgprtDscrpt.setTextColor(ContextCompat.getColor(this, R.color.black_26p));
            mTvFgprtDscrpt.setText(R.string.use_fingerprint_to_verify);
        }

        boolean useFingerprint = mPreferences.getBoolean(Def.Meta.KEY_USE_FINGERPRINT, false);
        mCbFgprt.setChecked(useFingerprint);
    }

    private void initUiAdvanced() {
        // quick create
        boolean qc = mPreferences.getBoolean(Def.Meta.KEY_QUICK_CREATE, true);
        mCbQuickCreate.setChecked(qc);

        // auto notify
        int index = mPreferences.getInt(Def.Meta.KEY_AUTO_NOTIFY, 0);
        if (index == 0) {
            mTvAN.setText(getString(R.string.auto_notify_off));
        } else {
            mTvAN.setText(
                    DateTimeUtil.getDateTimeStr(
                            AutoNotifyHelper.AUTO_NOTIFY_TYPES[index - 1],
                            AutoNotifyHelper.AUTO_NOTIFY_TIMES[index - 1], this));
        }
        mANPicked = index;
        updateUiAutoNotifyRingtone(index != 0);
    }

    private void updateUiAutoNotifyRingtone(boolean enable) {
        if (enable) {
            mLlANRingtoneAsBt.setEnabled(true);
            mTvANRingtoneTitle.setTextColor(ContextCompat.getColor(this, R.color.black_54p));
            mTvANRingtone.setText(mChosenRingtoneTitles[mChosenRingtoneTitles.length - 1]);
        } else {
            mLlANRingtoneAsBt.setEnabled(false);
            mTvANRingtoneTitle.setTextColor(ContextCompat.getColor(this, R.color.black_14p));
            mTvANRingtone.setText("");
        }
    }

    @Override
    protected void setActionbar() {
        Toolbar toolbar = f(R.id.actionbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void setEvents() {
        setUiEvents();
        setRingtoneEvents();
        setDataEvents();
        setPrivacyEvents();
        setAdvancedEvents();
    }

    private void setUiEvents() {
        mLlDrawerHeader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangeDrawerHeaderDialog();
            }
        });

        mLlLanguage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChooseLanguageDialog();
            }
        });

        mRlToggleCli.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbToggleCli.setChecked(!mCbToggleCli.isChecked());
            }
        });

        mRlTwiceBackAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbTwiceBack.setChecked(!mCbTwiceBack.isChecked());
            }
        });
    }

    private void setRingtoneEvents() {
        for (int i = 0; i < 4; i++) {
            final int j = i;
            mLlsRingtone[j].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sRingtoneTitleList == null) {
                        final LoadingDialogFragment ldf = createLoadingDialog(
                                R.string.please_wait, R.string.ringtone_loading);
                        ldf.show(getFragmentManager(), LoadingDialogFragment.TAG);
                        initSystemRingtoneList(ldf, j);
                    } else {
                        showRingtoneDialog(j);
                    }
                }
            });
        }
    }

    private void showRingtoneDialog(int index) {
        if (mCdfsRingtone[index] == null) {
            initRingtoneFragment(index);
        }
        mCdfsRingtone[index].show(
                getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void setDataEvents() {
        mTvBackupAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBackupDialog();
            }
        });
        mLlRestoreAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRestoreDialog();
            }
        });
    }

    private void setPrivacyEvents() {
        mLlSetPasswordAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String passwordBefore = mPreferences.getString(
                        Def.Meta.KEY_PRIVATE_PASSWORD, null);
                if (passwordBefore == null) {
                    beginSetPassword();
                } else {
                    beginChangePassword(passwordBefore);
                }
            }
        });

        mRlFgprtAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean toUseFingerprint = !mCbFgprt.isChecked();
                if (!toUseFingerprint) {
                    final PatternLockDialogFragment pldf = new PatternLockDialogFragment();
                    pldf.setValidateTitle(getString(R.string.use_fingerprint));
                    pldf.setCorrectPassword(mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null));
                    pldf.setAuthenticationCallback(new AuthenticationHelper.AuthenticationCallback() {
                        @Override
                        public void onAuthenticated() {
                            mCbFgprt.setChecked(false);
                        }

                        @Override
                        public void onCancel() {

                        }
                    });
                    pldf.setAccentColor(mAccentColor);
                    pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE);
                    pldf.show(getFragmentManager(), PatternLockDialogFragment.TAG);
                } else {
                    mCbFgprt.setChecked(true);
                }
            }
        });
    }

    private void beginSetPassword() {
        final PatternLockDialogFragment pldf = new PatternLockDialogFragment();
        pldf.setType(PatternLockDialogFragment.TYPE_SET);
        pldf.setAccentColor(mAccentColor);
        pldf.setPasswordSetDoneListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPreferences.edit()
                        .putString(Def.Meta.KEY_PRIVATE_PASSWORD, pldf.getPassword()).apply();
                initUiPrivacy();
            }
        });
        pldf.show(getFragmentManager(), PatternLockDialogFragment.TAG);
    }

    private void beginChangePassword(String passwordBefore) {
        PatternLockDialogFragment pldf = new PatternLockDialogFragment();
        pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE);
        pldf.setAccentColor(mAccentColor);
        pldf.setCorrectPassword(passwordBefore);
        pldf.setValidateTitle(getString(R.string.change_app_password));
        pldf.setAuthenticationCallback(new AuthenticationHelper.AuthenticationCallback() {
            @Override
            public void onAuthenticated() {
                beginSetPassword();
            }

            @Override
            public void onCancel() {

            }
        });
        pldf.show(getFragmentManager(), PatternLockDialogFragment.TAG);
    }

    private void setAdvancedEvents() {
        setQuickCreateEvents();

        mLlANAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCdfAN == null) {
                    initAutoNotifyFragment();
                }
                mCdfAN.show(getFragmentManager(), ChooserDialogFragment.TAG);
            }
        });

        mIvANAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final AlertDialogFragment adf = createAlertDialog(
                        false, R.string.auto_notify, R.string.auto_notify_help_info);
                adf.show(getFragmentManager(), AlertDialogFragment.TAG);
            }
        });
    }

    private void setQuickCreateEvents() {
        mRlQuickCreateAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbQuickCreate.toggle();
            }
        });

        mCbQuickCreate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    SystemNotificationUtil.createQuickCreateNotification(App.getApp());
                } else {
                    NotificationManagerCompat.from(App.getApp()).cancel(
                            Def.Meta.ONGOING_NOTIFICATION_ID);
                }
            }
        });
    }

    private void showChangeDrawerHeaderDialog() {
        final TwoOptionsDialogFragment todf = new TwoOptionsDialogFragment();
        todf.setStartAction(R.drawable.act_default_drawer_header, R.string.default_drawer_header,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mTvDrawerHeader.setTextSize(14);
                        mTvDrawerHeader.setText(R.string.default_drawer_header);
                        todf.dismiss();
                    }
                });
        todf.setEndAction(R.drawable.act_select_image_as_drawer_header, R.string.more,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        todf.dismiss();
                        doWithPermissionChecked(
                                new SimplePermissionCallback(SettingsActivity.this) {
                                    @Override
                                    public void onGranted() {
                                        startChooseImageAsDrawerHeader();
                                    }
                                },
                                Def.Communication.REQUEST_PERMISSION_CHOOSE_IMAGE_FILE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }
                });
        todf.show(getFragmentManager(), TwoOptionsDialogFragment.TAG);
    }

    private void startChooseImageAsDrawerHeader() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(
                Intent.createChooser(intent, getString(R.string.act_choose_image_as_drawer_header)),
                Def.Communication.REQUEST_CHOOSE_IMAGE_FILE);
    }

    private void showChooseLanguageDialog() {
        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mAccentColor);
        cdf.setTitle(getString(R.string.change_app_language));
        cdf.setShouldShowMore(false);
        final Resources resources = getResources();
        final String[] languages = resources.getStringArray(R.array.languages);
        cdf.setItems(Arrays.asList(languages));

        String display = mTvLanguage.getText().toString();
        int index = 0;
        for (int i = 0; i < languages.length; i++) {
            if (display.equals(languages[i])) {
                index = i;
                break;
            }
        }
        final int initialIndex = index;
        cdf.setInitialIndex(initialIndex);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pickedIndex = cdf.getPickedIndex();
                if (pickedIndex == initialIndex) {
                    return;
                }
                Context context = SettingsActivity.this;
                mPreferences.edit().putString(Def.Meta.KEY_LANGUAGE_CODE,
                        resources.getStringArray(R.array.language_codes)[pickedIndex]).commit();
                if (App.getDoingThingId() != -1) {
                    Toast.makeText(context, R.string.doing_failed_change_language,
                            Toast.LENGTH_LONG).show();
                    stopService(new Intent(context, DoingService.class));
                }
                App.killMeAndRestart(context, null, 0);

                Intent intent = new Intent(context, LocaleChangeReceiver.class);
                intent.setAction(Def.Communication.BROADCAST_ACTION_RESP_LOCALE_CHANGE);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                        0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 600, pendingIntent);
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void showBackupDialog() {
        final AlertDialogFragment adf = createAlertDialog(
                true, R.string.backup, R.string.backup_content);
        adf.setConfirmText(getString(R.string.backup_start));
        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                authenticateToBackup();
            }
        });
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void authenticateToBackup() {
        String password = mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        AuthenticationHelper.authenticate(this, mAccentColor, getString(R.string.backup_start), password,
                new AuthenticationHelper.AuthenticationCallback() {
                    @Override
                    public void onAuthenticated() {
                        doWithPermissionChecked(
                                new SimplePermissionCallback(SettingsActivity.this) {
                                    @Override
                                    public void onGranted() {
                                        showBackupLoadingDialog();
                                        new BackupTask().execute();
                                    }
                                },
                                Def.Communication.REQUEST_PERMISSION_BACKUP,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }

                    @Override
                    public void onCancel() { }
                });
    }

    private void showBackupLoadingDialog() {
        if (mLdfBackup == null) {
            mLdfBackup = createLoadingDialog(
                    R.string.backup_loading_title, R.string.backup_loading_content);
        }
        mLdfBackup.show(getFragmentManager(), LoadingDialogFragment.TAG);
    }

    private void showRestoreDialog() {
        final AlertDialogFragment adf = createAlertDialog(
                true, R.string.restore, R.string.restore_content);
        adf.setConfirmText(getString(R.string.restore_start));
        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                authenticateToRestore();
            }
        });
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void authenticateToRestore() {
        String password = mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        AuthenticationHelper.authenticate(this, mAccentColor, getString(R.string.restore_start), password,
                new AuthenticationHelper.AuthenticationCallback() {
                    @Override
                    public void onAuthenticated() {
                        doWithPermissionChecked(
                                new SimplePermissionCallback(SettingsActivity.this) {
                                    @Override
                                    public void onGranted() {
                                        showRestoreLoadingDialog();
                                        new RestoreTask().execute();
                                    }
                                },
                                Def.Communication.REQUEST_PERMISSION_RESTORE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }

                    @Override
                    public void onCancel() { }
                });
    }

    private void showRestoreLoadingDialog() {
        if (mLdfRestore == null) {
            mLdfRestore = createLoadingDialog(
                    R.string.restore_loading_title, R.string.restore_loading_content);
        }
        mLdfRestore.show(getFragmentManager(), LoadingDialogFragment.TAG);
    }

    private void initRingtoneFragment(final int index) {
        mCdfsRingtone[index] = new ChooserDialogFragment();
        final ChooserDialogFragment cdf = mCdfsRingtone[index];
        cdf.setAccentColor(mAccentColor);
        cdf.setTitle(getString(R.string.chooser_ringtone));
        cdf.setItems(sRingtoneTitleList);
        cdf.setInitialIndex(sRingtoneUriList.indexOf(mChosenRingtoneUris[index]));
        cdf.setShouldOverScroll(true);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pickedIndex = cdf.getPickedIndex();
                mTvsRingtone[index].setText(sRingtoneTitleList.get(pickedIndex));
                cdf.setInitialIndex(pickedIndex);
                mChosenRingtoneUris[index] = sRingtoneUriList.get(pickedIndex);
                mChosenRingtoneTitles[index] = sRingtoneTitleList.get(pickedIndex);
            }
        });
        cdf.setMoreListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mChoosingIndex = index;
                doWithPermissionChecked(
                        new SimplePermissionCallback(SettingsActivity.this) {
                            @Override
                            public void onGranted() {
                                startChooseRingtoneFromStorage();
                            }
                        },
                        Def.Communication.REQUEST_PERMISSION_CHOOSE_AUDIO_FILE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        });
//        if (!DeviceUtil.hasNougatApi()) {
//
//        } else { // TODO: 2016/9/2 cannot set audio file as ringtone above N
//            cdf.setShouldShowMore(false);
//        }
        cdf.setOnItemClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPlayingRingtone != null) {
                    mPlayingRingtone.stop();
                }

                int pickedIndex = cdf.getPickedIndex();
                if (pickedIndex == -1) {
                    throw new IllegalStateException(
                            "user picked a ringtone but getPickedIndex returned -1");
                }
                Uri uri = sRingtoneUriList.get(pickedIndex);
                Context context = SettingsActivity.this;
                if (isFileRingtone(mRingtoneManager, uri)) {
                    String pathName = UriPathConverter.getLocalPathName(context, uri);
                    if (pathName == null) { // TODO: 2016/5/16 strange behavior here
                        return;
                    }
                    uri = Uri.fromFile(new File(pathName));
                }
                Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
                ringtone.play();
                mPlayingRingtone = ringtone;
            }
        });
        cdf.setOnDismissListener(new ChooserDialogFragment.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (mPlayingRingtone != null) {
                    mPlayingRingtone.stop();
                }
            }
        });
    }

    private void startChooseRingtoneFromStorage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        startActivityForResult(
                Intent.createChooser(intent, getString(R.string.chooser_ringtone)),
                Def.Communication.REQUEST_CHOOSE_AUDIO_FILE);
    }

    private void initAutoNotifyFragment() {
        mCdfAN = new ChooserDialogFragment();
        mCdfAN.setAccentColor(mAccentColor);
        mCdfAN.setShouldShowMore(false);
        mCdfAN.setTitle(getString(R.string.auto_notify_set_time));
        mCdfAN.setItems(sANItems);
        mCdfAN.setInitialIndex(mANPicked);
        mCdfAN.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int index = mCdfAN.getPickedIndex();
                mCdfAN.setInitialIndex(index);
                mTvAN.setText(sANItems.get(index));
                mANPicked = index;
                updateUiAutoNotifyRingtone(index != 0);
            }
        });
    }

    private void storeConfiguration() {
        SharedPreferences.Editor editor = mPreferences.edit();

        String headerBefore = mPreferences.getString(
                Def.Meta.KEY_DRAWER_HEADER, DEFAULT_DRAWER_HEADER);
        String header = DEFAULT_DRAWER_HEADER;
        String str = mTvDrawerHeader.getText().toString();
        if (!str.equals(getString(R.string.default_drawer_header))) {
            header = str;
        }
        editor.putString(Def.Meta.KEY_DRAWER_HEADER, header);
        if (!headerBefore.equals(header)) {
            setResult(Def.Communication.RESULT_UPDATE_DRAWER_HEADER_DONE);
        }

        boolean toggleCliOtc = mCbToggleCli.isChecked();
        editor.putBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc);
        if (toggleCliOtc != mToggleCliOtc) {
            // set or unset Checklist items listeners for ThingsAdapter in ThingsActivity
            App.setShouldJustNotifyDataSetChanged(true);
        }

        editor.putBoolean(Def.Meta.KEY_TWICE_BACK, mCbTwiceBack.isChecked());

        for (int i = 0; i < mChosenRingtoneUris.length; i++) {
            editor.putString(sKeysRingtone[i], mChosenRingtoneUris[i].toString());
        }

        boolean isChecked = mCbFgprt.isChecked();
        if (isChecked) {
            FingerprintHelper.getInstance().createFingerprintKeyForEverythingDone();
        }
        editor.putBoolean(Def.Meta.KEY_USE_FINGERPRINT, isChecked);

        editor.putBoolean(Def.Meta.KEY_QUICK_CREATE, mCbQuickCreate.isChecked());

        editor.putInt(Def.Meta.KEY_AUTO_NOTIFY, mANPicked);
        editor.commit();
    }

    @Override
    public void finish() {
        mRingtoneManager.stopPreviousRingtone();
        storeConfiguration();
        super.finish();
    }

    private AlertDialogFragment createAlertDialog(
            boolean showCancel, @StringRes int titleRes, @StringRes int contentRes) {
        final AlertDialogFragment adf = new AlertDialogFragment();
        adf.setShowCancel(showCancel);
        adf.setTitleColor(mAccentColor);
        adf.setConfirmColor(mAccentColor);
        adf.setTitle(getString(titleRes));
        adf.setContent(getString(contentRes));
        return adf;
    }

    private LoadingDialogFragment createLoadingDialog(@StringRes int titleRes, @StringRes int contentRes) {
        final LoadingDialogFragment ldf = new LoadingDialogFragment();
        ldf.setAccentColor(mAccentColor);
        ldf.setTitle(getString(titleRes));
        ldf.setContent(getString(contentRes));
        return ldf;
    }

    class BackupTask extends AsyncTask<Object, Object, Boolean> {

        @Override
        protected Boolean doInBackground(Object... params) {
            return BackupHelper.backup(SettingsActivity.this);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            mLdfBackup.dismiss();
            int titleRes, contentRes;
            if (success) {
                titleRes = R.string.backup_success_title;
                contentRes = R.string.backup_success_content;
                updateUiRestore();
            } else {
                titleRes = R.string.backup_failed_title;
                contentRes = R.string.backup_failed_content;
            }
            final AlertDialogFragment adf = createAlertDialog(false, titleRes, contentRes);
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);
        }
    }

    class RestoreTask extends AsyncTask<Object, Object, String> {

        @Override
        protected String doInBackground(Object... params) {
            List<Long> thingIds = new ArrayList<>();
            List<Long> reminderIds = new ArrayList<>();
            List<Long> habitReminderIds = new ArrayList<>();
            Context context = getApplicationContext();
            ThingDAO thingDAO = ThingDAO.getInstance(context);
            Cursor cursor = thingDAO.getAllThingsCursor();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(
                        cursor.getColumnIndex(Def.Database.COLUMN_ID_THINGS));
                @Thing.Type int type = cursor.getInt(
                        cursor.getColumnIndex(Def.Database.COLUMN_TYPE_THINGS));
                int state = cursor.getInt(
                        cursor.getColumnIndex(Def.Database.COLUMN_STATE_THINGS));
                if (state != Thing.UNDERWAY) continue;
                thingIds.add(id);
                if (type == Thing.REMINDER || type == Thing.HABIT || type == Thing.GOAL) {
                    if (Thing.isReminderType(type)) {
                        reminderIds.add(id);
                    } else {
                        List<HabitReminder> habitReminders = HabitDAO.getInstance(context)
                                .getHabitRemindersByHabitId(id);
                        for (HabitReminder habitReminder : habitReminders) {
                            habitReminderIds.add(habitReminder.getId());
                        }
                    }
                }
            }
            cursor.close();

            String result = BackupHelper.restore(context);
            if (BackupHelper.SUCCESS.equals(result)) {
                AlarmHelper.cancelAlarms(context, thingIds, reminderIds, habitReminderIds);
                try {
                    FileOutputStream fos = SettingsActivity.this.openFileOutput(
                            Def.Meta.RESTORE_DONE_FILE_NAME, MODE_PRIVATE);
                    fos.write(getString(R.string.qq_my_love).getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                    result = getString(R.string.restore_failed_other);
                }
            }
            return result;
        }

        @Override
        protected void onPostExecute(String s) {
            mLdfRestore.dismiss();
            String title, content;
            if (BackupHelper.SUCCESS.equals(s)) {
                title = getString(R.string.restore_success_title);
                content = getString(R.string.restore_success_content);
            } else {
                title = getString(R.string.restore_failed_title);
                content = s;
            }
            final AlertDialogFragment adf = new AlertDialogFragment();
            adf.setShowCancel(false);
            adf.setTitleColor(mAccentColor);
            adf.setConfirmColor(mAccentColor);
            adf.setTitle(title);
            adf.setContent(content);
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);

            if (BackupHelper.SUCCESS.equals(s)) {
                if (App.getDoingThingId() != -1) {
                    Toast.makeText(SettingsActivity.this, R.string.doing_failed_restore,
                            Toast.LENGTH_LONG).show();
                    stopService(new Intent(SettingsActivity.this, DoingService.class));
                }
                App.killMeAndRestart(SettingsActivity.this, null, 1200);
            }
        }
    }
}
