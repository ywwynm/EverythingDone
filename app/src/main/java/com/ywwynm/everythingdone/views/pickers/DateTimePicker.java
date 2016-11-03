package com.ywwynm.everythingdone.views.pickers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseViewHolder;
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import java.util.Calendar;

/**
 * Created by ywwynm on 2015/8/19.
 * Picker for picking time for quick remind in DetailActivity
 * and picking time type in DateTimeDialogFragment
 */
public class DateTimePicker extends PopupPicker {

    public static final String TAG = "DateTimePicker";

    private int mType;
    private int mAccentColor;
    private String[] mItems;
    private View.OnClickListener mOnClickListener;
    private DateTimePickerAdapter mAdapter;
    private int mPreviousIndex = 8;

    public DateTimePicker(Activity activity, View parent, int type, int accentColor) {
        super(activity, parent, type == Def.PickerType.AFTER_TIME ?
                R.style.QuickRemindPickerAnimation : R.style.TimeTypePickerAnimation);
        mType = type;
        mAccentColor = accentColor;
        ViewGroup.LayoutParams params = mRecyclerView.getLayoutParams();
        if (mType == Def.PickerType.AFTER_TIME) {
            params.width = (int) (mScreenDensity * 168);
            mItems = mActivity.getResources().getStringArray(R.array.quick_remind);
            if (BuildConfig.DEBUG) {
                mItems[0] = "6 " + activity.getString(R.string.second);
            }
        } else if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE) {
            params.width = (int) (mScreenDensity * 120);
            mItems = mActivity.getResources().getStringArray(R.array.time_type);
            if (LocaleUtil.isChinese(mActivity)) {
                mItems[2] = mActivity.getString(R.string.days);
            }
        } else {
            params.width = (int) (mScreenDensity * 98);
            String[] items = mActivity.getResources().getStringArray(R.array.time_type);
            mItems = new String[4];
            System.arraycopy(items, 2, mItems, 0, 4);
            if (LocaleUtil.isChinese(mActivity)) {
                mItems[0] = mActivity.getString(R.string.days);
            }
        }
        params.height = getRecyclerViewHeight();
        mRecyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(mActivity);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new DateTimePickerAdapter();
        mRecyclerView.setAdapter(mAdapter);

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                EdgeEffectUtil.forRecyclerView(recyclerView, mAccentColor);
            }
        });
    }

    public void setAccentColor(int accentColor) {
        mAccentColor = accentColor;
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void updateAnchor() {
        int index = getPickedIndex();
        if (index < 0 || index >= mItems.length) {
            return;
        }
        TextView anchor = (TextView) mAnchor;
        if (index != 9) {
            if (mType == Def.PickerType.AFTER_TIME) {
                String after = mActivity.getString(R.string.after);
                if (LocaleUtil.isChinese(mActivity)) {
                    anchor.setText(mItems[index]);
                    anchor.append(after);
                } else {
                    anchor.setText(after + " ");
                    anchor.append(mItems[index]);
                }
            } else {
                int offset = mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE ? 0 : 1;
                if (!LocaleUtil.isChinese(mActivity)) {
                    anchor.setText(mItems[index].toLowerCase());
                    if (offset == 0) {
                        anchor.append(" ");
                    }
                } else {
                    boolean b1 = mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE
                            && (index == 1 || index == 3 || index == 4);
                    boolean b2 = mType == Def.PickerType.TIME_TYPE_NO_HOUR_MINUTE
                            && (index == 1 || index == 2);
                    if (b1 || b2) {
                        anchor.setText(mActivity.getString(R.string.description_a) + mItems[index]);
                    } else {
                        anchor.setText(mItems[index]);
                    }
                }
                if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE) {
                    anchor.append(mActivity.getString(R.string.later));
                }
            }
        }
    }

    @Override
    public void show() {
        if (mAnchor == null) {
            return;
        }
        mRecyclerView.scrollToPosition(getPickedIndex());

        Point display = DisplayUtil.getDisplaySize(mActivity);
        int displayHeight = display.y;

        ViewGroup.LayoutParams params = mRecyclerView.getLayoutParams();
        int recyclerViewHeight = getRecyclerViewHeight();
        int orientation = mActivity.getResources().getConfiguration().orientation;
        boolean isInLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (isInLandscape) {
            Rect window = new Rect();
            mParent.getWindowVisibleDisplayFrame(window);
            if (displayHeight - window.bottom >= 96 * mScreenDensity) { // Keyboard is showing.
                if (window.bottom - mScreenDensity * 48 >= recyclerViewHeight) {
                    params.height = recyclerViewHeight;
                } else {
                    params.height = window.bottom - (int) (mScreenDensity * 48);
                }
            } else {
                params.height = recyclerViewHeight;
            }
        } else {
            params.height = recyclerViewHeight;
        }

        int[] pos = new int[2];
        View anchor = (View) mAnchor;
        anchor.getLocationInWindow(pos);
        if (mType == Def.PickerType.AFTER_TIME) {
            if (DisplayUtil.isInMultiWindow(mActivity)) {
                View decor = mActivity.getWindow().getDecorView();
                if (decor.getWidth() == display.x) {
                    // portrait multi-window
                    int[] location = new int[2];
                    decor.getLocationOnScreen(location);
                    if (location[1] == 0) {
                        displayHeight -= (display.y - decor.getHeight());
                    } else {
                        displayHeight -= location[1];
                    }
                }
            }
            mPopupWindow.showAtLocation(mParent, Gravity.BOTTOM | Gravity.START,
                    (int) (pos[0] - mScreenDensity * 16),
                    displayHeight - pos[1] - anchor.getHeight());
        } else {
            mPopupWindow.showAtLocation(mParent, Gravity.TOP | Gravity.START,
                    pos[0] - DisplayUtil.getStatusbarHeight(mActivity),
                    (int) (pos[1] - mScreenDensity * 56));
        }

    }

    public void setPickedListener(View.OnClickListener listener) {
        mOnClickListener = listener;
    }

    @Override
    public void pickForUI(int index) {
        mPreviousIndex = getPickedIndex();
        mAdapter.pick(index);
        updateAnchor();
    }

    public void pickPreviousForUI() {
        pickForUI(mPreviousIndex);
    }

    public int getPreviousIndex() {
        return mPreviousIndex;
    }

    @Override
    public int getPickedIndex() {
        return mAdapter.getPickedPosition();
    }

    public int[] getPickedTimeAfter() {
        int index = getPickedIndex();
        if (index == 9) return null;
        int[] time = new int[] { 1, 1, 1, 2, 1, 2, 1, 30, 15 };
        int[] type = new int[] { Calendar.YEAR, Calendar.MONTH, Calendar.WEEK_OF_YEAR,
                Calendar.DATE, Calendar.DATE, Calendar.HOUR_OF_DAY,
                Calendar.HOUR, Calendar.MINUTE, Calendar.MINUTE };
        if (BuildConfig.DEBUG) {
            time[0] = 6;
            type[0] = Calendar.SECOND;
        }
        return new int[] { type[index], time[index] };
    }

    public int getPickedTimeType() {
        if (mType == Def.PickerType.AFTER_TIME) return -1;
        int[] types = new int[] { Calendar.MINUTE, Calendar.HOUR_OF_DAY, Calendar.DATE,
                Calendar.WEEK_OF_YEAR, Calendar.MONTH, Calendar.YEAR };
        return mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE ?
                types[getPickedIndex()] : types[getPickedIndex() + 2];
    }

    private int getRecyclerViewHeight() {
        if (mType == Def.PickerType.AFTER_TIME) {
            return (int) (mScreenDensity * 228);
        } else {
            return (int) (mScreenDensity * 180);
        }
    }

    private class DateTimePickerAdapter extends SingleChoiceAdapter {

        private LayoutInflater mInflater;

        DateTimePickerAdapter() {
            mInflater = LayoutInflater.from(mActivity);
        }

        @Override
        public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new DateTimeViewHolder(mInflater.inflate(R.layout.datetime_picker_bt, parent, false));
        }

        @Override
        public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
            DateTimeViewHolder holder = (DateTimeViewHolder) viewHolder;
            int m8 = (int) (mScreenDensity * 8);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.bt.getLayoutParams();
            if (position == 0) {
                params.setMargins(0, m8, 0, 0);
            } else if (position == getItemCount() - 1) {
                params.setMargins(0, 0, 0, m8);
            } else {
                params.setMargins(0, 0, 0, 0);
            }
            holder.bt.setText(mItems[position]);
            if (mPickedPosition == position) {
                holder.bt.setTypeface(Typeface.DEFAULT_BOLD);
                holder.bt.setTextColor(mAccentColor);
                holder.bt.setClickable(position == 9);
            } else {
                holder.bt.setTypeface(Typeface.DEFAULT);
                holder.bt.setTextColor(ContextCompat.getColor(mActivity, R.color.black_54p));
                holder.bt.setClickable(true);
            }
        }

        @Override
        public int getItemCount() {
            if (mType == Def.PickerType.AFTER_TIME) {
                return 10;
            } else if (mType == Def.PickerType.TIME_TYPE_NO_HOUR_MINUTE) {
                return 4;
            } else if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE) {
                return 6;
            }
            return 0;
        }

        class DateTimeViewHolder extends BaseViewHolder {

            final Button bt;

            DateTimeViewHolder(View itemView) {
                super(itemView);
                bt = f(R.id.bt_pick_after_time);
                bt.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mPopupWindow.dismiss();
                        pickForUI(getAdapterPosition());
                        if (mOnClickListener != null) {
                            mOnClickListener.onClick(v);
                        }
                    }
                });
            }
        }
    }
}
