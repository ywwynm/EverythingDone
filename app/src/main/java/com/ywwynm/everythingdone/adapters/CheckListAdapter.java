package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.KeyboardUtil;

import java.util.List;

/**
 * Created by ywwynm on 2015/9/17.
 * Adapter for check list.
 */
public class CheckListAdapter extends RecyclerView.Adapter<BaseViewHolder> {

    public static final String TAG = "CheckListAdapter";

    public static final int TEXTVIEW            = 0;
    public static final int EDITTEXT_EDITABLE   = 1;
    public static final int EDITTEXT_UNEDITABLE = 2;

    private int mMaxItemCount;

    private boolean mWatchEditTextChange = true;
    private boolean mDragging = false;

    public interface ItemsChangeCallback {
        void onInsert(int position);
        void onRemove(int position, String item, int cursorPos);
    }
    private ItemsChangeCallback mItemsChangeCallback;

    public interface IvStateTouchCallback {
        void onTouch(int pos);
    }
    private IvStateTouchCallback mIvStateTouchCallback;

    public interface ActionCallback {
        void onAction(String before, String after);
    }
    private ActionCallback mActionCallback;

    private View.OnTouchListener mEtTouchListener;
    private View.OnClickListener mEtClickListener;
    private View.OnLongClickListener mEtLongClickListener;

    public interface TvItemClickCallback {
        void onItemClick(int itemPos);
    }
    private TvItemClickCallback mTvItemClickCallback;

    private Context mContext;

    private LayoutInflater mInflater;

    private int mType;

    private List<String> mItems;

    private boolean mShouldAutoLink;

    private @BaseThingsAdapter.Style int mStyle = BaseThingsAdapter.STYLE_WHITE;

    public CheckListAdapter(Context context, int type, List<String> items) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mType = type;
        mItems = items;

        removeItemsForTextView();
    }

    public void setDragging(boolean dragging) {
        mDragging = dragging;
    }

    public boolean isDragging() {
        return mDragging;
    }

    public void setIvStateTouchCallback(IvStateTouchCallback ivStateTouchCallback) {
        mIvStateTouchCallback = ivStateTouchCallback;
    }

    public void setActionCallback(ActionCallback actionCallback) {
        mActionCallback = actionCallback;
    }

    public void setEtTouchListener(View.OnTouchListener etTouchListener) {
        mEtTouchListener = etTouchListener;
    }

    public void setEtClickListener(View.OnClickListener etClickListener) {
        mEtClickListener = etClickListener;
    }

    public void setEtLongClickListener(View.OnLongClickListener etLongClickListener) {
        mEtLongClickListener = etLongClickListener;
    }

    public void setTvItemClickCallback(TvItemClickCallback tvItemClickCallback) {
        mTvItemClickCallback = tvItemClickCallback;
    }

    public void setMaxItemCount(int maxItemCount) {
        mMaxItemCount = maxItemCount;
    }

    public void setItems(List<String> items) {
        mItems = items;
        removeItemsForTextView();
        notifyDataSetChanged();
    }

    public List<String> getItems() {
        return mItems;
    }

    public void setItemsChangeCallback(ItemsChangeCallback itemsChangeCallback) {
        mItemsChangeCallback = itemsChangeCallback;
    }

    public void setShouldAutoLink(boolean shouldAutoLink) {
        mShouldAutoLink = shouldAutoLink;
    }

    public void setStyle(int style) {
        mStyle = style;
    }

    private void removeItemsForTextView() {
        if (mType == TEXTVIEW) {
            mItems.remove("2");
            mItems.remove("3");
            mItems.remove("4");
        }
    }

    @Override
    public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (mType == TEXTVIEW) {
            return new TextViewHolder(mInflater.inflate(R.layout.check_list_tv, parent, false));
        } else {
            return new EditTextHolder(mInflater.inflate(R.layout.check_list_et, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
        int white_76 = ContextCompat.getColor(mContext, R.color.white_76p);
        int white_50 = Color.parseColor("#80FFFFFF");
        float density = DisplayUtil.getScreenDensity(mContext);

        if (mType == TEXTVIEW) {
            TextViewHolder holder = (TextViewHolder) viewHolder;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.tv.getLayoutParams();
            if (mMaxItemCount != -1 && position == mMaxItemCount) {
                holder.iv.setVisibility(View.GONE);
                holder.tv.setTextSize(18);
                holder.tv.setText("...");
                holder.tv.setContentDescription(mContext.getString(R.string.cd_checklist_more_items));
                params.setMargins((int) (density * 8), 0, 0, params.bottomMargin);
                holder.itemView.setClickable(false);
                holder.itemView.setBackgroundResource(0);
                holder.itemView.setOnClickListener(null);
            } else {
                holder.iv.setVisibility(View.VISIBLE);
                int flag = holder.tv.getPaintFlags();
                String stateContent = mItems.get(position);
                char state = stateContent.charAt(0);
                if (state == '0') {
                    holder.iv.setImageResource(R.drawable.checklist_unchecked_card);
                    holder.iv.setContentDescription(
                            mContext.getString(R.string.cd_checklist_unfinished_item));
                    holder.tv.setTextColor(white_76);
                    holder.tv.setPaintFlags(flag & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else if (state == '1') {
                    holder.iv.setImageResource(R.drawable.checklist_checked_card);
                    holder.iv.setContentDescription(
                            mContext.getString(R.string.cd_checklist_finished_item));
                    holder.tv.setTextColor(white_50);
                    holder.tv.setPaintFlags(flag | Paint.STRIKE_THRU_TEXT_FLAG);
                }

                int size = mItems.size();
                if ((mMaxItemCount != -1 && size >= mMaxItemCount) || size >= mMaxItemCount) {
                    holder.tv.setTextSize(14);
                    params.setMargins(0, (int) (2 * density), 0, params.bottomMargin);
                } else {
                    float textSize = -4 * size / 7f + 130f / 7;
                    holder.tv.setTextSize(textSize);
                    float mt = - 2 * textSize / 3 + 34f / 3;
                    params.setMargins(0, (int) mt, 0, params.bottomMargin);
                }

                holder.tv.setText(stateContent.substring(1, stateContent.length()));
                params.setMargins(0, params.topMargin, 0, params.bottomMargin);

                setEventForTextViewItem(holder);
            }
        } else {
            final EditTextHolder holder = (EditTextHolder) viewHolder;
            holder.flSeparator.setVisibility(View.GONE);
            holder.ivState.setVisibility(View.VISIBLE);
            holder.ivState.setClickable(true);
            holder.ivDelete.setVisibility(View.INVISIBLE);

            holder.et.setEnabled(true);
            holder.et.setVisibility(View.VISIBLE);
            holder.et.getPaint().setTextSkewX(0);

            int flags = holder.et.getPaintFlags();
            holder.et.setPaintFlags(flags & ~Paint.STRIKE_THRU_TEXT_FLAG);

            holder.et.setTextSize(20);
            holder.et.setHint("");

            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                    holder.et.getLayoutParams();
            params.topMargin = (int) (density * 3);

            mWatchEditTextChange = false;
            String stateContent = mItems.get(position);
            char state = stateContent.charAt(0);
            if (state == '0') {
                if (!mDragging) {
                    holder.ivState.setImageResource(R.drawable.checklist_unchecked_detail);
                    holder.ivState.setContentDescription(
                            mContext.getString(R.string.cd_checklist_unfinished_item_clickable));
                } else {
                    holder.ivState.setImageResource(R.drawable.checklist_move_76);
                    holder.ivState.setContentDescription(
                            mContext.getString(R.string.cd_checklist_move));
                }
                holder.et.setTextColor(white_76);
                holder.et.setText(stateContent.substring(1, stateContent.length()));
            } else if (state == '1') {
                if (!mDragging) {
                    holder.ivState.setImageResource(R.drawable.checklist_checked_detail);
                    holder.ivState.setContentDescription(
                            mContext.getString(R.string.cd_checklist_finished_item_clickable));
                } else {
                    holder.ivState.setImageResource(R.drawable.checklist_move_50);
                    holder.ivState.setContentDescription(
                            mContext.getString(R.string.cd_checklist_move));
                }
                holder.et.setTextColor(white_50);
                holder.et.setPaintFlags(flags | Paint.STRIKE_THRU_TEXT_FLAG);
                holder.et.setText(stateContent.substring(1, stateContent.length()));
            } else if (state == '2') {
                params.topMargin = (int) (density * 4);
                holder.ivState.setImageResource(R.drawable.checklist_add);
                String newItem = mContext.getString(R.string.hint_new_item);
                holder.ivState.setContentDescription(newItem);
                holder.et.setHint(newItem);
                holder.et.setText("");
            } else if (state == '3') {
                holder.ivState.setVisibility(View.GONE);
                holder.ivDelete.setVisibility(View.GONE);
                holder.et.setVisibility(View.GONE);
                holder.flSeparator.setVisibility(View.VISIBLE);
            } else if (state == '4') {
                params.topMargin = (int) (density * 6);
                holder.ivState.setImageResource(R.drawable.checklist_finished);
                holder.ivState.setClickable(false);
                holder.ivState.setContentDescription(
                        mContext.getString(R.string.cd_checklist_finished_items));
                holder.et.setEnabled(false);
                holder.et.setText(mContext.getString(R.string.finished));
                holder.et.setTextColor(white_50);
                holder.et.setTextSize(16);
                holder.et.getPaint().setTextSkewX(-0.20f);
                holder.et.setContentDescription(
                        mContext.getString(R.string.cd_checklist_finished_items));
            }
            mWatchEditTextChange = true;
        }
    }

    private void setEventForTextViewItem(final TextViewHolder holder) {
        if ((mMaxItemCount != -1 && holder.getAdapterPosition() == mMaxItemCount)
                || mTvItemClickCallback == null) {
            holder.itemView.setClickable(false);
            // If don't write this line, we can still get a touch feedback for whole parent
            // RecyclerView even if its touch event should be intercepted by its parent.
            holder.itemView.setBackgroundResource(0);
        } else {
            holder.itemView.setClickable(true);
            holder.itemView.setBackgroundResource(R.drawable.selectable_item_background_light);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mTvItemClickCallback.onItemClick(holder.getAdapterPosition());
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        int size = mItems.size();
        if (mType == TEXTVIEW) {
            if (mMaxItemCount == -1) {
                return size;
            } else {
                return size <= mMaxItemCount ? size : mMaxItemCount + 1;
            }
        } else return size;
    }

    static class TextViewHolder extends BaseViewHolder {

        final ImageView iv;
        final TextView  tv;

        TextViewHolder(View itemView) {
            super(itemView);
            iv = f(R.id.iv_check_list_state);
            tv = f(R.id.tv_check_list);
        }
    }

    public class EditTextHolder extends BaseViewHolder {

        public final FrameLayout flSeparator;
        public final ImageView   ivState;
        public final EditText    et;
        public final ImageView   ivDelete;

        EditTextHolder(View itemView) {
            super(itemView);
            flSeparator = f(R.id.fl_check_list_separator);
            ivState     = f(R.id.iv_check_list_state);
            et          = f(R.id.et_check_list);
            ivDelete    = f(R.id.iv_check_list_delete);

            if (mType == EDITTEXT_EDITABLE) {
                DisplayUtil.setSelectionHandlersColor(et, ContextCompat.getColor(
                        mContext, R.color.app_accent));
                setupIvListeners();
                setupEtListeners();
            } else {
                et.setKeyListener(null);
            }

            if (mShouldAutoLink) {
                et.setAutoLinkMask(Linkify.ALL);
            } else {
                et.setAutoLinkMask(0);
            }
        }

        private void setupIvListeners() {
            ivState.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    final int pos = getAdapterPosition();
                    String item = mItems.get(pos);
                    if (event.getAction() == MotionEvent.ACTION_DOWN && mDragging
                            && !item.equals("2") && !item.equals("3") && !item.equals("4")) {
                        if (mIvStateTouchCallback != null) {
                            mIvStateTouchCallback.onTouch(pos);
                        }
                        return true;
                    }
                    return false;
                }
            });

            ivState.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String before = CheckListHelper.toCheckListStr(mItems);

                    int pos = getAdapterPosition(), posAfter;
                    KeyboardUtil.hideKeyboard(et);

                    String item = mItems.get(pos);
                    char state = item.charAt(0);
                    if (mDragging && state != '2') {
                        return;
                    }

                    if (state == '0') {
                        state = '1';
                        int size = mItems.size();
                        int firstFinishedItemIndex = CheckListHelper.getFirstFinishedItemIndex(mItems);
                        if (firstFinishedItemIndex == -1) {
                            mItems.add(size, "3");
                            mItems.add(size + 1, "4");
                            notifyItemInserted(size);
                            notifyItemInserted(size + 1);
                            posAfter = size + 1;
                        } else {
                            posAfter = firstFinishedItemIndex - 1;
                        }
                    } else if (state == '1') {
                        state = '0';
                        posAfter = 0;
                        if (CheckListHelper.onlyOneFinishedItem(mItems)) {
                            int size = mItems.size();
                            mItems.remove(size - 2);
                            notifyItemRemoved(size - 2);
                            mItems.remove(size - 2);
                            notifyItemRemoved(size - 2);
                            pos = size - 3;
                        }
                    } else {
                        insertItem(CheckListHelper.toCheckListStr(mItems), v, pos, "");
                        return;
                    }

                    String itemAfter = state + item.substring(1, item.length());

                    mWatchEditTextChange = false;
                    mItems.remove(pos);
                    notifyItemRemoved(pos);

                    mItems.add(posAfter, itemAfter);
                    notifyItemInserted(posAfter);
                    mWatchEditTextChange = true;

                    if (mActionCallback != null) {
                        mActionCallback.onAction(
                                before, CheckListHelper.toCheckListStr(mItems));
                    }
                }
            });

            ivDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeItem(v, getAdapterPosition(), true);
                }
            });
        }

        private void setupEtListeners() {
            if (mEtTouchListener != null) {
                et.setOnTouchListener(mEtTouchListener);
            }
            if (mEtClickListener != null) {
                et.setOnClickListener(mEtClickListener);
            }
            if (mEtLongClickListener != null) {
                et.setOnLongClickListener(mEtLongClickListener);
            }

            et.addTextChangedListener(new TextWatcher() {
                private String mBefore;
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    mBefore = CheckListHelper.toCheckListStr(mItems);
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (!mWatchEditTextChange) return;
                    int pos = getAdapterPosition();
                    char state = mItems.get(pos).charAt(0);
                    if (state == '0' || state == '1') {
                        mItems.set(pos, state + s.toString());
                    }
                    if (mActionCallback != null) {
                        mActionCallback.onAction(
                                mBefore, CheckListHelper.toCheckListStr(mItems));
                    }
                }
            });

            et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        int pos = getAdapterPosition();
                        if (mItems.get(pos).charAt(0) == '2') {
                            insertItem(CheckListHelper.toCheckListStr(mItems), v, pos, "");
                        } else {
                            v.post(new Runnable() {
                                @Override
                                public void run() {
                                    ivDelete.setClickable(true);
                                    ivDelete.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                    } else {
                        ivDelete.setClickable(false);
                        ivDelete.setVisibility(View.INVISIBLE);
                    }
                }
            });

            et.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    int action = event.getAction();
                    final int pos = getAdapterPosition();
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_ENTER) {
                            int cursorPos = et.getSelectionEnd();
                            int etLength = et.getText().length();
                            if (cursorPos == etLength) {
                                insertItem(CheckListHelper.toCheckListStr(mItems), v, pos, "");
                            } else {
                                String before = CheckListHelper.toCheckListStr(mItems);
                                String current = mItems.get(pos);
                                String newCurrent = current.substring(0, cursorPos + 1);
                                String next = current.substring(cursorPos + 1, etLength + 1);
                                mItems.set(pos, newCurrent);
                                notifyItemChanged(pos);
                                insertItem(before, v, pos, next);
                            }
                            return true;
                        } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                            if ((pos != 0 && et.getSelectionEnd() == 0)
                                    || (pos == 0 && mItems.get(0).length() == 1)) {
                                removeItem(v, pos, false);
                                return true;
                            }
                        }
                    }
                    return false;
                }
            });
        }

        /**
         * Inserting occurs in three ways: click ImageView "add", click EditText "new item"
         * and press enter when focus is on any EditTexts.
         */
        private void insertItem(String before, View v, final int pos, String preset) {
            final char state = mItems.get(pos).charAt(0);
            if (state == '2') {
                mItems.set(pos, "0");
                mItems.add(pos + 1, "2");
                notifyItemChanged(pos);
            } else {
                mItems.add(pos + 1, state + preset);
            }
            notifyItemInserted(pos + 1);
            v.clearFocus();
            if (mItemsChangeCallback != null) {
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        mItemsChangeCallback.onInsert(state == '2' ? pos : pos + 1);
                    }
                });
            }

            if (mActionCallback != null) {
                mActionCallback.onAction(
                        before, CheckListHelper.toCheckListStr(mItems));
            }
        }

        private void removeItem(View v, int pos, boolean deleteByClick) {
            String before = CheckListHelper.toCheckListStr(mItems);
            boolean justNotifyAll = false;
            String current = mItems.get(pos);
            final int posToFocus;
            if (pos != 0) {
                if (mItems.get(pos - 1).equals("4")) { // delete first finished item.
                    if (pos - 4 == -1) { // there is no unfinished item.
                        if (!deleteByClick) { // user used keyboard to delete this item.
                            if (current.length() != 1) {
                                // if the first finished item isn't empty, we should put them
                                // into the first item, which is "add item" now. So we need
                                // to add a new empty item at first.
                                mItems.add(0, "0");
                                pos++;
                                posToFocus = 0;
                                justNotifyAll = true;
                            } else {
                                // Otherwise, just delete this item and hide keyboard.
                                posToFocus = -1;
                            }
                        } else {
                            // user clicked "delete item", so we just delete this item
                            // and hide keyboard.
                            posToFocus = -1;
                        }
                    } else {
                        // there is at least 1 unfinished item, we need not to do special works.
                        posToFocus = pos - 4;
                    }
                } else {
                    // both this item and the item above this are in same state.
                    posToFocus = pos - 1;
                }
            } else {
                // delete first item
                posToFocus = -1;
            }

            final int cursorPos;
            if (pos == 0) {
                cursorPos = -1;
            } else {
                String itemToFocus = mItems.get(posToFocus == -1 ? 0 : posToFocus);
                int length = itemToFocus.length();
                cursorPos = length == 1 ? 0 : length - 1;
                if (!deleteByClick && posToFocus != -1) {
                    String append = current.substring(1, current.length());
                    mItems.set(posToFocus, itemToFocus + append);
                    justNotifyAll = true;
                }
            }

            mItems.remove(pos);
            if (justNotifyAll || pos == 0) {
                notifyDataSetChanged();
            } else {
                notifyItemRemoved(pos);
            }

            int size = mItems.size();
            if (mItems.get(size - 1).equals("4")) {
                mItems.remove("3");
                mItems.remove("4");
            }

            if (mItemsChangeCallback != null) {
                if (deleteByClick) {
                    mItemsChangeCallback.onRemove(pos, current, -1);
                } else {
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            mItemsChangeCallback.onRemove(posToFocus, null, cursorPos);
                        }
                    });
                }
            }

            if (mActionCallback != null) {
                mActionCallback.onAction(
                        before, CheckListHelper.toCheckListStr(mItems));
            }
        }
    }
}
