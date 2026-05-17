package com.ywwynm.everythingdone.adapters;

import android.os.Handler;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
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

    public interface OnNewItemBoundListener {
        void onNewItemBound(int position, BaseThingViewHolder holder);
    }

    private int                       mArmedNewItemPosition = -1;
    private long                      mArmedNewItemId       = -1L;
    private OnNewItemBoundListener    mArmedNewItemListener;

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

    /**
     * Arm a one-shot animation for a freshly created thing.
     * When the row at {@code position} is next bound, its inner content is hidden and the
     * listener is invoked so the activity can play a reveal / shining-border animation on it.
     * The thing id is captured so we can re-match the row even if the position shifts before
     * binding (e.g. another insert lands first).
     */
    public void armNewItemAnimation(int position, long thingId, OnNewItemBoundListener listener) {
        mArmedNewItemPosition = position;
        mArmedNewItemId       = thingId;
        mArmedNewItemListener = listener;
    }

    public void clearArmedNewItemAnimation() {
        mArmedNewItemPosition = -1;
        mArmedNewItemId       = -1L;
        mArmedNewItemListener = null;
    }

    @Override
    public BaseThingViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ThingViewHolder(mInflater.inflate(R.layout.card_thing, parent, false));
    }

    @Override
    public void onBindViewHolder(final BaseThingViewHolder holder, int position) {
        distinguishHeaderAndOthers(getThings().get(position).getType() == Thing.HEADER, holder.cv);
        super.onBindViewHolder(holder, position);

        final boolean armed = isArmedFor(position);

        // Reset any state a previously-armed (and now recycled) holder may have left behind.
        // (distinguishHeaderAndOthers above already sets cv.visibility for us.)
        if (!armed) {
            if (holder.llContent.getAlpha() != 1f) holder.llContent.setAlpha(1f);
            if (holder.cv.getAlpha() != 1f)        holder.cv.setAlpha(1f);
        }

        // Skip the generic "appearing" animation for the freshly-armed new item — that
        // animation forces visibility=VISIBLE on a delay and would un-hide the card
        // before our reveal/shining-border has a chance to run.
        if (mShouldThingsAnimWhenAppearing && !armed) {
            playAppearingAnimation(holder.cv, position);
        }

        maybeTriggerArmedNewItemAnimation(holder, position);
    }

    private boolean isArmedFor(int position) {
        if (mArmedNewItemListener == null) return false;
        if (position != mArmedNewItemPosition) return false;
        if (mArmedNewItemId == -1L) return true;
        return getThings().get(position).getId() == mArmedNewItemId;
    }

    private void maybeTriggerArmedNewItemAnimation(final BaseThingViewHolder holder, int position) {
        if (!isArmedFor(position)) return;

        final OnNewItemBoundListener listener = mArmedNewItemListener;
        final int firedPosition = position;
        clearArmedNewItemAnimation();

        // Hide the whole card (background + content) until our animation runs.
        // Using visibility=INVISIBLE is more robust than alpha — the RecyclerView default
        // add animator drives alpha, so an alpha=0 we set here would get overwritten as
        // soon as the add animation kicks in. INVISIBLE keeps the card laid out and
        // measured (so we can locate it precisely for the border) but stops it drawing.
        holder.cv.setVisibility(View.INVISIBLE);
        holder.llContent.setAlpha(1f);
        holder.cv.setAlpha(1f);

        holder.cv.post(new Runnable() {
            @Override
            public void run() {
                if (holder.cv.getWidth() == 0 || holder.cv.getHeight() == 0) {
                    // Layout not done yet — try again on the next frame.
                    holder.cv.post(this);
                    return;
                }
                listener.onNewItemBound(firedPosition, holder);
            }
        });
    }

    private void distinguishHeaderAndOthers(boolean header, CardView cv) {
        int mX = (int) (mDensity * 6);
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
