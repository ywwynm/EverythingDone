package com.ywwynm.everythingdone.adapters;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.bean.Habit;
import com.ywwynm.everythingdone.bean.Reminder;
import com.ywwynm.everythingdone.bean.Thing;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.ImageLoader;
import com.ywwynm.everythingdone.utils.VersionUtil;
import com.ywwynm.everythingdone.views.HabitRecordPresenter;
import com.ywwynm.everythingdone.views.InterceptTouchCardView;

import java.util.HashMap;
import java.util.List;

/**
 * Created by ywwynm on 2015/5/28.
 * Adapter for things.
 */
public class ThingsAdapter extends RecyclerView.Adapter<ThingsAdapter.ThingViewHolder> {

    public static final String TAG = "ThingsAdapter";

    private EverythingDoneApplication mApplication;

    private final float mScreenDensity;

    private ThingManager mThingManager;
    private ReminderDAO  mReminderDAO;
    private HabitDAO     mHabitDAO;

    private HashMap<Long, CheckListAdapter> mCheckListAdapters;

    private OnItemTouchedListener mOnItemTouchedListener;

    // decrease memory usage as much as possible.
    private View.OnTouchListener mOnTouchListener;

    private LayoutInflater mInflater;

    private boolean mShouldThingsAnimWhenAppearing = true;

    private Handler mAnimHandler;

    public ThingsAdapter(EverythingDoneApplication application, OnItemTouchedListener listener) {
        mApplication = application;
        mScreenDensity = DisplayUtil.getScreenDensity(mApplication);

        mThingManager = ThingManager.getInstance(application);
        mReminderDAO  = ReminderDAO.getInstance(application);
        mHabitDAO     = HabitDAO.getInstance(application);

        mCheckListAdapters = new HashMap<>();

        mInflater = LayoutInflater.from(mApplication);

        mOnItemTouchedListener = listener;
        mOnTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mOnItemTouchedListener.onItemTouch(v, event);
            }
        };

        mAnimHandler = new Handler();
    }

    public boolean isShouldThingsAnimWhenAppearing() {
        return mShouldThingsAnimWhenAppearing;
    }

    public void setShouldThingsAnimWhenAppearing(boolean shouldThingsAnimWhenAppearing) {
        mShouldThingsAnimWhenAppearing = shouldThingsAnimWhenAppearing;
    }

    @Override
    public ThingsAdapter.ThingViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ThingViewHolder(mInflater.inflate(R.layout.card_thing, parent, false));
    }

    @Override
    public void onBindViewHolder(ThingsAdapter.ThingViewHolder holder, int position) {
        Thing thing = mThingManager.getThings().get(position);

        distinguishHeaderAndOthers(thing.getType() == Thing.HEADER, holder.cv);
        setViewAppearance(holder, thing);
        setCardAppearance(holder, thing.getColor(),
                mThingManager.getThings().get(position).isSelected());

        if (mShouldThingsAnimWhenAppearing) {
            playAppearingAnimation(holder.cv, position);
        }
    }

    private void distinguishHeaderAndOthers(boolean header, CardView cv) {
        int mX = (int) (mScreenDensity * 4);
        if (VersionUtil.hasLollipopApi()) {
            mX = (int) (mScreenDensity * 6);
        }
        int mY = header ? 0 : mX;

        int height;
        if (header) {
            height = (int) (EverythingDoneApplication.isSearching ? mScreenDensity * 6 : mScreenDensity * 102);
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

    private void setViewAppearance(ThingViewHolder holder, Thing thing) {
        String title = thing.getTitle();
        if (!title.isEmpty()) {
            int p = (int) (mScreenDensity * 16);
            holder.tvTitle.setVisibility(View.VISIBLE);
            holder.tvTitle.setPadding(p, p, p, 0);
            holder.tvTitle.setText(title);
        } else {
            holder.tvTitle.setVisibility(View.GONE);
        }

        updateCardForContent(holder, thing);
        updateCardForReminder(holder, thing);
        updateCardForHabit(holder, thing);
        updateCardForAudioAttachment(holder, thing);
        updateCardForImageAttachment(holder, thing);

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

    private void updateCardForContent(ThingViewHolder holder, Thing thing) {
        int p = (int) (mScreenDensity * 16);
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
            } else {
                holder.tvContent.setVisibility(View.GONE);
                holder.rvChecklist.setVisibility(View.VISIBLE);

                long id = thing.getId();
                List<String> items = CheckListHelper.toCheckListItems(content, false);
                CheckListAdapter adapter = mCheckListAdapters.get(id);
                if (adapter == null) {
                    adapter = new CheckListAdapter(mApplication,
                            CheckListAdapter.TEXTVIEW, items);
                    mCheckListAdapters.put(id, adapter);
                } else {
                    adapter.setItems(items);
                }
                holder.rvChecklist.setAdapter(adapter);
                holder.rvChecklist.setLayoutManager(new LinearLayoutManager(mApplication));

                int rp = (int) (mScreenDensity * 6);
                holder.rvChecklist.setPaddingRelative(rp, p, p, 0);
            }
        } else {
            holder.tvContent.setVisibility(View.GONE);
            holder.rvChecklist.setVisibility(View.GONE);
        }
    }

    private void updateCardForReminder(ThingViewHolder holder, Thing thing) {
        int thingType = thing.getType();
        int thingState = thing.getState();
        Reminder reminder = mReminderDAO.getReminderById(thing.getId());
        if (reminder != null) {
            int state = reminder.getState();
            long notifyTime = reminder.getNotifyTime();

            int p = (int) (mScreenDensity * 16);
            holder.rlReminder.setVisibility(View.VISIBLE);
            holder.rlReminder.setPadding(p, p, p, 0);

            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                    holder.ivReminder.getLayoutParams();
            if (thingType == Thing.REMINDER) {
                params.setMargins(0, (int) (mScreenDensity * 2), 0, 0);
                holder.ivReminder.setImageResource(R.mipmap.card_reminder);
                holder.tvReminderTime.setTextSize(12);

                String timeStr = DateTimeUtil.getDateTimeStrAt(notifyTime, mApplication, false);
                if (timeStr.startsWith("on ")) {
                    timeStr = timeStr.substring(3, timeStr.length());
                }
                holder.tvReminderTime.setText(timeStr);
                if (thingState != Thing.UNDERWAY || state != Reminder.UNDERWAY) {
                    holder.tvReminderTime.append(", " + Reminder.getStateDescription(thingState, state, mApplication));
                }
            } else {
                params.setMargins(0, (int) (mScreenDensity * 1.6), 0, 0);
                holder.ivReminder.setImageResource(R.mipmap.card_goal);
                holder.tvReminderTime.setTextSize(16);

                if (thingState == Reminder.UNDERWAY && state == Reminder.UNDERWAY) {
                    holder.tvReminderTime.setText(DateTimeUtil.getDateTimeStrGoal(notifyTime, mApplication));
                } else {
                    holder.tvReminderTime.setText(Reminder.getStateDescription(thingState, state, mApplication));
                }
            }
        } else {
            holder.rlReminder.setVisibility(View.GONE);
        }
    }

    private void updateCardForHabit(ThingViewHolder holder, Thing thing) {
        Habit habit = mHabitDAO.getHabitById(thing.getId());
        if (habit != null) {
            int p = (int) (mScreenDensity * 16);
            holder.rlHabit.setVisibility(View.VISIBLE);
            holder.rlHabit.setPadding(p, p, p, 0);

            holder.tvHabitSummary.setText(habit.getSummary(mApplication));

            if (thing.getState() == Thing.UNDERWAY) {
                holder.tvHabitNextReminder.setVisibility(View.VISIBLE);
                holder.vHabitSeparator2.setVisibility(View.VISIBLE);
                holder.tvHabitLastFive.setVisibility(View.VISIBLE);
                holder.llHabitRecord.setVisibility(View.VISIBLE);
                holder.tvHabitFinishedThisT.setVisibility(View.VISIBLE);

                String next = mApplication.getString(R.string.habit_next_reminder);
                holder.tvHabitNextReminder.setText(
                        next + " " + habit.getNextReminderDescription(mApplication));

                String record = habit.getRecord();
                String lastFive;
                int len = record.length();
                if (len >= 5) {
                    lastFive = record.substring(len - 5, len);
                } else {
                    lastFive = record;
                    for (int i = 0; i < 5 - len; i++) {
                        lastFive += "?";
                    }
                }
                holder.habitRecordPresenter.setRecord(lastFive);

                holder.tvHabitFinishedThisT.setText(habit.getFinishedTimesThisTStr(mApplication));
            } else {
                holder.tvHabitNextReminder.setVisibility(View.GONE);
                holder.vHabitSeparator2.setVisibility(View.GONE);
                holder.tvHabitLastFive.setVisibility(View.GONE);
                holder.llHabitRecord.setVisibility(View.GONE);
                holder.tvHabitFinishedThisT.setVisibility(View.GONE);
            }
        } else {
            holder.rlHabit.setVisibility(View.GONE);
        }
    }

    private void updateCardForImageAttachment(ThingViewHolder holder, Thing thing) {
        String attachment = thing.getAttachment();
        String firstImageTypePathName = AttachmentHelper.getFirstImageTypePathName(attachment);
        if (firstImageTypePathName != null) {
            holder.flImageAttachment.setVisibility(View.VISIBLE);

            int imageW = DisplayUtil.getThingCardWidth(mApplication);
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
            if (!VersionUtil.hasLollipopApi()) {
                int m = (int) (mScreenDensity * -8);
                paramsLayout.setMargins(0, m, 0, 0);
            }

            // load bitmap in a new thread
            LruCache<String, Bitmap> cache = mApplication.getBitmapLruCache();
            String key = AttachmentHelper.generateKeyForCache(
                    firstImageTypePathName.substring(1, firstImageTypePathName.length()), imageW, imageH);
            Bitmap bitmap = cache.get(key);
            if (bitmap != null) {
                holder.pbLoading.setVisibility(View.GONE);
                holder.ivImageAttachment.setImageBitmap(bitmap);
            } else {
                int type = firstImageTypePathName.charAt(0) == '0' ? 0 : 1;
                new ImageLoader(type, cache, holder.ivImageAttachment, holder.pbLoading)
                        .execute(key, imageW, imageH);
            }

            // if thing has only an image/video, there should be no margins for ImageView
            if (holder.tvTitle.getVisibility() == View.GONE
                    && holder.tvContent.getVisibility() == View.GONE
                    && holder.rvChecklist.getVisibility() == View.GONE
                    && holder.llAudioAttachment.getVisibility() == View.GONE
                    && holder.rlReminder.getVisibility() == View.GONE) {
                holder.vPaddingBottom.setVisibility(View.GONE);
            } else {
                holder.vPaddingBottom.setVisibility(View.VISIBLE);
            }

            holder.tvImageCount.setText(AttachmentHelper.getImageAttachmentCountStr(attachment, mApplication));

            // when this card is unselected in selecting/moving mode, image should be covered
            if (mApplication.getModeManager().getCurrentMode() == ModeManager.NORMAL) {
                holder.vImageCover.setVisibility(View.GONE);
            } else {
                holder.vImageCover.setVisibility(thing.isSelected() ? View.GONE : View.VISIBLE);
            }
        } else {
            holder.vPaddingBottom.setVisibility(View.VISIBLE);
            holder.flImageAttachment.setVisibility(View.GONE);
        }
    }

    private void updateCardForAudioAttachment(ThingViewHolder holder, Thing thing) {
        String attachment = thing.getAttachment();
        String str = AttachmentHelper.getAudioAttachmentCountStr(attachment, mApplication);
        if (str == null) {
            holder.llAudioAttachment.setVisibility(View.GONE);
        } else {
            holder.llAudioAttachment.setVisibility(View.VISIBLE);
            int p = (int) (mScreenDensity * 16);
            holder.llAudioAttachment.setPadding(p, p / 4 * 3, p, 0);

            holder.tvAudioCount.setText(str);
        }
    }

    private void setCardAppearance(final ThingViewHolder holder, int color, final boolean selected) {
        final CardView cv = holder.cv;
        int currentMode = mApplication.getModeManager().getCurrentMode();
        if (currentMode == ModeManager.MOVING) {
            if (selected) {
                ObjectAnimator.ofFloat(cv, "scaleX", 1.11f).setDuration(96).start();
                ObjectAnimator.ofFloat(cv, "scaleY", 1.11f).setDuration(96).start();
                ObjectAnimator.ofFloat(cv, "CardElevation", 12 * mScreenDensity).
                        setDuration(96).start();
                cv.setCardBackgroundColor(color);
            } else {
                cv.setScaleX(1.0f);
                cv.setScaleY(1.0f);
                cv.setCardElevation(2 * mScreenDensity);
                cv.setCardBackgroundColor(
                        DisplayUtil.getLightColor(color, mApplication));
            }
        } else if (currentMode == ModeManager.SELECTING) {
            if (selected) {
                cv.setCardBackgroundColor(color);
            } else {
                cv.setCardBackgroundColor(
                        DisplayUtil.getLightColor(color, mApplication));
            }
        } else {
            cv.setCardBackgroundColor(color);
        }
    }

    private void playAppearingAnimation(final View v, int position) {
        v.setVisibility(View.INVISIBLE);
        if (getItemViewType(position) != Thing.HEADER) {
            final Animation animation = AnimationUtils.loadAnimation(
                    mApplication, R.anim.things_show);
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
    public int getItemViewType(int position) {
        return mThingManager.getThings().get(position).getType();
    }

    @Override
    public int getItemCount() {
        return mThingManager.getThings().size();
    }

    public interface OnItemTouchedListener {
        boolean onItemTouch(View v, MotionEvent event);
        void    onItemClick(View v, int position);
        boolean onItemLongClick(View v, int position);
    }

    public class ThingViewHolder extends RecyclerView.ViewHolder{

        final InterceptTouchCardView cv;
        final View vPaddingBottom;

        final FrameLayout flImageAttachment;
        final ImageView ivImageAttachment;
        final TextView tvImageCount;
        final ProgressBar pbLoading;
        final View vImageCover;

        final TextView tvTitle;
        final TextView tvContent;
        final RecyclerView rvChecklist;

        final LinearLayout llAudioAttachment;
        final TextView tvAudioCount;

        final RelativeLayout rlReminder;
        final View vReminderSeparator;
        final ImageView ivReminder;
        final TextView tvReminderTime;

        final RelativeLayout rlHabit;
        final View vHabitSeparator1;
        final TextView tvHabitSummary;
        final TextView tvHabitNextReminder;
        final View vHabitSeparator2;
        final LinearLayout llHabitRecord;
        final TextView tvHabitLastFive;
        final HabitRecordPresenter habitRecordPresenter;
        final TextView tvHabitFinishedThisT;

        public ThingViewHolder(View item) {
            super(item);

            cv             = (InterceptTouchCardView) item.findViewById(R.id.cv_thing);
            vPaddingBottom = item.findViewById(R.id.view_thing_padding_bottom);

            flImageAttachment = (FrameLayout) item.findViewById(R.id.fl_thing_image);
            ivImageAttachment = (ImageView) item.findViewById(R.id.iv_thing_image);
            tvImageCount      = (TextView) item.findViewById(R.id.tv_thing_image_attachment_count);
            pbLoading         = (ProgressBar) item.findViewById(R.id.pb_thing_image_attachment);
            vImageCover       = item.findViewById(R.id.view_thing_image_cover);

            tvTitle      = (TextView) item.findViewById(R.id.tv_thing_title);
            tvContent    = (TextView) item.findViewById(R.id.tv_thing_content);
            rvChecklist  = (RecyclerView) item.findViewById(R.id.rv_check_list);

            llAudioAttachment = (LinearLayout) item.findViewById(R.id.ll_thing_audio_attachment);
            tvAudioCount      = (TextView) item.findViewById(R.id.tv_thing_audio_attachment_count);

            rlReminder         = (RelativeLayout) item.findViewById(R.id.rl_thing_reminder);
            vReminderSeparator = item.findViewById(R.id.view_reminder_separator);
            ivReminder         = (ImageView) item.findViewById(R.id.iv_thing_reminder);
            tvReminderTime     = (TextView) item.findViewById(R.id.tv_thing_reminder_time);

            rlHabit              = (RelativeLayout) item.findViewById(R.id.rl_thing_habit);
            vHabitSeparator1     = item.findViewById(R.id.view_habit_separator_1);
            tvHabitSummary       = (TextView) item.findViewById(R.id.tv_thing_habit_summary);
            tvHabitNextReminder  = (TextView) item.findViewById(R.id.tv_thing_habit_next_reminder);
            vHabitSeparator2     = item.findViewById(R.id.view_habit_separator_2);
            llHabitRecord        = (LinearLayout) item.findViewById(R.id.ll_thing_habit_record);
            tvHabitLastFive      = (TextView) item.findViewById(R.id.tv_thing_habit_last_five_record);
            habitRecordPresenter = new HabitRecordPresenter(new ImageView[] {
                    (ImageView) item.findViewById(R.id.iv_thing_habit_record_1),
                    (ImageView) item.findViewById(R.id.iv_thing_habit_record_2),
                    (ImageView) item.findViewById(R.id.iv_thing_habit_record_3),
                    (ImageView) item.findViewById(R.id.iv_thing_habit_record_4),
                    (ImageView) item.findViewById(R.id.iv_thing_habit_record_5)
            });
            tvHabitFinishedThisT = (TextView) item.findViewById(R.id.tv_thing_habit_finished_this_t);

            int pbColor = ContextCompat.getColor(mApplication, R.color.app_accent);
            pbLoading.getIndeterminateDrawable().setColorFilter(pbColor, PorterDuff.Mode.SRC_IN);

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
