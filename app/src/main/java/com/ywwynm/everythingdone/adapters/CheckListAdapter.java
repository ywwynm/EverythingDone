package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
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
public class CheckListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final String TAG = "CheckListAdapter";

    public static final int TEXTVIEW            = 0;
    public static final int EDITTEXT_EDITABLE   = 1;
    public static final int EDITTEXT_UNEDITABLE = 2;

    private boolean mWatchEditTextChange = true;

    public interface ItemsChangeCallback {
        void onInsert(int position);
        void onRemove(int position, String item, int cursorPos);
    }
    private ItemsChangeCallback mItemsChangeCallback;
    private View.OnTouchListener mEtTouchListener;

    private Context mContext;

    private LayoutInflater mInflater;

    private int mType;

    private List<String> mItems;

    public CheckListAdapter(Context context, int type, List<String> items) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mType = type;
        mItems = items;

        removeItemsForTextView();
    }

    public CheckListAdapter(Context context, int type, List<String> items, View.OnTouchListener listener) {
        this(context, type, items);
        mEtTouchListener = listener;
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

    private void removeItemsForTextView() {
        if (mType == TEXTVIEW) {
            mItems.remove("2");
            mItems.remove("3");
            mItems.remove("4");
        }
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (mType == TEXTVIEW) {
            return new TextViewHolder(mInflater.inflate(R.layout.check_list_tv, parent, false));
        } else {
            return new EditTextHolder(mInflater.inflate(R.layout.check_list_et, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
        int white_76 = ContextCompat.getColor(mContext, R.color.white_76p);
        int white_50 = Color.parseColor("#80FFFFFF");
        float density = DisplayUtil.getScreenDensity(mContext);

        if (mType == TEXTVIEW) {
            TextViewHolder holder = (TextViewHolder) viewHolder;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.tv.getLayoutParams();
            if (position == 8) {
                holder.iv.setVisibility(View.GONE);
                holder.tv.setTextSize(18);
                holder.tv.setText("...");
                params.setMargins((int) (density * 8), 0, 0, params.bottomMargin);
            } else {
                holder.iv.setVisibility(View.VISIBLE);
                int flag = holder.tv.getPaintFlags();
                String stateContent = mItems.get(position);
                char state = stateContent.charAt(0);
                if (state == '0') {
                    holder.iv.setImageResource(R.mipmap.checklist_unchecked_card);
                    holder.tv.setTextColor(white_76);
                    holder.tv.setPaintFlags(flag & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else if (state == '1') {
                    holder.iv.setImageResource(R.mipmap.checklist_checked_card);
                    holder.tv.setTextColor(white_50);
                    holder.tv.setPaintFlags(flag | Paint.STRIKE_THRU_TEXT_FLAG);
                }

                int size = mItems.size();
                if (size >= 8) {
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
            }
        } else {
            EditTextHolder holder = (EditTextHolder) viewHolder;
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
            params.setMargins((int) (density * 8), (int) (density * 3), 0 , 0);

            mWatchEditTextChange = false;
            String stateContent = mItems.get(position);
            char state = stateContent.charAt(0);
            if (state == '0') {
                holder.ivState.setImageResource(R.mipmap.checklist_unchecked_detail);
                holder.et.setTextColor(white_76);
                holder.et.setText(stateContent.substring(1, stateContent.length()));
            } else if (state == '1') {
                holder.ivState.setImageResource(R.mipmap.checklist_checked_detail);
                holder.et.setTextColor(white_50);
                holder.et.setPaintFlags(flags | Paint.STRIKE_THRU_TEXT_FLAG);
                holder.et.setText(stateContent.substring(1, stateContent.length()));
            } else if (state == '2') {
                params.setMargins((int) (density * 8), (int) (density * 4), 0 , 0);
                holder.ivState.setImageResource(R.mipmap.checklist_add);
                holder.et.setHint(mContext.getString(R.string.hint_new_item));
                holder.et.setText("");
            } else if (state == '3') {
                holder.ivState.setVisibility(View.GONE);
                holder.ivDelete.setVisibility(View.GONE);
                holder.et.setVisibility(View.GONE);
                holder.flSeparator.setVisibility(View.VISIBLE);
            } else if (state == '4') {
                params.setMargins((int) (density * 8), (int) (density * 6), 0 , 0);
                holder.ivState.setImageResource(R.mipmap.checklist_finished);
                holder.ivState.setClickable(false);
                holder.et.setEnabled(false);
                holder.et.setText(mContext.getString(R.string.finished));
                holder.et.setTextColor(white_50);
                holder.et.setTextSize(16);
                holder.et.getPaint().setTextSkewX(-0.20f);
            }
            mWatchEditTextChange = true;
        }
    }

    @Override
    public int getItemCount() {
        int size = mItems.size();
        if (mType == TEXTVIEW) {
            return size <= 8 ? size : 9;
        } else return size;
    }

    public void addItem(int pos, String item) {
        if (CheckListHelper.getFirstFinishedItemIndex(mItems) == -1 && item.startsWith("1")) {
            mItems.add("3");
            mItems.add("4");
            notifyDataSetChanged();
        }
        mItems.add(pos, item);
        notifyItemInserted(pos);
    }

    class TextViewHolder extends RecyclerView.ViewHolder {

        final ImageView iv;
        final TextView  tv;

        public TextViewHolder(View itemView) {
            super(itemView);
            iv = (ImageView) itemView.findViewById(R.id.iv_check_list_state);
            tv = (TextView)  itemView.findViewById(R.id.tv_check_list);
        }
    }

    public class EditTextHolder extends RecyclerView.ViewHolder {

        public final FrameLayout flSeparator;
        public final ImageView   ivState;
        public final EditText    et;
        public final ImageView   ivDelete;

        public EditTextHolder(View itemView) {
            super(itemView);
            flSeparator = (FrameLayout) itemView.findViewById(R.id.fl_check_list_separator);
            ivState     = (ImageView)   itemView.findViewById(R.id.iv_check_list_state);
            et          = (EditText)    itemView.findViewById(R.id.et_check_list);
            ivDelete    = (ImageView)   itemView.findViewById(R.id.iv_check_list_delete);

            if (mType == EDITTEXT_EDITABLE) {
                DisplayUtil.setSelectionHandlersColor(et, ContextCompat.getColor(
                        mContext, R.color.app_accent));
                setupIvListeners();
                setupEtListeners();
            } else {
                et.setKeyListener(null);
            }
        }

        private void setupIvListeners() {
            ivState.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = getAdapterPosition(), posAfter;
                    KeyboardUtil.hideKeyboard(et);

                    String item = mItems.get(pos);
                    char state = item.charAt(0);
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
                        insertItem(v, pos, "");
                        return;
                    }

                    String itemAfter = state + item.substring(1, item.length());

                    mWatchEditTextChange = false;
                    mItems.remove(pos);
                    notifyItemRemoved(pos);

                    mItems.add(posAfter, itemAfter);
                    notifyItemInserted(posAfter);
                    mWatchEditTextChange = true;
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

            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
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
                }
            });

            et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        int pos = getAdapterPosition();
                        if (mItems.get(pos).charAt(0) == '2') {
                            insertItem(v, pos, "");
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
                                insertItem(v, pos, "");
                            } else {
                                String current = mItems.get(pos);
                                String newCurrent = current.substring(0, cursorPos + 1);
                                String next = current.substring(cursorPos + 1, etLength + 1);
                                mItems.set(pos, newCurrent);
                                notifyItemChanged(pos);
                                insertItem(v, pos, next);
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
        private void insertItem(View v, final int pos, String preset) {
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
        }

        private void removeItem(View v, int pos, boolean deleteByClick) {
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
        }
    }
}
