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
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

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

    private ModeManager mModeManager;

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

    public void setModeManager(ModeManager modeManager) {
        mModeManager = modeManager;
    }

    @Override
    protected int getCurrentMode() {
        return mModeManager.getCurrentMode();
    }

    @Override
    protected List<Thing> getThings() {
        return mThingManager.getThings();
    }

    public boolean shouldThingsAnimWhenAppearing() {
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
        int mX = (int) (mDensity * 4);
        if (DeviceUtil.hasLollipopApi()) {
            mX = (int) (mDensity * 6);
        }
        int mY = header ? 0 : mX;

        int height;
        if (header) {
            height = (int) (App.isSearching ? mDensity * 6 : mDensity * 102);
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
            }, position * 30L);
        }
    }

    @Override
    protected void onChecklistAdapterInitialized(
            final BaseThingViewHolder holder, final CheckListAdapter adapter, final Thing thing) {
        super.onChecklistAdapterInitialized(holder, adapter, thing);
        boolean toggleCliOtc = FrequentSettings.getBoolean(Def.Meta.KEY_TOGGLE_CLI_OTC);
        if (!toggleCliOtc
                || thing.getType() <= Thing.HEADER
                || thing.getType() >= Thing.NOTIFICATION_UNDERWAY
                || thing.getState() != Thing.UNDERWAY
                || getCurrentMode() != ModeManager.NORMAL
                || App.getDoingThingId() == thing.getId()) {
            holder.cv.setShouldInterceptTouchEvent(true);
            adapter.setTvItemClickCallback(null);
        } else {
            holder.cv.setShouldInterceptTouchEvent(false);
            adapter.setTvItemClickCallback(new CheckListAdapter.TvItemClickCallback() {
                @Override
                public void onItemClick(int itemPos) {
                    boolean simpleFCli = FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI);
                    String content = thing.getContent();
                    if (simpleFCli) {
                        List<String> items = CheckListHelper.toCheckListItems(content, false);
                        items.remove("2");
                        items.remove("3");
                        items.remove("4");
                        if (itemPos < 0 || itemPos >= items.size()
                                || items.get(itemPos).startsWith("1")) {
                            return;
                        }
                    }

                    String updatedContent = CheckListHelper.toggleChecklistItem(content, itemPos);
                    thing.setContent(updatedContent);
                    int typeBefore = thing.getType();
                    int thingPos = holder.getAdapterPosition();
                    if (thingPos == -1) return;
                    ThingManager.getInstance(mApp).update(typeBefore, thing, thingPos, false);
                    notifyItemChanged(thingPos);
                    long thingId = thing.getId();
                    int thingType = thing.getType();
                    AppWidgetHelper.updateSingleThingAppWidgets(mApp, thingId);
                    AppWidgetHelper.updateThingsListAppWidgetsForType(mApp, thingType);
                    SystemNotificationUtil.cancelNotification(thingId, thingType, mApp);
                }

                @Override
                public void onItemSpaceClick(View v) {
                    if (mOnItemTouchedListener != null) {
                        mOnItemTouchedListener.onItemClick(v, holder.getAdapterPosition());
                    }
                }
            });
        }
    }

    public interface OnItemTouchedListener {
        boolean onItemTouch(View v, MotionEvent event);
        void    onItemClick(View v, int position);
        boolean onItemLongClick(View v, int position);
    }

    private class ThingViewHolder extends BaseThingViewHolder {

        ThingViewHolder(View item) {
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
