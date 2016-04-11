package com.ywwynm.everythingdone.activities;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment;
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.helpers.AutoNotifyHelper;
import com.ywwynm.everythingdone.helpers.BackupHelper;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.PermissionUtil;
import com.ywwynm.everythingdone.utils.VersionUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "SettingsActivity";

    private SharedPreferences mPreferences;

    private int mAccentColor;

    private View mStatusBar;
    private Toolbar mActionbar;

    private static String[] mKeysRingtone = {
            Definitions.MetaData.KEY_RINGTONE_REMINDER,
            Definitions.MetaData.KEY_RINGTONE_HABIT,
            Definitions.MetaData.KEY_RINGTONE_GOAL,
            Definitions.MetaData.KEY_RINGTONE_AUTO_NOTIFY
    };
    public static String FOLLOW_SYSTEM = "follow_system";
    private static List<String> sSystemRingtoneList;
    private RingtoneManager mRingtoneManager;
    private Uri[] mRingtoneUris;
    private LinearLayout[] mLlsRingtone;
    private TextView[] mTvsRingtone;
    private String[] mRingtoneTitles;
    private ChooserDialogFragment[] mCdfsRingtone;

    private TextView mTvBackupAsBt;
    private TextView mTvRestoreAsBt;
    private LoadingDialogFragment mLdgBackup;
    private LoadingDialogFragment mLdgRestore;

    private static List<String> sANItems;
    private int mANPicked;
    private LinearLayout mLlANAsBt;
    private TextView mTvAN;
    private ImageView mIvANAsBt;
    private ChooserDialogFragment mCdfAN;

    private LinearLayout mLlANRingtoneAsBt;
    private TextView mTvANRingtoneTitle;
    private TextView mTvANRingtone;

    public static void initSystemRingtoneList(final Context context) {
        sSystemRingtoneList = new ArrayList<>();
        new Thread() {
            @Override
            public void run() {
                RingtoneManager manager = new RingtoneManager(context);
                manager.setType(RingtoneManager.TYPE_NOTIFICATION);

                Ringtone dr = RingtoneManager.getRingtone(
                        context, Settings.System.DEFAULT_NOTIFICATION_URI);
                sSystemRingtoneList.add(dr.getTitle(context));
                int count = manager.getCursor().getCount();
                for (int i = 0; i < count; i++) {
                    sSystemRingtoneList.add(
                            manager.getRingtone(i).getTitle(context));
                }
            }
        }.start();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        final int G = PackageManager.PERMISSION_GRANTED;
        if (requestCode == Definitions.Communication.REQUEST_PERMISSION_BACKUP) {
            if (grantResults[0] == G) {
                showBackupLoadingDialog();
                new BackupTask().execute();
            } else {
                Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == Definitions.Communication.REQUEST_PERMISSION_RESTORE) {
            if (grantResults[0] == G) {
                showRestoreLoadingDialog();
                new RestoreTask().execute();
            } else {
                Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_settings;
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

        mPreferences = getSharedPreferences(Definitions.MetaData.PREFERENCES_NAME, MODE_PRIVATE);
        mAccentColor = ContextCompat.getColor(this, R.color.blue_deep);

        initMembersRingtone();
    }

    private void initMembersRingtone() {
        mRingtoneManager = new RingtoneManager(this);
        mRingtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION);

        mRingtoneUris   = new Uri[4];
        mRingtoneTitles = new String[4];
        mCdfsRingtone   = new ChooserDialogFragment[4];

        for (int i = 0; i < mRingtoneUris.length; i++) {
            String value = mPreferences.getString(mKeysRingtone[i], FOLLOW_SYSTEM);
            if (FOLLOW_SYSTEM.equals(value)) {
                mRingtoneUris[i] = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                mRingtoneUris[i] = Uri.parse(value);
            }

            if (mRingtoneManager.getRingtonePosition(mRingtoneUris[i]) == -1) {
                mRingtoneUris[i] = Settings.System.DEFAULT_NOTIFICATION_URI;
            }
            Ringtone ringtone = RingtoneManager.getRingtone(this, mRingtoneUris[i]);
            mRingtoneTitles[i] = ringtone.getTitle(this);
        }
    }

    @Override
    protected void findViews() {
        mStatusBar = findViewById(R.id.view_status_bar);
        mActionbar = (Toolbar) findViewById(R.id.actionbar);

        mLlsRingtone = new LinearLayout[4];
        mLlsRingtone[0] = (LinearLayout) findViewById(R.id.ll_ringtone_reminder_as_bt);
        mLlsRingtone[1] = (LinearLayout) findViewById(R.id.ll_ringtone_habit_as_bt);
        mLlsRingtone[2] = (LinearLayout) findViewById(R.id.ll_ringtone_goal_as_bt);

        mTvsRingtone = new TextView[4];
        mTvsRingtone[0] = (TextView) findViewById(R.id.tv_ringtone_reminder);
        mTvsRingtone[1] = (TextView) findViewById(R.id.tv_ringtone_habit);
        mTvsRingtone[2] = (TextView) findViewById(R.id.tv_ringtone_goal);

        mTvBackupAsBt  = (TextView) findViewById(R.id.tv_backup_as_bt);
        mTvRestoreAsBt = (TextView) findViewById(R.id.tv_restore_as_bt);

        mLlANAsBt = (LinearLayout) findViewById(R.id.ll_advanced_auto_notify_as_bt);
        mTvAN     = (TextView)     findViewById(R.id.tv_advanced_auto_notify_time);
        mIvANAsBt = (ImageView)    findViewById(R.id.iv_auto_notify_help_as_bt);

        mLlANRingtoneAsBt  = (LinearLayout) findViewById(R.id.ll_ringtone_auto_notify_as_bt);
        mTvANRingtoneTitle = (TextView)     findViewById(R.id.tv_ringtone_auto_notify_title);
        mTvANRingtone      = (TextView)     findViewById(R.id.tv_ringtone_auto_notify);

        mLlsRingtone[3] = mLlANRingtoneAsBt;
        mTvsRingtone[3] = mTvANRingtone;
    }

    @Override
    protected void initUI() {
        DisplayUtil.darkStatusBarForMIUI(this);

        if (VersionUtil.hasKitKatApi()) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                    mStatusBar.getLayoutParams();
            params.height = DisplayUtil.getStatusbarHeight(this);
            mStatusBar.requestLayout();
        }

        initUiRingtone();
        initUiAutoNotify();
    }

    private void initUiRingtone() {
        for (int i = 0; i < 3; i++) {
            mTvsRingtone[i].setText(mRingtoneTitles[i]);
        }
    }

    private void initUiAutoNotify() {
        int index = mPreferences.getInt(Definitions.MetaData.KEY_AUTO_NOTIFY, 0);
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
            mTvANRingtone.setText(mRingtoneTitles[mRingtoneTitles.length - 1]);
        } else {
            mLlANRingtoneAsBt.setEnabled(false);
            mTvANRingtoneTitle.setTextColor(ContextCompat.getColor(this, R.color.black_10p));
            mTvANRingtone.setText("");
        }
    }

    @Override
    protected void setActionbar() {
        setSupportActionBar(mActionbar);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        mActionbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void setEvents() {
        for (int i = 0; i < 4; i++) {
            final int j = i;
            mLlsRingtone[j].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mCdfsRingtone[j] == null) {
                        initRingtoneFragment(j);
                    }
                    mCdfsRingtone[j].show(getFragmentManager(), ChooserDialogFragment.TAG);
                }
            });
        }

        mTvBackupAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBackupDialog();
            }
        });
        mTvRestoreAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRestoreDialog();
            }
        });

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

    private void showBackupDialog() {
        final AlertDialogFragment adf = createAlertDialog(
                true, R.string.backup, R.string.backup_content);
        adf.setConfirmText(getString(R.string.backup_start));
        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                PermissionUtil.Callback callback = new PermissionUtil.Callback() {
                    @Override
                    public void onGranted() {
                        showBackupLoadingDialog();
                        new BackupTask().execute();
                    }
                };
                PermissionUtil.doWithPermissionChecked(callback, SettingsActivity.this,
                        Definitions.Communication.REQUEST_PERMISSION_BACKUP,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        });
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void showBackupLoadingDialog() {
        if (mLdgBackup == null) {
            mLdgBackup = createLoadingDialog(
                    R.string.backup_loading_title, R.string.backup_loading_content);
        }
        mLdgBackup.show(getFragmentManager(), LoadingDialogFragment.TAG);
    }

    private void showRestoreDialog() {
        final AlertDialogFragment adf = createAlertDialog(
                true, R.string.restore, R.string.restore_content);
        adf.setConfirmText(getString(R.string.restore_start));
        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                PermissionUtil.Callback callback = new PermissionUtil.Callback() {
                    @Override
                    public void onGranted() {
                        showRestoreLoadingDialog();
                        new RestoreTask().execute();
                    }
                };
                PermissionUtil.doWithPermissionChecked(callback, SettingsActivity.this,
                        Definitions.Communication.REQUEST_PERMISSION_RESTORE,
                        Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        });
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void showRestoreLoadingDialog() {
        if (mLdgRestore == null) {
            mLdgRestore = createLoadingDialog(
                    R.string.restore_loading_title, R.string.restore_loading_content);
        }
        mLdgRestore.show(getFragmentManager(), LoadingDialogFragment.TAG);
    }

    private void initRingtoneFragment(final int index) {
        mCdfsRingtone[index] = new ChooserDialogFragment();
        final ChooserDialogFragment cdf = mCdfsRingtone[index];
        cdf.setAccentColor(mAccentColor);
        cdf.setTitle(getString(R.string.chooser_ringtone));
        cdf.setItems(sSystemRingtoneList);
        cdf.setInitialIndex(mRingtoneManager.getRingtonePosition(mRingtoneUris[index]) + 1);
        cdf.setConfirmListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pickedIndex = cdf.getPickedIndex();
                mTvsRingtone[index].setText(sSystemRingtoneList.get(pickedIndex));
                cdf.setInitialIndex(pickedIndex);
                if (pickedIndex == 0) {
                    mRingtoneUris[index] = Settings.System.DEFAULT_NOTIFICATION_URI;
                } else {
                    mRingtoneUris[index] = mRingtoneManager.getRingtoneUri(pickedIndex - 1);
                }
                mRingtoneTitles[index] = RingtoneManager
                        .getRingtone(SettingsActivity.this, mRingtoneUris[index])
                        .getTitle(SettingsActivity.this);
            }
        });
        cdf.setOnItemClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pickedIndex = cdf.getPickedIndex();
                if (pickedIndex == 0) {
                    Ringtone ringtone = RingtoneManager.getRingtone(
                            SettingsActivity.this, Settings.System.DEFAULT_NOTIFICATION_URI);
                    ringtone.play();
                } else {
                    mRingtoneManager.getRingtone(pickedIndex - 1).play();
                }
            }
        });
        cdf.setOnDismissListener(new ChooserDialogFragment.OnDismissListener() {
            @Override
            public void onDismiss() {
                mRingtoneManager.stopPreviousRingtone();
            }
        });
    }

    private void initAutoNotifyFragment() {
        mCdfAN = new ChooserDialogFragment();
        mCdfAN.setAccentColor(mAccentColor);
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
        for (int i = 0; i < mRingtoneUris.length; i++) {
            editor.putString(mKeysRingtone[i], mRingtoneUris[i].toString());
        }
        editor.putInt(Definitions.MetaData.KEY_AUTO_NOTIFY, mANPicked);
        editor.apply();
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
            mLdgBackup.dismiss();
            int titleRes, contentRes;
            if (success) {
                titleRes = R.string.backup_success_title;
                contentRes = R.string.backup_success_content;
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
                        cursor.getColumnIndex(Definitions.Database.COLUMN_ID_THINGS));
                int type = cursor.getInt(
                        cursor.getColumnIndex(Definitions.Database.COLUMN_TYPE_THINGS));
                int state = cursor.getInt(
                        cursor.getColumnIndex(Definitions.Database.COLUMN_STATE_THINGS));
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
                File file = FileUtil.createFile(getApplicationInfo().dataDir + "/files",
                        Definitions.MetaData.CREATE_ALARMS_FILE_NAME);
                try {
                    boolean created = file.createNewFile();
                    if (!created) {
                        result = getString(R.string.restore_failed_other);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    result = getString(R.string.restore_failed_other);
                }
            }
            return result;
        }

        @Override
        protected void onPostExecute(String s) {
            mLdgRestore.dismiss();
            String title, content;
            if (s.equals(BackupHelper.SUCCESS)) {
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

            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(SettingsActivity.this,
                    0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2100, pendingIntent);
            mTvRestoreAsBt.postDelayed(new Runnable() {
                @Override
                public void run() {
                    System.exit(0);
                }
            }, 2000);
        }
    }
}
