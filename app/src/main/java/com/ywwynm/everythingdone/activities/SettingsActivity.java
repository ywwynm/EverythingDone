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
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
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
import com.ywwynm.everythingdone.helpers.DailyTodoHelper;
import com.ywwynm.everythingdone.helpers.FingerprintHelper;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.model.DoingRecord;
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
import com.ywwynm.everythingdone.utils.StringUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;
import com.ywwynm.everythingdone.utils.UriPathConverter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.ywwynm.everythingdone.Def.Communication.REQUEST_CHOOSE_AUDIO_FILE;
import static com.ywwynm.everythingdone.Def.Communication.REQUEST_CHOOSE_BACKUP_FILE;
import static com.ywwynm.everythingdone.Def.Communication.REQUEST_CHOOSE_IMAGE_FILE;

@SuppressLint("CommitPrefEdits")
public class SettingsActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "SettingsActivity";

    private SharedPreferences mPreferences;

    private int mAccentColor;

    // group ui
    public static final String DEFAULT_DRAWER_HEADER = "default_drawer_header";
    private TextView mTvDrawerHeader;

    private TextView mTvLanguage;

    private CheckBox mCbNn; // noticeable notification

    private CheckBox mCbToggleCli; // toggle checklist item
    private boolean  mToggleCliOtc;

    private CheckBox mCbSimpleFCli; // simple finished checklist item
    private boolean  mSimpleFCli;

    private CheckBox mCbAutoLink;

    private CheckBox mCbTwiceBack;

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
    private TextView mTvASE; // auto save edits
    private boolean mAutoSaveEdits;

    private TextView mTvRestoreLastInfo;
    private LoadingDialogFragment mLdfBackup;
    private LoadingDialogFragment mLdfRestore;

    // group privacy
    private RelativeLayout mRlFgprtAsBt;
    private CheckBox       mCbFgprt;

    // group start doing
    private TextView mTvASD; // auto start doing
    private int      mASDPicked;

    private LinearLayout[] mLlASDTimes;
    private TextView[]     mTvASDTimeTitles;
    private TextView[]     mTvASDTimes;
    private int[]          mASDTimesPicked;

    private TextView mTvASM; // auto strict mode
    private int      mASMPicked;

    // group advanced
    private CheckBox mCbQuickCreate;
    private CheckBox mCbCloseNotificationLater;
    private CheckBox mCbOngoingLockscreen;

    private static List<String> sDTItems; // daily to-do
    private int                 mDTPicked;
    private TextView            mTvDT;

    private static List<String>   sANItems;
    private int                   mANPicked;
    private TextView              mTvAN;
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

                Uri defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (defaultUri != null) {
                    Ringtone dr = RingtoneManager.getRingtone(context, defaultUri);
                    if (dr != null) {
                        sRingtoneUriList.add(defaultUri);
                        sRingtoneTitleList.add(StringUtil.replaceChineseBrackets(dr.getTitle(context)));
                    }
                }

                SharedPreferences preferences = context.getSharedPreferences(
                        Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
                for (String key : sKeysRingtone) {
                    String uriStr = preferences.getString(key, FOLLOW_SYSTEM);
                    if (FOLLOW_SYSTEM.equals(uriStr)) continue;

                    Uri uri = Uri.parse(uriStr);
                    if (manager.getRingtonePosition(uri) != -1) continue;

                    String pathName = UriPathConverter.getLocalPathName(context, uri);
                    if (pathName == null) {
                        preferences.edit().putString(key, FOLLOW_SYSTEM).apply();
                        continue;
                    }

                    File file = new File(pathName);
                    if (!file.exists()) {
                        preferences.edit().putString(key, FOLLOW_SYSTEM).apply();
                    } else if (!sRingtoneUriList.contains(uri)) {
                        sRingtoneUriList.add(uri);
                        sRingtoneTitleList.add(getRingtoneTitle(context, manager, uri));
                    }
                }

                Cursor cursor = manager.getCursor();
                int count = cursor.getCount();
                for (int i = 0; i < count; i++) {
                    sRingtoneUriList.add(manager.getRingtoneUri(i));
                    sRingtoneTitleList.add(StringUtil.replaceChineseBrackets(
                            manager.getRingtone(i).getTitle(context)));
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
            return StringUtil.replaceChineseBrackets(FileUtil.getNameWithoutPostfix(pathName));
        } else {
            Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
            return StringUtil.replaceChineseBrackets(ringtone.getTitle(context));
        }
    }

    private static boolean isFileRingtone(RingtoneManager ringtoneManager, Uri uri) {
        return !uri.equals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                && ringtoneManager.getRingtonePosition(uri) == -1;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Uri uri = data.getData();
            String pathName = UriPathConverter.getLocalPathName(this, uri);
            View root = f(R.id.rl_settings_root);
            if (pathName == null) {
                Snackbar.make(root, R.string.error_cannot_add_from_network, // TODO: 2017/3/28 not attachment
                        Snackbar.LENGTH_SHORT).show();
                return;
            }

            String postfix = FileUtil.getPostfix(pathName);
            if (!isSupportedFilePostfix(requestCode, postfix)) {
                Snackbar.make(root, R.string.error_unsupported_file_type,
                        Snackbar.LENGTH_SHORT).show();
                return;
            }

            if (requestCode == REQUEST_CHOOSE_IMAGE_FILE) {
                mTvDrawerHeader.setTextSize(12);
                mTvDrawerHeader.setText(pathName);
            } else if (requestCode == REQUEST_CHOOSE_AUDIO_FILE) {
                setFileRingtone(pathName);
            } else if (requestCode == REQUEST_CHOOSE_BACKUP_FILE) {
                startToRestore(pathName);
            }
        }
    }

    private boolean isSupportedFilePostfix(int requestCode, String postfix) {
        if (requestCode == REQUEST_CHOOSE_IMAGE_FILE) {
            return AttachmentHelper.isImageFile(postfix);
        } else if (requestCode == REQUEST_CHOOSE_AUDIO_FILE) {
            return AttachmentHelper.isAudioFile(postfix);
        } else if (requestCode == REQUEST_CHOOSE_BACKUP_FILE) {
            return BackupHelper.isSupportedBackupFilePostfix(postfix);
        }
        return false;
    }

    private void setFileRingtone(String pathName) {
        String audioName = FileUtil.getNameWithoutPostfix(pathName);
        File srcFile = new File(pathName);
        Uri uri;
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
        f(R.id.rl_settings_root).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ringtone.isPlaying()) {
                    ringtone.stop();
                }
            }
        }, 6000);
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

    @Override
    protected void initMembers() {
        initStaticVariables();

        mPreferences = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
        mAccentColor = ContextCompat.getColor(this, R.color.blue_deep);

        initMembersRingtone();

        mASDTimesPicked = new int[2];
    }

    private void initStaticVariables() {
        if (sANItems == null) {
            sANItems = new ArrayList<>();
            sANItems.add(getString(R.string.disable));
            for (int i = 0; i < AutoNotifyHelper.AUTO_NOTIFY_TIMES.length; i++) {
                sANItems.add(DateTimeUtil.getDateTimeStr(
                        AutoNotifyHelper.AUTO_NOTIFY_TYPES[i],
                        AutoNotifyHelper.AUTO_NOTIFY_TIMES[i], this));
            }
        }

        if (sDTItems == null) {
            sDTItems = DailyTodoHelper.getDailyTodoItems(this);
        }
    }

    private void initMembersRingtone() {
        mRingtoneManager = new RingtoneManager(this);
        mRingtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION);

        mChosenRingtoneUris   = new Uri[4];
        mChosenRingtoneTitles = new String[4];
        mCdfsRingtone         = new ChooserDialogFragment[4];

        Uri defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (defaultUri == null) defaultUri = sRingtoneUriList.get(0);
        for (int i = 0; i < mChosenRingtoneUris.length; i++) {
            String value = mPreferences.getString(sKeysRingtone[i], FOLLOW_SYSTEM);
            if (FOLLOW_SYSTEM.equals(value)) {
                mChosenRingtoneUris[i] = defaultUri;
            } else {
                Uri uri = Uri.parse(value);
                mChosenRingtoneUris[i] = uri;
                if (isFileRingtone(mRingtoneManager, uri)) {
                    String pathName = UriPathConverter.getLocalPathName(this, uri);
                    if (pathName == null || !new File(pathName).exists()) {
                        mChosenRingtoneUris[i] = defaultUri;
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
        mTvDrawerHeader = f(R.id.tv_drawer_header_path);

        mTvLanguage = f(R.id.tv_app_language);

        mCbNn = f(R.id.cb_noticeable_notification);

        mCbToggleCli  = f(R.id.cb_toggle_checklist);
        mCbSimpleFCli = f(R.id.cb_simple_finished_checklist);

        mCbAutoLink = f(R.id.cb_auto_link);

        mCbTwiceBack = f(R.id.cb_twice_back);

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
        mTvASE             = f(R.id.tv_auto_save_edits);
        mTvRestoreLastInfo = f(R.id.tv_restore_last_info);

        // privacy
        mRlFgprtAsBt = f(R.id.rl_use_fingerprint_as_bt);
        mCbFgprt     = f(R.id.cb_use_fingerprint);

        // start doing
        mTvASD = f(R.id.tv_auto_start_doing);

        mLlASDTimes    = new LinearLayout[2];
        mLlASDTimes[0] = f(R.id.ll_asd_time_reminder_as_bt);
        mLlASDTimes[1] = f(R.id.ll_asd_time_habit_as_bt);

        mTvASDTimeTitles = new TextView[2];
        mTvASDTimeTitles[0] = f(R.id.tv_asd_time_reminder_title);
        mTvASDTimeTitles[1] = f(R.id.tv_asd_time_habit_title);

        mTvASDTimes    = new TextView[2];
        mTvASDTimes[0] = f(R.id.tv_asd_time_reminder);
        mTvASDTimes[1] = f(R.id.tv_asd_time_habit);

        mTvASM = f(R.id.tv_auto_strict_mode);

        // advanced
        mCbQuickCreate = f(R.id.cb_quick_create);
        mCbCloseNotificationLater = f(R.id.cb_close_notification_later);
        mCbOngoingLockscreen = f(R.id.cb_ongoing_lockscreen);

        mTvDT = f(R.id.tv_daily_todo);
        mTvAN = f(R.id.tv_advanced_auto_notify_time);

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
        initUiData();
        initUiStartDoing();
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

        String languageCode = FrequentSettings.getString(
                Def.Meta.KEY_LANGUAGE_CODE, LocaleUtil.LANGUAGE_CODE_FOLLOW_SYSTEM + "_");
        mTvLanguage.setText(LocaleUtil.getLanguageDescription(languageCode));

        boolean nn = mPreferences.getBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, true);
        mCbNn.setChecked(nn);

        mToggleCliOtc = mPreferences.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, false);
        mCbToggleCli.setChecked(mToggleCliOtc);

        mSimpleFCli = mPreferences.getBoolean(Def.Meta.KEY_SIMPLE_FCLI, false);
        mCbSimpleFCli.setChecked(mSimpleFCli);

        boolean autoLink = mPreferences.getBoolean(Def.Meta.KEY_AUTO_LINK, true);
        mCbAutoLink.setChecked(autoLink);

        boolean twiceBack = mPreferences.getBoolean(Def.Meta.KEY_TWICE_BACK, false);
        mCbTwiceBack.setChecked(twiceBack);
    }

    private void initUiRingtone() {
        for (int i = 0; i < 3; i++) {
            mTvsRingtone[i].setText(mChosenRingtoneTitles[i]);
        }
    }

    private void initUiData() {
        mAutoSaveEdits = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS);
        mTvASE.setText(mAutoSaveEdits ? R.string.enabled : R.string.disabled);

        updateUiRestore();
    }

    private void updateUiRestore() {
        mTvRestoreLastInfo.setText(BackupHelper.getLastBackupTimeString());
    }

    private void initUiPrivacy() {
        String password = mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        TextView tv = f(R.id.tv_set_password_title);
        if (password == null) {
            tv.setText(R.string.set_app_password);
        } else {
            tv.setText(R.string.change_app_password);
        }

        initUiFingerprint();
    }

    private void initUiFingerprint() {
        TextView tvTitle  = f(R.id.tv_use_fingerprint_title);
        TextView tvDscrpt = f(R.id.tv_use_fingerprint_description);

        if (!DeviceUtil.hasMarshmallowApi()) {
            mRlFgprtAsBt.setEnabled(false);
            mCbFgprt.setEnabled(false);
            mCbFgprt.setChecked(false);
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.black_14p));
            tvDscrpt.setTextColor(ContextCompat.getColor(this, R.color.black_10p));
            tvDscrpt.setText(R.string.not_support_fgprt);
            return;
        }

        FingerprintHelper fph = FingerprintHelper.getInstance();
        String password = mPreferences.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        if (password == null || !fph.isFingerprintReady()) {
            mRlFgprtAsBt.setEnabled(false);
            mCbFgprt.setEnabled(false);
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.black_14p));
            tvDscrpt.setTextColor(ContextCompat.getColor(this, R.color.black_10p));

            if (password == null) {
                tvDscrpt.setText(R.string.password_not_set);
            } else {
                tvDscrpt.setTextSize(LocaleUtil.isChinese(this) ? 14 : 12);
                if (!fph.supportFingerprint()) {
                    tvDscrpt.setText(R.string.not_support_fgprt);
                } else if (!fph.hasSystemFingerprintSet()) {
                    tvDscrpt.setText(R.string.system_fgprt_not_set);
                } else if (!fph.hasFingerprintRegistered()) {
                    tvDscrpt.setText(R.string.fgprt_not_enrolled);
                }
            }
        } else {
            mRlFgprtAsBt.setEnabled(true);
            mCbFgprt.setEnabled(true);
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.black_54p));
            tvDscrpt.setTextColor(ContextCompat.getColor(this, R.color.black_26p));
            tvDscrpt.setText(R.string.use_fingerprint_to_verify);
        }

        boolean useFingerprint = mPreferences.getBoolean(Def.Meta.KEY_USE_FINGERPRINT, false);
        mCbFgprt.setChecked(useFingerprint);
    }

    private void initUiStartDoing() {
        initStartDoingTitle();

        mASDPicked = mPreferences.getInt(Def.Meta.KEY_AUTO_START_DOING, 0);
        String[] options = getResources().getStringArray(R.array.auto_start_doing_states);
        mTvASD.setText(options[mASDPicked]);

        enableOrDisableASDTimesUi();

        String[] pickedStr = new String[2];
        pickedStr[0] = mPreferences.getString(Def.Meta.KEY_ASD_TIME_REMINDER,
                ThingDoingHelper.START_DOING_TIME_FOLLOW_GENERAL_PICKED);
        pickedStr[1] = mPreferences.getString(Def.Meta.KEY_ASD_TIME_HABIT,
                ThingDoingHelper.START_DOING_TIME_FOLLOW_GENERAL_PICKED);
        mASDTimesPicked[0] = ThingDoingHelper.getStartDoingTimeIndex(pickedStr[0], false);
        mASDTimesPicked[1] = ThingDoingHelper.getStartDoingTimeIndex(pickedStr[1], false);

        List<String> items = ThingDoingHelper.getStartDoingTimeItems(this);
        mTvASDTimes[0].setText(items.get(mASDTimesPicked[0]));
        mTvASDTimes[1].setText(items.get(mASDTimesPicked[1]));

        mASMPicked = mPreferences.getInt(Def.Meta.KEY_AUTO_STRICT_MODE, 0);
        mTvASM.setText(options[mASMPicked]);
    }

    private void enableOrDisableASDTimesUi() {
        int black_54p = ContextCompat.getColor(this, R.color.black_54p);
        int black_26p = ContextCompat.getColor(this, R.color.black_26p);
        int black_14p = ContextCompat.getColor(this, R.color.black_14p);
        int black_10p = ContextCompat.getColor(this, R.color.black_10p);

        boolean[] enabled = { mASDPicked % 2 != 0, mASDPicked >= 2 };
        for (int i = 0; i < enabled.length; i++) {
            if (enabled[i]) {
                mLlASDTimes[i].setEnabled(true);
                mTvASDTimeTitles[i].setTextColor(black_54p);
                mTvASDTimes[i].setTextColor(black_26p);
            } else {
                mLlASDTimes[i].setEnabled(false);
                mTvASDTimeTitles[i].setTextColor(black_14p);
                mTvASDTimes[i].setTextColor(black_10p);
            }
        }
    }

    private void initStartDoingTitle() {
        TextView tvTitle = f(R.id.tv_title_group_start_doing_settings);
        Drawable d1 = ContextCompat.getDrawable(this, R.drawable.act_start_doing);
        Drawable d2 = d1.mutate();
        d2.setColorFilter(mAccentColor, PorterDuff.Mode.SRC_ATOP);
        if (DeviceUtil.hasJellyBeanMR1Api()) {
            tvTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(d2, null, null, null);
        } else {
            tvTitle.setCompoundDrawablesWithIntrinsicBounds(d2, null, null, null);
        }
    }

    private void initUiAdvanced() {
        // quick create
        boolean qc = mPreferences.getBoolean(Def.Meta.KEY_QUICK_CREATE, true);
        mCbQuickCreate.setChecked(qc);

        boolean closeLater = mPreferences.getBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, false);
        mCbCloseNotificationLater.setChecked(closeLater);

        boolean ongoingLockscreen = FrequentSettings.getBoolean(Def.Meta.KEY_ONGOING_LOCKSCREEN);
        mCbOngoingLockscreen.setChecked(ongoingLockscreen);

        // daily todo
        mDTPicked = mPreferences.getInt(Def.Meta.KEY_DAILY_TODO, 0);
        updateUiDailyTodo();

        // auto notify
        mANPicked = mPreferences.getInt(Def.Meta.KEY_AUTO_NOTIFY, 0);
        updateUiAutoNotifyRingtone();
    }

    private void updateUiDailyTodo() {
        if (mDTPicked == 0) {
            mTvDT.setText(R.string.disabled);
        } else {
            mTvDT.setText(sDTItems.get(mDTPicked));
        }
    }

    private void updateUiAutoNotifyRingtone() {
        if (mANPicked == 0) {
            mTvAN.setText(R.string.disabled);
            mLlANRingtoneAsBt.setEnabled(false);
            mTvANRingtoneTitle.setTextColor(ContextCompat.getColor(this, R.color.black_14p));
            mTvANRingtone.setText("");
        } else {
            mTvAN.setText(sANItems.get(mANPicked - 1));
            mLlANRingtoneAsBt.setEnabled(true);
            mTvANRingtoneTitle.setTextColor(ContextCompat.getColor(this, R.color.black_54p));
            mTvANRingtone.setText(mChosenRingtoneTitles[mChosenRingtoneTitles.length - 1]);
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
        setStartDoingEvents();
        setAdvancedEvents();
    }

    private void setUiEvents() {
        f(R.id.ll_change_drawer_header_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangeDrawerHeaderDialog();
            }
        });

        f(R.id.ll_app_language_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChooseLanguageDialog();
            }
        });

        f(R.id.rl_noticeable_notification_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCbNn.setChecked(!mCbNn.isChecked());
            }
        });

        f(R.id.rl_toggle_checklist_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbToggleCli.setChecked(!mCbToggleCli.isChecked());
            }
        });

        f(R.id.rl_simple_finished_checklist_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbSimpleFCli.setChecked(!mCbSimpleFCli.isChecked());
            }
        });

        f(R.id.rl_auto_link_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCbAutoLink.setChecked(!mCbAutoLink.isChecked());
            }
        });

        f(R.id.rl_twice_back_as_bt).setOnClickListener(new View.OnClickListener() {
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
        f(R.id.rl_auto_save_edits_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final ChooserDialogFragment cdf = new ChooserDialogFragment();
                cdf.setAccentColor(mAccentColor);
                cdf.setTitle(getString(R.string.auto_save_edits_title));
                List<String> items = Arrays.asList(getString(R.string.enable), getString(R.string.disable));
                cdf.setItems(items);
                cdf.setShouldShowMore(false);
                cdf.setInitialIndex(mAutoSaveEdits ? 0 : 1);
                cdf.setConfirmListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int picked = cdf.getPickedIndex();
                        mAutoSaveEdits = picked == 0;
                        mTvASE.setText(mAutoSaveEdits ? R.string.enabled : R.string.disabled);
                    }
                });
                cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
            }
        });
        f(R.id.iv_auto_save_edits_help_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createAlertDialog(false,
                        R.string.auto_save_edits_title, R.string.auto_save_edits_help_info,
                        R.string.act_get_it)
                        .show(getFragmentManager(), AlertDialogFragment.TAG);
            }
        });

        f(R.id.tv_backup_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBackupDialog();
            }
        });
        f(R.id.ll_restore_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRestoreDialog();
            }
        });
    }

    private void setPrivacyEvents() {
        f(R.id.ll_set_password_as_bt).setOnClickListener(new View.OnClickListener() {
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

    private void setStartDoingEvents() {
        f(R.id.ll_auto_start_doing_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAutoStartDoingDialog();
            }
        });

        for (int i = 0; i < mLlASDTimes.length; i++) {
            final int index = i;
            mLlASDTimes[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showAutoStartDoingTimeDialog(index);
                }
            });
        }

        f(R.id.rl_auto_strict_mode_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAutoStrictModeDialog();
            }
        });
        f(R.id.iv_auto_strict_mode_help_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final AlertDialogFragment adf = createAlertDialog(
                        false, R.string.doing_alert_first_strict_mode_title,
                        R.string.auto_strict_mode_help_content,
                        R.string.act_get_it);
                adf.show(getFragmentManager(), AlertDialogFragment.TAG);
            }
        });
    }

    private void showAutoStartDoingTimeDialog(final int index) {
        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mAccentColor);
        cdf.setShouldShowMore(false);
        @StringRes int titleRes = index == 0 ? R.string.auto_start_doing_time_reminder_title
                : R.string.auto_start_doing_time_habit_title;
        cdf.setTitle(getString(titleRes));
        cdf.setItems(ThingDoingHelper.getStartDoingTimeItems(this));
        cdf.setInitialIndex(mASDTimesPicked[index]);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int picked = cdf.getPickedIndex();
                mASDTimesPicked[index] = picked;
                List<String> items = ThingDoingHelper.getStartDoingTimeItems(getApplicationContext());
                mTvASDTimes[index].setText(items.get(picked));
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void showAutoStartDoingDialog() {
        final ChooserDialogFragment cdf = createChooserDialogForStartDoing();
        cdf.setTitle(getString(R.string.auto_start_doing_title));
        cdf.setInitialIndex(mASDPicked);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mASDPicked = cdf.getPickedIndex();
                final String[] states = getResources().getStringArray(R.array.auto_start_doing_states);
                mTvASD.setText(states[mASDPicked]);
                enableOrDisableASDTimesUi();
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void showAutoStrictModeDialog() {
        final ChooserDialogFragment cdf = createChooserDialogForStartDoing();
        cdf.setTitle(getString(R.string.auto_strict_mode_title));
        cdf.setInitialIndex(mASMPicked);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String[] states = getResources().getStringArray(R.array.auto_start_doing_states);
                mASMPicked = cdf.getPickedIndex();
                mTvASM.setText(states[mASMPicked]);
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private ChooserDialogFragment createChooserDialogForStartDoing() {
        ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mAccentColor);
        cdf.setShouldShowMore(false);
        String[] options = getResources().getStringArray(R.array.auto_start_doing_options);
        cdf.setItems(Arrays.asList(options));
        return cdf;
    }

    private void setAdvancedEvents() {
        setQuickCreateEvents();

        f(R.id.rl_close_notification_later_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbCloseNotificationLater.setChecked(!mCbCloseNotificationLater.isChecked());
            }
        });
        f(R.id.rl_ongoing_lockscreen_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ongoingLockscreen = !mCbOngoingLockscreen.isChecked();
                mCbOngoingLockscreen.setChecked(ongoingLockscreen);
                FrequentSettings.put(Def.Meta.KEY_ONGOING_LOCKSCREEN, ongoingLockscreen);
                SystemNotificationUtil.tryToCreateThingOngoingNotification(App.getApp());
            }
        });

        f(R.id.ll_daily_todo_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDailyTodoFragment();
            }
        });
        f(R.id.iv_daily_todo_help_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final AlertDialogFragment adf = createAlertDialog(
                        false, R.string.create_daily_todo_automatically, R.string.create_daily_todo_help_info,
                        R.string.act_get_it);
                adf.show(getFragmentManager(), AlertDialogFragment.TAG);
            }
        });

        f(R.id.ll_advanced_auto_notify_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCdfAN == null) {
                    initAutoNotifyFragment();
                }
                mCdfAN.show(getFragmentManager(), ChooserDialogFragment.TAG);
            }
        });
        f(R.id.iv_auto_notify_help_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final AlertDialogFragment adf = createAlertDialog(
                        false, R.string.auto_notify, R.string.auto_notify_help_info, R.string.act_get_it);
                adf.show(getFragmentManager(), AlertDialogFragment.TAG);
            }
        });
    }

    private void showDailyTodoFragment() {
        final ChooserDialogFragment cdf = new ChooserDialogFragment();
        cdf.setAccentColor(mAccentColor);
        cdf.setShouldShowMore(false);
        cdf.setTitle(getString(R.string.auto_notify_set_time));
        cdf.setItems(sDTItems);
        cdf.setInitialIndex(mDTPicked);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDTPicked = cdf.getPickedIndex();
                updateUiDailyTodo();
            }
        });
        cdf.show(getFragmentManager(), ChooserDialogFragment.TAG);
    }

    private void setQuickCreateEvents() {
        f(R.id.rl_quick_create_as_bt).setOnClickListener(new View.OnClickListener() {
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
            @SuppressLint("ApplySharedPref")
            @Override
            public void onClick(View v) {
                int pickedIndex = cdf.getPickedIndex();
                if (pickedIndex == initialIndex) {
                    return;
                }
                Context context = SettingsActivity.this;
                String newLanguageCode = resources.getStringArray(R.array.language_codes)[pickedIndex];
                FrequentSettings.put(Def.Meta.KEY_LANGUAGE_CODE, newLanguageCode);
                mPreferences.edit().putString(Def.Meta.KEY_LANGUAGE_CODE, newLanguageCode).commit();
                if (App.getDoingThingId() != -1) {
                    Toast.makeText(context, R.string.doing_failed_change_language,
                            Toast.LENGTH_LONG).show();
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_OTHER;
                    stopService(new Intent(context, DoingService.class));
                }
                App.killMeAndRestart(context, null, 0);

                Intent intent = new Intent(context, LocaleChangeReceiver.class);
                intent.setAction(Def.Communication.BROADCAST_ACTION_RESP_LOCALE_CHANGE);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                        0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1600, pendingIntent);
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
        adf.setConfirmText(getString(R.string.restore_choose_backup_file));
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
        AuthenticationHelper.authenticate(this, mAccentColor, getString(R.string.restore_choose_backup_file), password,
                new AuthenticationHelper.AuthenticationCallback() {
                    @Override
                    public void onAuthenticated() {
                        doWithPermissionChecked(
                                new SimplePermissionCallback(SettingsActivity.this) {
                                    @Override
                                    public void onGranted() {
                                        startChooseBackupFile();
                                    }
                                },
                                Def.Communication.REQUEST_PERMISSION_RESTORE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }

                    @Override
                    public void onCancel() { }
                });
    }

    private void startChooseBackupFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(
                Intent.createChooser(intent, getString(R.string.restore_choose_backup_file)),
                Def.Communication.REQUEST_CHOOSE_BACKUP_FILE);
    }

    private void startToRestore(String pathName) {
        showRestoreLoadingDialog();
        new RestoreTask().execute(pathName);
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
                mANPicked = mCdfAN.getPickedIndex();
                updateUiAutoNotifyRingtone();
            }
        });
    }

    private void storeConfiguration() {
        SharedPreferences.Editor editor = mPreferences.edit();

        // ui
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

        editor.putBoolean(Def.Meta.KEY_NOTICEABLE_NOTIFICATION, mCbNn.isChecked());

        boolean toggleCliOtc = mCbToggleCli.isChecked();
        editor.putBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc);
        FrequentSettings.put(Def.Meta.KEY_TOGGLE_CLI_OTC, toggleCliOtc);
        if (toggleCliOtc != mToggleCliOtc) {
            // set or unset Checklist items listeners for ThingsAdapter in ThingsActivity
            App.setJustNotifyAll(true);
        }

        boolean simpleFCli = mCbSimpleFCli.isChecked();
        editor.putBoolean(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli);
        FrequentSettings.put(Def.Meta.KEY_SIMPLE_FCLI, simpleFCli);
        if (simpleFCli != mSimpleFCli) {
            App.setJustNotifyAll(true);
        }

        boolean autoLink = mCbAutoLink.isChecked();
        FrequentSettings.put(Def.Meta.KEY_AUTO_LINK, autoLink);
        editor.putBoolean(Def.Meta.KEY_AUTO_LINK, autoLink);

        boolean twiceBack = mCbTwiceBack.isChecked();
        FrequentSettings.put(Def.Meta.KEY_TWICE_BACK, twiceBack);
        editor.putBoolean(Def.Meta.KEY_TWICE_BACK, twiceBack);

        // ringtone
        for (int i = 0; i < mChosenRingtoneUris.length; i++) {
            editor.putString(sKeysRingtone[i], mChosenRingtoneUris[i].toString());
        }

        // data
        FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, mAutoSaveEdits);
        editor.putBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS, mAutoSaveEdits);

        // privacy
        boolean isChecked = mCbFgprt.isChecked();
        if (isChecked) {
            FingerprintHelper.getInstance().createFingerprintKeyForEverythingDone();
        }
        editor.putBoolean(Def.Meta.KEY_USE_FINGERPRINT, isChecked);

        // start doing
        editor.putInt(Def.Meta.KEY_AUTO_START_DOING, mASDPicked);
        editor.putString(Def.Meta.KEY_ASD_TIME_REMINDER,
                ThingDoingHelper.getStartDoingTimePickedStr(mASDTimesPicked[0], false));
        editor.putString(Def.Meta.KEY_ASD_TIME_HABIT,
                ThingDoingHelper.getStartDoingTimePickedStr(mASDTimesPicked[1], false));
        editor.putInt(Def.Meta.KEY_AUTO_STRICT_MODE, mASMPicked);

        // advanced
        editor.putBoolean(Def.Meta.KEY_QUICK_CREATE, mCbQuickCreate.isChecked());

        boolean closeLater = mCbCloseNotificationLater.isChecked();
        FrequentSettings.put(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, closeLater);
        editor.putBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, closeLater);

        boolean ongoingLockscreen = mCbOngoingLockscreen.isChecked();
        editor.putBoolean(Def.Meta.KEY_ONGOING_LOCKSCREEN, ongoingLockscreen);

        editor.putInt(Def.Meta.KEY_DAILY_TODO, mDTPicked);
        AlarmHelper.cancelDailyTodoAlarm(this);

        editor.putInt(Def.Meta.KEY_AUTO_NOTIFY, mANPicked);

        editor.apply();

        if (mDTPicked != 0) {
            AlarmHelper.tryToCreateDailyTodoAlarm(this);
        }
    }

    @Override
    public void finish() {
        mRingtoneManager.stopPreviousRingtone();
        storeConfiguration();
        super.finish();
    }

    private AlertDialogFragment createAlertDialog(
            boolean showCancel, @StringRes int titleRes, @StringRes int contentRes) {
        return createAlertDialog(showCancel, titleRes, contentRes, R.string.confirm);
    }

    private AlertDialogFragment createAlertDialog(
            boolean showCancel, @StringRes int titleRes, @StringRes int contentRes, @StringRes int confirmRes) {
        final AlertDialogFragment adf = new AlertDialogFragment();
        adf.setShowCancel(showCancel);
        adf.setTitleColor(mAccentColor);
        adf.setConfirmColor(mAccentColor);
        adf.setTitle(getString(titleRes));
        adf.setContent(getString(contentRes));
        adf.setConfirmText(getString(confirmRes));
        return adf;
    }

    private LoadingDialogFragment createLoadingDialog(@StringRes int titleRes, @StringRes int contentRes) {
        final LoadingDialogFragment ldf = new LoadingDialogFragment();
        ldf.setAccentColor(mAccentColor);
        ldf.setTitle(getString(titleRes));
        ldf.setContent(getString(contentRes));
        return ldf;
    }

    private class BackupTask extends AsyncTask<Object, Object, Boolean> {

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

    private class RestoreTask extends AsyncTask<Object, Object, Boolean> {

        @Override
        protected Boolean doInBackground(Object... params) {
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

            String backupFilePathName = (String) params[0];
            if (BackupHelper.restore(context, new File(backupFilePathName))) {
                AlarmHelper.cancelAlarms(context, thingIds, reminderIds, habitReminderIds);
                try {
                    FileOutputStream fos = SettingsActivity.this.openFileOutput(
                            Def.Meta.RESTORE_DONE_FILE_NAME, MODE_PRIVATE);
                    fos.write(getString(R.string.qq_my_love).getBytes());
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean restoreSuccessfully) {
            mLdfRestore.dismiss();
            String title, content;
            if (restoreSuccessfully) {
                title = getString(R.string.restore_success_title);
                content = getString(R.string.restore_success_content);
            } else {
                title = getString(R.string.restore_failed_title);
                content = getString(R.string.restore_failed_other);
            }
            final AlertDialogFragment adf = new AlertDialogFragment();
            adf.setShowCancel(false);
            adf.setTitleColor(mAccentColor);
            adf.setConfirmColor(mAccentColor);
            adf.setTitle(title);
            adf.setContent(content);
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);

            Context context = SettingsActivity.this;
            if (restoreSuccessfully) {
                if (App.getDoingThingId() != -1) {
                    Toast.makeText(context, R.string.doing_failed_restore,
                            Toast.LENGTH_LONG).show();
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_OTHER;
                    stopService(new Intent(context, DoingService.class));
                    App.setDoingThingId(-1L);
                    AppWidgetHelper.updateAllAppWidgets(context);
                }
                App.killMeAndRestart(context, null, 1200);
            }
        }
    }
}
