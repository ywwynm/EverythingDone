package com.ywwynm.everythingdone.adapters;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.support.annotation.DrawableRes;
import android.support.annotation.IntDef;
import android.support.annotation.StringRes;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.LongSparseArray;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.views.HabitRecordPresenter;
import com.ywwynm.everythingdone.views.InterceptTouchCardView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Created by ywwynm on 2016/7/31.
 * Basic things adapter for RecyclerView. Created for re-use.
 */
public abstract class BaseThingsAdapter extends RecyclerView.Adapter<BaseThingsAdapter.BaseThingViewHolder> {

    public static final String TAG = "BaseThingsAdapter";

    public static final int STYLE_WHITE = 0;
    public static final int STYLE_BLACK = 1;

    @IntDef({STYLE_WHITE, STYLE_BLACK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Style {}

    private static int white_86p;
    private static int white_76p;
    private static int white_66p;
    private static int black_86p;
    private static int black_76p;
    private static int black_66p;

    static {
        Context context = App.getApp();
        white_86p = ContextCompat.getColor(context, R.color.white_86p);
        white_76p = ContextCompat.getColor(context, R.color.white_76p);
        white_66p = ContextCompat.getColor(context, R.color.white_66p);
        black_86p = ContextCompat.getColor(context, R.color.black_86p);
        black_76p = ContextCompat.getColor(context, R.color.black_76p);
        black_66p = ContextCompat.getColor(context, R.color.black_66p);
    }

    protected LayoutInflater mInflater;
    protected float mDensity;

    private Context mContext;

    private LongSparseArray<CheckListAdapter> mCheckListAdapters;

    private ReminderDAO mReminderDAO;
    private HabitDAO    mHabitDAO;

    private RequestManager mImageRequestManager;

    private int mCardWidth;
    protected @Style int mStyle = STYLE_WHITE;
    private boolean mShouldShowPrivateContent = false;
    private int mChecklistMaxItemCount = 8;

    protected abstract int getCurrentMode();
    protected abstract List<Thing> getThings();

    public BaseThingsAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
        mDensity = DisplayUtil.getScreenDensity(context);

        mContext = context;

        mCheckListAdapters = new LongSparseArray<>();

        mReminderDAO = ReminderDAO.getInstance(context);
        mHabitDAO    = HabitDAO.getInstance(context);

        mImageRequestManager = Glide.with(context);

        mCardWidth = DisplayUtil.getThingCardWidth(context);
    }

    public void setCardWidth(int cardWidth) {
        mCardWidth = cardWidth;
    }

    public void setStyle(int style) {
        mStyle = style;
    }

    public void setShouldShowPrivateContent(boolean shouldShowPrivateContent) {
        mShouldShowPrivateContent = shouldShowPrivateContent;
    }

    public void setChecklistMaxItemCount(int checklistMaxItemCount) {
        mChecklistMaxItemCount = checklistMaxItemCount;
    }

    @Override
    public BaseThingViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new BaseThingViewHolder(mInflater.inflate(R.layout.card_thing, parent, false));
    }

    @Override
    public void onBindViewHolder(BaseThingViewHolder holder, int position) {
        Thing thing = getThings().get(position);
        setContentViewAppearance(holder, thing);
        setCardAppearance(holder, thing.getColor(), thing.isSelected());
    }

    private void setContentViewAppearance(final BaseThingViewHolder holder, Thing thing) {
        updateCardForStickyOrOngoingNotification(holder, thing);
        updateCardForTitle(holder, thing);

        if (thing.isPrivate() && !mShouldShowPrivateContent) {
            holder.ivPrivateThing.setVisibility(View.VISIBLE);
            holder.flImageAttachment.setVisibility(View.GONE);
            holder.tvContent.setVisibility(View.GONE);
            holder.rvChecklist.setVisibility(View.GONE);
            holder.llAudioAttachment.setVisibility(View.GONE);
            holder.rlReminder.setVisibility(View.GONE);
            holder.rlHabit.setVisibility(View.GONE);
        } else {
            holder.ivPrivateThing.setVisibility(View.GONE);

            updateCardForContent(holder, thing);
            updateCardForReminder(holder, thing);
            updateCardForHabit(holder, thing);
            updateCardForAudioAttachment(holder, thing);
            updateCardForImageAttachment(holder, thing);

            updateCardSeparatorsIfNeeded(holder);

            enlargeAudioLayoutIfNeeded(holder);
        }

        updateCardForDoing(holder, thing);
    }

    private void updateCardForStickyOrOngoingNotification(BaseThingViewHolder holder, Thing thing) {
        boolean sticky = thing.getLocation() < 0;
        boolean ongoing = FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == thing.getId();
        if (!sticky && !ongoing) {
            holder.ivStickyOngoing.setVisibility(View.GONE);
        } else {
            holder.ivStickyOngoing.setVisibility(View.VISIBLE);
            if (getCurrentMode() != ModeManager.NORMAL && !thing.isSelected()) {
                holder.ivStickyOngoing.setImageResource(sticky ?
                        R.drawable.ic_sticky_not_selected : R.drawable.ic_ongoing_notication_not_selected);
            } else {
                holder.ivStickyOngoing.setImageResource(sticky ?
                        R.drawable.ic_sticky : R.drawable.ic_ongoing_notication);
            }
            @StringRes int cdRes = sticky ? R.string.sticky_thing : R.string.ongoing_thing;
            holder.ivStickyOngoing.setContentDescription(mContext.getString(cdRes));
        }
    }

    private void updateCardForTitle(BaseThingViewHolder holder, Thing thing) {
        String title = thing.getTitleToDisplay();
        if (!title.isEmpty()) {
            int p = (int) (mDensity * 16);
            holder.tvTitle.setVisibility(View.VISIBLE);
            holder.tvTitle.setPadding(p, p, p, 0);
            holder.tvTitle.setText(title);
            if (mStyle == STYLE_WHITE) {
                holder.tvTitle.setTextColor(white_86p);
            } else {
                holder.tvTitle.setTextColor(black_86p);
            }
        } else {
            holder.tvTitle.setVisibility(View.GONE);
        }
    }

    @SuppressLint("NewApi")
    private void updateCardForContent(BaseThingViewHolder holder, Thing thing) {
        int p = (int) (mDensity * 16);
        String content = thing.getContent();
        if (!content.isEmpty()) {
            if (!CheckListHelper.isCheckListStr(content)) {
                holder.rvChecklist.setVisibility(View.GONE);
                holder.tvContent.setVisibility(View.VISIBLE);

                int length = content.length();
                if (length <= 60) {
                    holder.tvContent.setTextSize(-0.14f * length + 24.14f);
                } else {
                    holder.tvContent.setTextSize(16);
                }

                holder.tvContent.setPadding(p, p, p, 0);
                holder.tvContent.setText(content);
                if (mStyle == STYLE_WHITE) {
                    holder.tvContent.setTextColor(white_76p);
                } else {
                    holder.tvContent.setTextColor(black_76p);
                }
            } else {
                holder.tvContent.setVisibility(View.GONE);
                holder.rvChecklist.setVisibility(View.VISIBLE);

                long id = thing.getId();
                List<String> items = CheckListHelper.toCheckListItems(content, false);
                CheckListAdapter adapter = mCheckListAdapters.get(id);
                if (adapter == null) {
                    adapter = new CheckListAdapter(mContext,
                            CheckListAdapter.TEXTVIEW, items);
                    mCheckListAdapters.put(id, adapter);
                } else {
                    adapter.setItems(items);
                }
                adapter.setStyle(mStyle);
                adapter.setMaxItemCount(mChecklistMaxItemCount);
                onChecklistAdapterInitialized(holder, adapter, thing);
                holder.rvChecklist.setAdapter(adapter);
                holder.rvChecklist.setLayoutManager(new LinearLayoutManager(mContext));

                int rp = (int) (mDensity * 6);
                holder.rvChecklist.setPaddingRelative(rp, p, p, 0);
            }
        } else {
            holder.tvContent.setVisibility(View.GONE);
            holder.rvChecklist.setVisibility(View.GONE);
        }
    }

    protected void onChecklistAdapterInitialized(
            BaseThingViewHolder holder, CheckListAdapter adapter, Thing thing) {
        // do nothing here
    }

    private void updateCardForReminder(BaseThingViewHolder holder, Thing thing) {
        int thingType = thing.getType();
        if (!Thing.isReminderType(thingType)) {
            holder.rlReminder.setVisibility(View.GONE);
            return;
        }

        Reminder reminder = mReminderDAO.getReminderById(thing.getId());
        if (reminder == null) {
            holder.rlReminder.setVisibility(View.GONE);
            return;
        }

        int p = (int) (mDensity * 16);
        holder.rlReminder.setVisibility(View.VISIBLE);
        holder.rlReminder.setPadding(p, p, p, 0);

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                holder.ivReminder.getLayoutParams();
        if (thingType == Thing.REMINDER) {
            params.setMargins(0, (int) (mDensity * 2), 0, 0);
            holder.ivReminder.setImageResource(R.drawable.card_reminder);
            holder.ivReminder.setContentDescription(mContext.getString(R.string.reminder));
            holder.tvReminderTime.setTextSize(12);

            holder.tvReminderTime.setText(
                    DateTimeUtil.getDateTimeStrReminder(mContext, thing, reminder));
        } else {
            params.setMargins(0, (int) (mDensity * 1.6), 0, 0);
            holder.ivReminder.setImageResource(R.drawable.card_goal);
            holder.ivReminder.setContentDescription(mContext.getString(R.string.goal));
            holder.tvReminderTime.setTextSize(16);

            holder.tvReminderTime.setText(
                    DateTimeUtil.getDateTimeStrGoal(mContext, thing, reminder));
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateCardForHabit(BaseThingViewHolder holder, Thing thing) {
        if (thing.getType() != Thing.HABIT) {
            holder.rlHabit.setVisibility(View.GONE);
            return;
        }

        Habit habit = mHabitDAO.getHabitById(thing.getId());
        if (habit == null) {
            holder.rlHabit.setVisibility(View.GONE);
            return;
        }

        int p = (int) (mDensity * 16);
        holder.rlHabit.setVisibility(View.VISIBLE);
        holder.rlHabit.setPadding(p, p, p, 0);

        String summary = habit.getSummary(mContext);
        if (thing.getState() == Thing.UNDERWAY && habit.isPaused()) {
            summary += ", " + habit.getStateDescription(mContext);
        }
        holder.tvHabitSummary.setText(summary);

        if (thing.getState() == Thing.UNDERWAY && !habit.isPaused()) {
            holder.tvHabitNextReminder.setVisibility(View.VISIBLE);
            holder.vHabitSeparator2.setVisibility(View.VISIBLE);
            holder.tvHabitLastFive.setVisibility(View.VISIBLE);
            holder.llHabitRecord.setVisibility(View.VISIBLE);
            holder.tvHabitFinishedThisT.setVisibility(View.VISIBLE);

            String next = mContext.getString(R.string.habit_next_reminder);
            holder.tvHabitNextReminder.setText(
                    next + " " + habit.getNextReminderDescription(mContext));

            String record = habit.getRecord();
            StringBuilder lastFive;
            int len = record.length();
            if (len >= 5) {
                lastFive = new StringBuilder(record.substring(len - 5, len));
            } else {
                lastFive = new StringBuilder(record);
                for (int i = 0; i < 5 - len; i++) {
                    lastFive.append("?");
                }
            }
            holder.habitRecordPresenter.setRecord(lastFive.toString());

            holder.tvHabitFinishedThisT.setText(habit.getFinishedTimesThisTStr(mContext));
        } else {
            holder.tvHabitNextReminder.setVisibility(View.GONE);
            holder.vHabitSeparator2.setVisibility(View.GONE);
            holder.tvHabitLastFive.setVisibility(View.GONE);
            holder.llHabitRecord.setVisibility(View.GONE);
            holder.tvHabitFinishedThisT.setVisibility(View.GONE);
        }
    }

    private void updateCardForImageAttachment(final BaseThingViewHolder holder, Thing thing) {
        String attachment = thing.getAttachment();
        String firstImageTypePathName = AttachmentHelper.getFirstImageTypePathName(attachment);
        if (firstImageTypePathName != null) {
            holder.flImageAttachment.setVisibility(View.VISIBLE);

            int imageW = mCardWidth;
            int imageH = imageW * 3 / 4;

            // without this, the first card with an image won't display well on lollipop device
            // and I don't know the reason...
            LinearLayout.LayoutParams paramsLayout = (LinearLayout.LayoutParams)
                    holder.flImageAttachment.getLayoutParams();
            paramsLayout.width = imageW;

            // set height at first to get a placeHolder before image has been loaded
            FrameLayout.LayoutParams paramsImage = (FrameLayout.LayoutParams)
                    holder.ivImageAttachment.getLayoutParams();
            paramsImage.height = imageH;

            // set height of cover
            FrameLayout.LayoutParams paramsCover = (FrameLayout.LayoutParams)
                    holder.vImageCover.getLayoutParams();
            paramsCover.height = imageH;

            // before lollipop, set margins to negative number to remove ugly stroke
            if (!DeviceUtil.hasLollipopApi()) {
                int m = (int) (mDensity * -8);
                paramsLayout.setMargins(0, m, 0, 0);
            }

            String pathName = firstImageTypePathName.substring(1, firstImageTypePathName.length());
            mImageRequestManager
                    .load(pathName)
                    .listener(new RequestListener<String, GlideDrawable>() {
                        @Override
                        public boolean onException(
                                Exception e, String model, Target<GlideDrawable> target,
                                boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(
                                GlideDrawable resource, String model, Target<GlideDrawable> target,
                                boolean isFromMemoryCache, boolean isFirstResource) {
                            holder.pbLoading.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .centerCrop()
                    .into(holder.ivImageAttachment);

            // if thing has only an image/video, there should be no margins for ImageView
            if (holder.tvTitle.getVisibility() == View.GONE
                    && holder.tvContent.getVisibility() == View.GONE
                    && holder.rvChecklist.getVisibility() == View.GONE
                    && holder.llAudioAttachment.getVisibility() == View.GONE
                    && holder.rlReminder.getVisibility() == View.GONE
                    && holder.rlHabit.getVisibility() == View.GONE) {
                holder.vPaddingBottom.setVisibility(View.GONE);
            } else {
                holder.vPaddingBottom.setVisibility(View.VISIBLE);
            }

            holder.tvImageCount.setText(AttachmentHelper.getImageAttachmentCountStr(attachment, mContext));

            // when this card is unselected in selecting/moving mode, image should be covered
            if (getCurrentMode() == ModeManager.NORMAL) {
                holder.vImageCover.setVisibility(View.GONE);
            } else {
                holder.vImageCover.setVisibility(thing.isSelected() ? View.GONE : View.VISIBLE);
            }
        } else {
            holder.vPaddingBottom.setVisibility(View.VISIBLE);
            holder.flImageAttachment.setVisibility(View.GONE);
        }
    }

    private void updateCardForAudioAttachment(BaseThingViewHolder holder, Thing thing) {
        String attachment = thing.getAttachment();
        String str = AttachmentHelper.getAudioAttachmentCountStr(attachment, mContext);
        if (str == null) {
            holder.llAudioAttachment.setVisibility(View.GONE);
        } else {
            holder.llAudioAttachment.setVisibility(View.VISIBLE);
            int p = (int) (mDensity * 16);
            holder.llAudioAttachment.setPadding(p, p / 4 * 3, p, 0);

            holder.tvAudioCount.setText(str);

            if (mStyle == STYLE_WHITE) {
                holder.ivAudioCount.setImageResource(R.drawable.card_audio_attachment);
                holder.tvAudioCount.setTextColor(white_66p);
            } else {
                holder.ivAudioCount.setImageResource(R.drawable.card_audio_attachment_black);
                holder.tvAudioCount.setTextColor(black_66p);
            }
        }
    }

    private void updateCardSeparatorsIfNeeded(BaseThingViewHolder holder) {
        if (holder.flImageAttachment.getVisibility() == View.VISIBLE
                && holder.tvTitle.getVisibility() == View.GONE
                && holder.tvContent.getVisibility() == View.GONE
                && holder.rvChecklist.getVisibility() == View.GONE
                && holder.llAudioAttachment.getVisibility() == View.GONE) {
            if (holder.rlReminder.getVisibility() == View.VISIBLE) {
                holder.vReminderSeparator.setVisibility(View.GONE);
            } else if (holder.rlHabit.getVisibility() == View.VISIBLE) {
                holder.vHabitSeparator1.setVisibility(View.GONE);
            }
        } else {
            if (holder.rlReminder.getVisibility() == View.VISIBLE) {
                holder.vReminderSeparator.setVisibility(View.VISIBLE);
            } else if (holder.rlHabit.getVisibility() == View.VISIBLE) {
                holder.vHabitSeparator1.setVisibility(View.VISIBLE);
            }
        }
    }

    private void enlargeAudioLayoutIfNeeded(BaseThingViewHolder holder) {
        if (holder.llAudioAttachment.getVisibility() != View.VISIBLE) {
            return;
        }

        LinearLayout.LayoutParams llp1 = (LinearLayout.LayoutParams)
                holder.ivAudioCount.getLayoutParams();
        LinearLayout.LayoutParams llp2 = (LinearLayout.LayoutParams)
                holder.tvAudioCount.getLayoutParams();
        int dp1  = (int) (mDensity * 1);
        int dp8  = (int) (mDensity * 8);
        int dp12 = (int) (mDensity * 12);
        int dp16 = (int) (mDensity * 16);
        if (holder.flImageAttachment.getVisibility() == View.GONE
                && holder.tvTitle.getVisibility() == View.GONE
                && holder.tvContent.getVisibility() == View.GONE
                && holder.rvChecklist.getVisibility() == View.GONE) {
            llp1.height = (int) (mDensity * 16);
            llp1.topMargin = dp1;
            holder.tvAudioCount.setTextSize(18);

            llp2.setMargins(dp12, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin);
            if (DeviceUtil.hasJellyBeanMR1Api()) {
                llp2.setMarginStart(dp12);
            }

            holder.llAudioAttachment.setPadding(dp16, dp16, dp16, 0);
        } else {
            llp1.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            llp1.topMargin = 0;
            holder.tvAudioCount.setTextSize(11);

            llp2.setMargins(dp8, llp2.topMargin, llp2.rightMargin, llp2.bottomMargin);
            if (DeviceUtil.hasJellyBeanMR1Api()) {
                llp2.setMarginStart(dp8);
            }

            holder.llAudioAttachment.setPadding(dp16, dp16 / 4 * 3, dp16, 0);
        }
        holder.ivAudioCount.requestLayout();
    }

    private void updateCardForDoing(final BaseThingViewHolder holder, Thing thing) {
        if (App.getDoingThingId() == thing.getId()) {
            holder.flDoing.setVisibility(View.VISIBLE);
            holder.cv.post(new Runnable() {
                @Override
                public void run() {
                    InterceptTouchCardView.LayoutParams lp = (InterceptTouchCardView.LayoutParams)
                            holder.flDoing.getLayoutParams();
                    lp.width  = holder.cv.getWidth();
                    lp.height = holder.cv.getHeight();
                    Log.i(TAG, "setting doing cover for thing card, " +
                            "width[" + lp.width + ", height[" + lp.height + "]");
                    holder.flDoing.requestLayout();
                }
            });
        } else {
            holder.flDoing.setVisibility(View.GONE);
        }
    }

    private void setCardAppearance(final BaseThingViewHolder holder, int color, final boolean selected) {
//        if (color == ContextCompat.getColor(mContext, R.color.pine_green)) {
//            color = ContextCompat.getColor(mContext, R.color.aein_red);
//        }
        final CardView cv = holder.cv;
        int currentMode = getCurrentMode();
        if (currentMode == ModeManager.MOVING) {
            if (selected) {
                ObjectAnimator.ofFloat(cv, "scaleX", 1.11f).setDuration(96).start();
                ObjectAnimator.ofFloat(cv, "scaleY", 1.11f).setDuration(96).start();
                ObjectAnimator.ofFloat(cv, "CardElevation", 12 * mDensity).
                        setDuration(96).start();
                cv.setCardBackgroundColor(color);
            } else {
                cv.setScaleX(1.0f);
                cv.setScaleY(1.0f);
                cv.setCardElevation(2 * mDensity);
                cv.setCardBackgroundColor(
                        DisplayUtil.getLightColor(color, mContext));
            }
        } else if (currentMode == ModeManager.SELECTING) {
            if (selected) {
                cv.setCardBackgroundColor(color);
            } else {
                cv.setCardBackgroundColor(
                        DisplayUtil.getLightColor(color, mContext));
            }
        } else {
            cv.setCardBackgroundColor(color);
        }

        // Wrong warning here since FrameLayout#setForeground(Drawable) was provided on API 1
        if (mStyle == STYLE_BLACK) {
            holder.cv.setForeground(ContextCompat.getDrawable(
                    mContext, R.drawable.selectable_item_background));
        } else {
            holder.cv.setForeground(ContextCompat.getDrawable(
                    mContext, R.drawable.selectable_item_background_light));
        }
    }

    @Override
    public int getItemViewType(int position) {
        return getThings().get(position).getType();
    }

    @Override
    public int getItemCount() {
        return getThings().size();
    }

    public static class BaseThingViewHolder extends BaseViewHolder {

        public final InterceptTouchCardView cv;
        public final View vPaddingBottom;

        public final ImageView   ivStickyOngoing;
        public final FrameLayout flDoing;

        public final FrameLayout flImageAttachment;
        public final ImageView   ivImageAttachment;
        public final TextView    tvImageCount;
        public final ProgressBar pbLoading;
        public final View        vImageCover;

        public final TextView  tvTitle;
        public final ImageView ivPrivateThing;

        public final TextView     tvContent;
        public final RecyclerView rvChecklist;

        public final LinearLayout llAudioAttachment;
        public final ImageView    ivAudioCount;
        public final TextView     tvAudioCount;

        public final RelativeLayout rlReminder;
        public final View           vReminderSeparator;
        public final ImageView      ivReminder;
        public final TextView       tvReminderTime;

        public final RelativeLayout       rlHabit;
        public final View                 vHabitSeparator1;
        public final TextView             tvHabitSummary;
        public final TextView             tvHabitNextReminder;
        public final View                 vHabitSeparator2;
        public final LinearLayout         llHabitRecord;
        public final TextView             tvHabitLastFive;
        public final HabitRecordPresenter habitRecordPresenter;
        public final TextView             tvHabitFinishedThisT;

        public BaseThingViewHolder(View item) {
            super(item);

            cv             = f(R.id.cv_thing);
            vPaddingBottom = f(R.id.view_thing_padding_bottom);

            ivStickyOngoing = f(R.id.iv_thing_sticky_ongoing);
            flDoing         = f(R.id.fl_thing_doing_cover);

            flImageAttachment = f(R.id.fl_thing_image);
            ivImageAttachment = f(R.id.iv_thing_image);
            tvImageCount      = f(R.id.tv_thing_image_attachment_count);
            pbLoading         = f(R.id.pb_thing_image_attachment);
            vImageCover       = f(R.id.view_thing_image_cover);

            tvTitle        = f(R.id.tv_thing_title);
            ivPrivateThing = f(R.id.iv_private_thing);

            tvContent    = f(R.id.tv_thing_content);
            rvChecklist  = f(R.id.rv_check_list);

            llAudioAttachment = f(R.id.ll_thing_audio_attachment);
            ivAudioCount      = f(R.id.iv_thing_audio_attachment_count);
            tvAudioCount      = f(R.id.tv_thing_audio_attachment_count);

            rlReminder         = f(R.id.rl_thing_reminder);
            vReminderSeparator = f(R.id.view_reminder_separator);
            ivReminder         = f(R.id.iv_thing_reminder);
            tvReminderTime     = f(R.id.tv_thing_reminder_time);

            rlHabit              = f(R.id.rl_thing_habit);
            vHabitSeparator1     = f(R.id.view_habit_separator_1);
            tvHabitSummary       = f(R.id.tv_thing_habit_summary);
            tvHabitNextReminder  = f(R.id.tv_thing_habit_next_reminder);
            vHabitSeparator2     = f(R.id.view_habit_separator_2);
            llHabitRecord        = f(R.id.ll_thing_habit_record);
            tvHabitLastFive      = f(R.id.tv_thing_habit_last_five_record);
            habitRecordPresenter = new HabitRecordPresenter(new ImageView[] {
                    f(R.id.iv_thing_habit_record_1),
                    f(R.id.iv_thing_habit_record_2),
                    f(R.id.iv_thing_habit_record_3),
                    f(R.id.iv_thing_habit_record_4),
                    f(R.id.iv_thing_habit_record_5)
            });
            tvHabitFinishedThisT = f(R.id.tv_thing_habit_finished_this_t);

            int pbColor = ContextCompat.getColor(item.getContext(), R.color.app_accent);
            pbLoading.getIndeterminateDrawable().setColorFilter(pbColor, PorterDuff.Mode.SRC_IN);
        }

    }

}
