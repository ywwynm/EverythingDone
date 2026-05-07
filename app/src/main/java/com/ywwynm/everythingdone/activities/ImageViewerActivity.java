package com.ywwynm.everythingdone.activities;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import androidx.activity.OnBackPressedCallback;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.bumptech.glide.Glide;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.OnPhotoTapListener;
import com.github.chrisbanes.photoview.PhotoView;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.ImageViewerPagerAdapter;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.ywwynm.everythingdone.helpers.AttachmentHelper.IMAGE;
import static com.ywwynm.everythingdone.helpers.AttachmentHelper.VIDEO;

public class ImageViewerActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "ImageViewerActivity";

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

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_image_viewer;
    }

    @Override
    protected void initMembers() {
        Intent intent = getIntent();
        mAccentColor = intent.getIntExtra(Def.Communication.KEY_COLOR, 0);
        mEditable = intent.getBooleanExtra(Def.Communication.KEY_EDITABLE, true);
        mTypePathNames = intent.getStringArrayListExtra(
                Def.Communication.KEY_TYPE_PATH_NAME);
        mPosition = intent.getIntExtra(Def.Communication.KEY_POSITION, 0);

        int size = mTypePathNames.size();
        mTabs = new ArrayList<>(size);
    }

    @Override
    protected void findViews() {
        mActionbar = f(R.id.actionbar);
        mVpImage   = f(R.id.vp_image_viewer);
    }

    @Override
    protected void initUI() {
        View decorView = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decorView.setSystemUiVisibility(flags);

        int appAccent = ContextCompat.getColor(this, R.color.app_accent);
        EdgeEffectUtil.forViewPager(mVpImage, appAccent);

        int[] size = getImageSize();
        OnPhotoTapListener imageListener = getImageListener();
        View.OnClickListener videoListener = getVideoListener();

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String typePathName : mTypePathNames) {
            @SuppressLint("InflateParams")
            View tab = inflater.inflate(R.layout.tab_image_attachment, null);

            int type = typePathName.charAt(0) == '0' ? IMAGE : VIDEO;
            String pathName = typePathName.substring(1, typePathName.length());

            ProgressBar pb          = f(tab, R.id.pb_image_attachment);
            PhotoView   iv          = f(tab, R.id.iv_image_attachment);
            ImageView   videoSignal = f(tab, R.id.iv_video_signal);

            pb.getIndeterminateDrawable().setColorFilter(appAccent, PorterDuff.Mode.SRC_IN);

            iv.setScaleLevels(1.0f, 3.0f, 6.0f);

            if (type == 0) {
                iv.setContentDescription(getString(R.string.cd_image_attachment));
                videoSignal.setVisibility(View.GONE);
                iv.setOnPhotoTapListener(imageListener);
            } else {
                iv.setContentDescription(getString(R.string.cd_video_attachment));
                videoSignal.setVisibility(View.VISIBLE);
                videoSignal.setOnClickListener(videoListener);
                iv.setZoomable(false);
            }

            loadImage(pathName, iv, pb, size);

            mTabs.add(tab);
        }

        mAdapter = new ImageViewerPagerAdapter(mTabs);
        mVpImage.setAdapter(mAdapter);

        mVpImage.setCurrentItem(mPosition);
    }

    private int[] getImageSize() {
        Point screen = DisplayUtil.getScreenSize(this);
        int width  = screen.x;
        int height = screen.y;
        return new int[] { width, height };
    }

    private OnPhotoTapListener getImageListener() {
        return new OnPhotoTapListener() {
            @Override
            public void onPhotoTap(ImageView view, float x, float y) {
                toggleSystemUI();
            }
        };
    }

    private View.OnClickListener getVideoListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = mVpImage.getCurrentItem();
                String typePathName = mTypePathNames.get(pos);
                String pathName = typePathName.substring(1, typePathName.length());
                File file = new File(pathName);

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Uri uri = FileProvider.getUriForFile(ImageViewerActivity.this,
                        "com.ywwynm.everythingdone", file);
                intent.setDataAndType(uri,
                        "video/" + FileUtil.getPostfix(pathName));
                startActivity(intent);
            }
        };
    }

    private void loadImage(
            String pathName, final PhotoView iv,
            final ProgressBar pb, int[] size) {
        Glide.with(this)
                .load(pathName)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(
                            GlideException e, Object model, Target<Drawable> target,
                            boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(
                            Drawable resource, Object model, Target<Drawable> target,
                            DataSource dataSource, boolean isFirstResource) {
                        iv.setImageDrawable(resource);
                        pb.setVisibility(View.GONE);
                        return true;
                    }
                })
                .override(size[0], size[1])
                .into(iv);
    }

    @Override
    protected void setActionbar() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mActionbar.getLayoutParams();
        params.setMargins(0, DisplayUtil.getStatusbarHeight(this), 0, 0);
        mActionbar.requestLayout();

        setSupportActionBar(mActionbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        updateAttachmentNumber();
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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                returnToDetailActivity();
            }
        });

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
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(current + " / " + total);
        }
    }

    private void toggleSystemUI() {
        View decorView = getWindow().getDecorView();
        int visibility = decorView.getSystemUiVisibility();
        if (mSystemUiVisible) {
            decorView.setSystemUiVisibility(visibility
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE);
            mActionbar.setVisibility(View.GONE);
        } else {
            decorView.setSystemUiVisibility(visibility
                    & ~View.SYSTEM_UI_FLAG_FULLSCREEN
                    & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    & ~View.SYSTEM_UI_FLAG_IMMERSIVE);
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
