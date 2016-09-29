package com.ywwynm.everythingdone.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.KeyboardUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by ywwynm on 2016/1/27.
 * Time of day recurrence adapter
 */
public class TimeOfDayRecAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    public static final String TAG = "TimeOfDayRecAdapter";

    private static final int EDITTEXT = 0;
    private static final int TEXTVIEW = 1;

    private final int[] mIcons = { R.drawable.ic_reminder_1, R.drawable.ic_reminder_2,
            R.drawable.ic_reminder_3, R.drawable.ic_reminder_4 };

    private Context mContext;
    private LayoutInflater mInflater;

    private int mAccentColor;
    private int black_26p;
    private int black_54p;

    private List<Integer> mItems;

    public interface OnItemChangeCallback {
        void onItemInserted();
        void onItemRemoved();
    }

    private OnItemChangeCallback mOnItemChangeCallback;

    public void setOnItemChangeCallback(OnItemChangeCallback onItemChangeCallback) {
        mOnItemChangeCallback = onItemChangeCallback;
    }

    public TimeOfDayRecAdapter(Context context, int accentColor) {
        mContext = context;
        mInflater = LayoutInflater.from(context);

        mAccentColor = accentColor;
        black_54p = ContextCompat.getColor(mContext, R.color.black_54p);
        black_26p = ContextCompat.getColor(mContext, R.color.black_26p);
    }

    public void setItems(List<Integer> items) {
        mItems = items;
        if (mItems.size() < 7) {
            mItems.add(96);
        }
    }

    public List<Integer> getFinalItems() {
        List<Integer> items = new ArrayList<>();
        items.addAll(mItems);
        items.remove(Integer.valueOf(96));
        List<String> strs = new ArrayList<>();
        for (int i = 0; i < items.size(); i += 2) {
            String hour = String.valueOf(items.get(i));
            if (hour.length() == 1) {
                hour = "0" + hour;
            }
            String minute = String.valueOf(items.get(i + 1));
            if (minute.length() == 1) {
                minute = "0" + minute;
            }
            strs.add(hour + ":" + minute);
        }
        Collections.sort(strs);
        items.clear();
        for (String str : strs) {
            String[] times = str.split(":");
            int hour = Integer.parseInt(times[0]);
            int minute = Integer.parseInt(times[1]);
            items.add(hour);
            items.add(minute);
        }
        return items;
    }

    @Override
    public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TEXTVIEW) {
            return new TextViewHolder(mInflater.inflate(R.layout.time_of_day_rec_tv, parent, false));
        } else {
            return new EditTextHolder(mInflater.inflate(R.layout.time_of_day_rec_et, parent, false));
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
        if (getItemViewType(position) == EDITTEXT) {
            EditTextHolder holder = (EditTextHolder) viewHolder;
            DisplayUtil.tintView(holder.etHour, black_26p);
            DisplayUtil.tintView(holder.etMinute, black_26p);
            holder.ivReminder.setImageResource(mIcons[position]);
            holder.ivReminder.setContentDescription(
                    mContext.getString(R.string.cd_reminder_time) + (position + 1));
            int hour = mItems.get(2 * position);
            if (hour == -1) {
                holder.etHour.setText("");
                holder.etMinute.setText("");
            } else {
                holder.etHour.setText("" + hour);
                int minute = mItems.get(2 * position + 1);
                if (minute < 10) {
                    holder.etMinute.setText("0" + minute);
                } else {
                    holder.etMinute.setText("" + minute);
                }
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        int item = mItems.get(2 * position);
        if (item == 96) {
            return TEXTVIEW;
        } else return EDITTEXT;
    }

    public int getTimeCount() {
        final int size = mItems.size();
        return size == 8 ? 4 : (size - 1) / 2;
    }

    @Override
    public int getItemCount() {
        final int size = mItems.size();
        if (size % 2 == 0) {
            return size / 2;
        } else {
            return size / 2 + 1;
        }
    }

    private class EditTextHolder extends BaseViewHolder {

        ImageView ivReminder;
        EditText  etHour;
        EditText  etMinute;
        ImageView ivDelete;

        EditTextHolder(View itemView) {
            super(itemView);

            ivReminder = f(R.id.iv_reminder_rec_day);
            etHour     = f(R.id.et_hour_rec_day);
            etMinute   = f(R.id.et_minute_rec_day);
            ivDelete   = f(R.id.iv_delete_reminder_as_bt_rec_day);

            DisplayUtil.setSelectionHandlersColor(etHour, mAccentColor);
            DisplayUtil.setSelectionHandlersColor(etMinute, mAccentColor);

            View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        DisplayUtil.tintView(v, mAccentColor);
                        ((EditText) v).setTextColor(mAccentColor);
                        ((EditText) v).setHighlightColor(
                                DisplayUtil.getLightColor(mAccentColor, mContext));
                    } else {
                        DisplayUtil.tintView(v, black_26p);
                        ((EditText) v).setTextColor(black_54p);
                        if (v.equals(etHour)) {
                            DateTimeUtil.limitHourForEditText(etHour);
                        } else {
                            DateTimeUtil.formatLimitMinuteForEditText((EditText) v);
                        }
                    }
                }
            };
            etHour.setOnFocusChangeListener(focusChangeListener);
            etMinute.setOnFocusChangeListener(focusChangeListener);

            etHour.addTextChangedListener(new TimeTextWatcher(TimeTextWatcher.HOUR));
            etMinute.addTextChangedListener(new TimeTextWatcher(TimeTextWatcher.MINUTE));

            etHour.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_NEXT) {
                        etMinute.requestFocus();
                        return true;
                    }
                    return false;
                }
            });
            etMinute.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        KeyboardUtil.hideKeyboard(v);
                        v.clearFocus();
                        return true;
                    }
                    return false;
                }
            });

            ivDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = getAdapterPosition();
                    mItems.remove(2 * pos);
                    mItems.remove(2 * pos);
                    final int size = mItems.size();
                    if (size < 7 && mItems.get(size - 1) != 96) {
                        mItems.add(96);
                    }
                    notifyItemRemoved(pos);
                    notifyItemRangeChanged(pos, (size + 1) / 2);
                    if (mOnItemChangeCallback != null) {
                        mOnItemChangeCallback.onItemRemoved();
                    }
                }
            });
        }

        class TimeTextWatcher implements TextWatcher {

            static final int HOUR   = 0;
            static final int MINUTE = 1;

            private int mType;

            TimeTextWatcher(int type) {
                mType = type;
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                int position = getAdapterPosition();
                int pos = mType == HOUR ? position * 2 : position * 2 + 1;
                String numStr = s.toString();
                if (numStr.isEmpty()) {
                    mItems.set(pos, -1);
                } else {
                    mItems.set(pos, Integer.valueOf(numStr));
                }
            }
        }
    }

    private class TextViewHolder extends BaseViewHolder {

        TextView tvNewReminder;

        TextViewHolder(View itemView) {
            super(itemView);

            tvNewReminder = f(R.id.tv_new_reminder_as_bt_rec_day);

            tvNewReminder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int size = mItems.size(), pos = getAdapterPosition();
                    mItems.set(size - 1, -1);
                    mItems.add(-1);
                    notifyItemChanged(pos);
                    if (size < 7) {
                        mItems.add(96);
                        notifyItemInserted(pos + 1);
                    }
                    if (mOnItemChangeCallback != null) {
                        mOnItemChangeCallback.onItemInserted();
                    }
                }
            });
        }
    }

}
