package com.ywwynm.everythingdone.adapters;

import android.os.Handler;
import android.support.v7.widget.CardView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DeviceUtil;

import java.util.List;

/**
 * Created by ywwynm on 2015/5/28.
 * Adapter for things.
 */
public class ThingsAdapter extends BaseThingsAdapter {

    public static final String TAG = "ThingsAdapter";

    private App mApp;
    private ThingManager mThingManager;

    private OnItemTouchedListener mOnItemTouchedListener;

    // decrease memory usage as much as possible.
    private View.OnTouchListener mOnTouchListener;

    private boolean mShouldThingsAnimWhenAppearing = true;

    private Handler mAnimHandler;

    public ThingsAdapter(App app, OnItemTouchedListener listener) {
        super(app);

        mApp = app;
        mThingManager = ThingManager.getInstance(mApp);

        mOnItemTouchedListener = listener;
        mOnTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mOnItemTouchedListener.onItemTouch(v, event);
            }
        };

        mAnimHandler = new Handler();
    }

    @Override
    protected List<Thing> getThings() {
        return mThingManager.getThings();
    }

    @Override
    protected int getCurrentMode() {
        return mApp.getModeManager().getCurrentMode();
    }

    public boolean isShouldThingsAnimWhenAppearing() {
        return mShouldThingsAnimWhenAppearing;
    }

    public void setShouldThingsAnimWhenAppearing(boolean shouldThingsAnimWhenAppearing) {
        mShouldThingsAnimWhenAppearing = shouldThingsAnimWhenAppearing;
    }

    @Override
    public BaseThingViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ThingViewHolder(mInflater.inflate(R.layout.card_thing, parent, false));
    }

    @Override
    public void onBindViewHolder(BaseThingViewHolder holder, int position) {
        distinguishHeaderAndOthers(getThings().get(position).getType() == Thing.HEADER, holder.cv);
        super.onBindViewHolder(holder, position);

        if (mShouldThingsAnimWhenAppearing) {
            playAppearingAnimation(holder.cv, position);
        }
    }

    private void distinguishHeaderAndOthers(boolean header, CardView cv) {
        int mX = (int) (mScreenDensity * 4);
        if (DeviceUtil.hasLollipopApi()) {
            mX = (int) (mScreenDensity * 6);
        }
        int mY = header ? 0 : mX;

        int height;
        if (header) {
            height = (int) (App.isSearching ? mScreenDensity * 6 : mScreenDensity * 102);
        } else {
            height = StaggeredGridLayoutManager.LayoutParams.WRAP_CONTENT;
        }

        cv.setVisibility(header ? View.INVISIBLE : View.VISIBLE);
        StaggeredGridLayoutManager.LayoutParams lp =
                (StaggeredGridLayoutManager.LayoutParams) cv.getLayoutParams();
        lp.height = height;
        lp.setMargins(mX, mY, mX, mY);
        lp.setFullSpan(header);
    }

    private void playAppearingAnimation(final View v, int position) {
        v.setVisibility(View.INVISIBLE);
        if (getItemViewType(position) != Thing.HEADER) {
            final Animation animation = AnimationUtils.loadAnimation(
                    mApp, R.anim.things_show);
            animation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    if (!mShouldThingsAnimWhenAppearing) {
                        v.clearAnimation();
                    }
                }

                @Override
                public void onAnimationEnd(Animation animation) { }

                @Override
                public void onAnimationRepeat(Animation animation) { }
            });
            mAnimHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    v.setVisibility(View.VISIBLE);
                    v.startAnimation(animation);
                }
            }, position * 30);
        }
    }

    @Override
    protected void onChecklistAdapterInitialized(
            CheckListAdapter adapter, final Thing thing, final int thingPos) {
        super.onChecklistAdapterInitialized(adapter, thing, thingPos);
        if (thing.getType() <= Thing.HEADER
                || thing.getType() >= Thing.NOTIFICATION_UNDERWAY
                || thing.getState() != Thing.UNDERWAY
                || getCurrentMode() != ModeManager.NORMAL) {
            adapter.setTvItemClickCallback(null);
            return;
        }
        adapter.setTvItemClickCallback(new CheckListAdapter.TvItemClickCallback() {
            @Override
            public void onItemClick(int itemPos) {
                String updatedContent = CheckListHelper.toggleChecklistItem(
                        thing.getContent(), itemPos);
                thing.setContent(updatedContent);
                int typeBefore = thing.getType();
                ThingManager.getInstance(mApp).update(typeBefore, thing, thingPos, false);
                notifyItemChanged(thingPos);
            }
        });
    }

    public interface OnItemTouchedListener {
        boolean onItemTouch(View v, MotionEvent event);
        void    onItemClick(View v, int position);
        boolean onItemLongClick(View v, int position);
    }

    class ThingViewHolder extends BaseThingViewHolder {

        public ThingViewHolder(View item) {
            super(item);

            if (mOnItemTouchedListener != null) {
                cv.setOnTouchListener(mOnTouchListener);
                cv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mOnItemTouchedListener.onItemClick(v, getAdapterPosition());
                    }
                });
                cv.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        return mOnItemTouchedListener.onItemLongClick(v, getAdapterPosition());
                    }
                });
            }
        }
    }
}
