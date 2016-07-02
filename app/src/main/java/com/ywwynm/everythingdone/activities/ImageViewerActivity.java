package com.ywwynm.everythingdone.activities;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.ImageViewerPagerAdapter;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.ImageLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import uk.co.senab.photoview.PhotoViewAttacher;

import static com.ywwynm.everythingdone.helpers.AttachmentHelper.IMAGE;
import static com.ywwynm.everythingdone.helpers.AttachmentHelper.VIDEO;

public class ImageViewerActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "ImageViewerActivity";

    private App mApplication;

    private boolean mSystemUiVisible = true;

    private int mAccentColor;
    private boolean mEditable;
    private List<String> mTypePathNames;
    private int mPosition;

    private boolean mUpdated = false;

    private Toolbar mActionbar;

    private ViewPager mVpImage;
    private ImageViewerPagerAdapter mAdapter;
    private List<View> mTabs;
    private List<PhotoViewAttacher> mAttachers;

    private LruCache<String, Bitmap> mBitmapCache;

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_image_viewer;
    }

    @Override
    public void onBackPressed() {
        returnToDetailActivity();
    }

    @Override
    protected void initMembers() {
        mApplication = (App) getApplication();

        Intent intent = getIntent();
        mAccentColor = intent.getIntExtra(Def.Communication.KEY_COLOR, 0);
        mEditable = intent.getBooleanExtra(Def.Communication.KEY_EDITABLE, true);
        mTypePathNames = intent.getStringArrayListExtra(
                Def.Communication.KEY_TYPE_PATH_NAME);
        mPosition = intent.getIntExtra(Def.Communication.KEY_POSITION, 0);

        int size = mTypePathNames.size();
        mTabs = new ArrayList<>(size);
        mAttachers = new ArrayList<>(size);

        mBitmapCache = mApplication.getBitmapLruCache();
    }

    @Override
    protected void findViews() {
        mActionbar = f(R.id.actionbar);
        mVpImage = f(R.id.vp_image_viewer);
    }

    @Override
    protected void initUI() {
        View decorView = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decorView.setSystemUiVisibility(flags);

        Point screen = DisplayUtil.getScreenSize(this);
        int width  = screen.x;
        int height = screen.y;
        if (!DeviceUtil.hasKitKatApi() && DisplayUtil.hasNavigationBar(this)) {
            int navigationBarHeight = DisplayUtil.getNavigationBarHeight(this);
            if (getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE) {
                width -= navigationBarHeight;
            } else {
                height -= navigationBarHeight;
            }
        }

        int appAccent = ContextCompat.getColor(mApplication, R.color.app_accent);
        EdgeEffectUtil.forViewPager(mVpImage, appAccent);

        PhotoViewAttacher.OnViewTapListener imageListener = new PhotoViewAttacher.OnViewTapListener() {
            @Override
            public void onViewTap(View view, float x, float y) {
                toggleSystemUI();
            }
        };

        View.OnClickListener videoListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = mVpImage.getCurrentItem();
                String typePathName = mTypePathNames.get(pos);
                String pathName = typePathName.substring(1, typePathName.length());
                File file = new File(pathName);

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(file),
                        "video/" + FileUtil.getPostfix(pathName));
                startActivity(intent);
            }
        };

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String typePathName : mTypePathNames) {
            View tab = inflater.inflate(R.layout.tab_image_attachment, null);

            int type = typePathName.charAt(0) == '0' ? IMAGE : VIDEO;
            String pathName = typePathName.substring(1, typePathName.length());
            String key = AttachmentHelper.generateKeyForCache(pathName, width, height);

            ProgressBar pb          = f(tab, R.id.pb_image_attachment);
            ImageView   iv          = f(tab, R.id.iv_image_attachment);
            ImageView   videoSignal = f(tab, R.id.iv_video_signal);

            pb.getIndeterminateDrawable().setColorFilter(appAccent, PorterDuff.Mode.SRC_IN);

            PhotoViewAttacher attacher = new PhotoViewAttacher(iv);
            attacher.setScaleLevels(1.0f, 3.0f, 6.0f);

            Bitmap bm = mBitmapCache.get(key);
            if (bm == null) {
                new ImageLoader(type, true, mBitmapCache, iv, videoSignal, pb, attacher)
                        .execute(key, width, height);
            } else {
                iv.setImageBitmap(bm);
                attacher.update();
                if (type == 0) {
                    videoSignal.setVisibility(View.GONE);
                } else {
                    videoSignal.setVisibility(View.VISIBLE);
                }
                pb.setVisibility(View.GONE);
            }

            if (type == 0) {
                attacher.setOnViewTapListener(imageListener);
                attacher.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        System.out.println("long");
                        return true;
                    }
                });
            } else {
                videoSignal.setOnClickListener(videoListener);
                attacher.setZoomable(false);
            }
            mAttachers.add(attacher);

            mTabs.add(tab);
        }

        mAdapter = new ImageViewerPagerAdapter(mTabs);
        mVpImage.setAdapter(mAdapter);

        mVpImage.setCurrentItem(mPosition);
    }

    @Override
    protected void setActionbar() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mActionbar.getLayoutParams();
        params.setMargins(0, DisplayUtil.getStatusbarHeight(this), 0, 0);
        mActionbar.requestLayout();

        setSupportActionBar(mActionbar);
        ActionBar actionBar = getSupportActionBar();
        updateAttachmentNumber();
        actionBar.setDisplayHomeAsUpEnabled(true);
        mActionbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToDetailActivity();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_image_viewer, menu);
        if (!mEditable) {
            MenuItem item = menu.findItem(R.id.act_delete_attachment);
            item.setVisible(false);
            item.setEnabled(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.act_show_attachment_info) {
            AttachmentHelper.showAttachmentInfoDialog(
                    this, mAccentColor, mTypePathNames.get(mVpImage.getCurrentItem()));
        } else if (id == R.id.act_delete_attachment) {
            final AlertDialogFragment adf = new AlertDialogFragment();
            adf.setContentColor(ContextCompat.getColor(this, R.color.black_69p));
            adf.setConfirmColor(mAccentColor);
            adf.setContent(getString(R.string.alert_delete_attachment));
            adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
                @Override
                public void onConfirm() {
                    int currentIndex = mVpImage.getCurrentItem();
                    mAttachers.remove(currentIndex);
                    mTypePathNames.remove(currentIndex);
                    mAdapter.removeTab(mVpImage, currentIndex);
                    updateAttachmentNumber();
                    mUpdated = true;
                    if (mAdapter.getCount() == 0) {
                        returnToDetailActivity();
                    }
                }
            });
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);
            return true;
        }
        return false;
    }

    @Override
    protected void setEvents() {
        mVpImage.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateAttachmentNumber();
            }
        });
    }

    private void updateAttachmentNumber() {
        int current = mVpImage.getCurrentItem() + 1;
        int total   = mTypePathNames.size();
        getSupportActionBar().setTitle(current + " / " + total);
    }

    private void toggleSystemUI() {
        View decorView = getWindow().getDecorView();
        int visibility = decorView.getSystemUiVisibility();
        if (mSystemUiVisible) {
            if (DeviceUtil.hasKitKatApi()) {
                decorView.setSystemUiVisibility(visibility
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE);
            } else {
                decorView.setSystemUiVisibility(visibility
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE);
            }
            mActionbar.setVisibility(View.GONE);
        } else {
            if (DeviceUtil.hasKitKatApi()) {
                decorView.setSystemUiVisibility(visibility
                        & ~View.SYSTEM_UI_FLAG_FULLSCREEN
                        & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        & ~View.SYSTEM_UI_FLAG_IMMERSIVE);
            } else {
                decorView.setSystemUiVisibility(visibility
                        & ~View.SYSTEM_UI_FLAG_FULLSCREEN
                        & ~View.SYSTEM_UI_FLAG_LOW_PROFILE);
            }
            mActionbar.setVisibility(View.VISIBLE);
        }
        mSystemUiVisible = !mSystemUiVisible;
    }

    private void returnToDetailActivity() {
        if (mUpdated) {
            Intent intent = new Intent();
            intent.putExtra(Def.Communication.KEY_TYPE_PATH_NAME, (ArrayList) mTypePathNames);
            setResult(Def.Communication.RESULT_UPDATE_IMAGE_DONE, intent);
        } else {
            setResult(Def.Communication.RESULT_NO_UPDATE);
        }
        finish();
    }
}
