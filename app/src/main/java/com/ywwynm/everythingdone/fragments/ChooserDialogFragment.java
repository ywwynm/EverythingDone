package com.ywwynm.everythingdone.fragments;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseViewHolder;
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

import java.util.List;

/**
 * Created by ywwynm on 2016/3/11.
 * chooser dialog fragment
 */
public class ChooserDialogFragment extends BaseDialogFragment {

    public static final String TAG = "ChooserDialogFragment";

    private int mAccentColor;
    private String mTitle;
    private List<String> mItems;
    private int mInitialIndex;

    private View.OnClickListener mConfirmListener;
    private View.OnClickListener mMoreListener;

    private TextView mTvTitle;
    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLlm;
    private ChooserFragmentAdapter mAdapter;
    private TextView mTvConfirmAsBt;
    private TextView mTvCancelAsBt;
    private TextView mTvMoreAsBt;

    private boolean mShouldOverScroll = false;
    private boolean mShouldShowMore   = true;

    private View mSeparator1;
    private View mSeparator2;

    private View.OnClickListener mOnItemClickListener;

    public interface OnDismissListener {
        void onDismiss();
    }
    private OnDismissListener mOnDismissListener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        mTvTitle       = f(R.id.tv_title_fragment_chooser);
        mRecyclerView  = f(R.id.rv_fragment_chooser);
        mTvConfirmAsBt = f(R.id.tv_confirm_as_bt_fragment_chooser);
        mTvCancelAsBt  = f(R.id.tv_cancel_as_bt_fragment_chooser);
        mTvMoreAsBt    = f(R.id.tv_more_as_bt_fragment_chooser);

        mSeparator1 = f(R.id.view_separator_1);
        mSeparator2 = f(R.id.view_separator_2);

        mLlm = new LinearLayoutManager(getActivity());

        initUI();
        setEvents();

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_chooser;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mOnDismissListener != null) {
            mOnDismissListener.onDismiss();
        }
        super.onDismiss(dialog);
    }

    private void initUI() {
        mTvTitle.setTextColor(mAccentColor);
        mTvTitle.setText(mTitle);

        mTvConfirmAsBt.setTextColor(mAccentColor);

        if (!mShouldShowMore) {
            mTvMoreAsBt.setVisibility(View.GONE);
        } else {
            mTvMoreAsBt.setTextColor(mAccentColor);
        }

        if (mItems.size() > 9) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
                    mRecyclerView.getLayoutParams();
            params.height = (int) (40 * 8.5 * DisplayUtil.getScreenDensity(App.getApp()));
            mRecyclerView.requestLayout();
        } else {
            mSeparator1.setVisibility(View.INVISIBLE);
            mSeparator2.setVisibility(View.INVISIBLE);
        }

        if (mShouldOverScroll) {
            mRecyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        } else {
            mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        mAdapter = new ChooserFragmentAdapter(getActivity(), mItems);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setLayoutManager(mLlm);
        mAdapter.pick(mInitialIndex);

        if (mItems.size() > 9) {
            mRecyclerView.post(() -> {
                mRecyclerView.scrollToPosition(mInitialIndex);
                updateSeparators();
            });
        }
    }

    private void setEvents() {
        if (mItems.size() > 9) {
            mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    updateSeparators();
                }
            });
        }
        mTvCancelAsBt.setOnClickListener(v -> dismiss());
        mTvConfirmAsBt.setOnClickListener(v -> {
            if (mConfirmListener != null) {
                mConfirmListener.onClick(v);
            }
            dismiss();
        });
        if (mShouldShowMore) {
            mTvMoreAsBt.setOnClickListener(v -> {
                if (mMoreListener != null) {
                    mMoreListener.onClick(v);
                }
                dismiss();
            });
        }

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                EdgeEffectUtil.forRecyclerView(mRecyclerView, mAccentColor);
            }
        });
    }

    public int getPickedIndex() {
        return mAdapter.getPickedPosition();
    }

    public void setAccentColor(int accentColor) {
        mAccentColor = accentColor;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public void setItems(List<String> items) {
        mItems = items;
    }

    public void setShouldOverScroll(boolean shouldOverScroll) {
        mShouldOverScroll = shouldOverScroll;
    }

    public void setShouldShowMore(boolean shouldShowMore) {
        mShouldShowMore = shouldShowMore;
    }

    public void setInitialIndex(int initialIndex) {
        mInitialIndex = initialIndex;
    }

    public void setConfirmListener(View.OnClickListener confirmListener) {
        mConfirmListener = confirmListener;
    }

    public void setMoreListener(View.OnClickListener moreListener) {
        mMoreListener = moreListener;
    }

    public void setOnItemClickListener(View.OnClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
    }

    public void setOnDismissListener(OnDismissListener onDismissListener) {
        mOnDismissListener = onDismissListener;
    }

    private void updateSeparators() {
        if (mLlm.findFirstCompletelyVisibleItemPosition() == 0) {
            mSeparator1.setVisibility(View.INVISIBLE);
            mSeparator2.setVisibility(View.VISIBLE);
        } else if (mLlm.findLastCompletelyVisibleItemPosition() == mItems.size() - 1) {
            mSeparator1.setVisibility(View.VISIBLE);
            mSeparator2.setVisibility(View.INVISIBLE);
        } else {
            mSeparator1.setVisibility(View.VISIBLE);
            mSeparator2.setVisibility(View.VISIBLE);
        }
    }

    class ChooserFragmentAdapter extends SingleChoiceAdapter {

        public static final String TAG = "ChooserFragmentAdapter";

        private LayoutInflater mInflater;
        private List<String> mItems;

        public ChooserFragmentAdapter(Context context, List<String> items) {
            mInflater = LayoutInflater.from(context);
            mItems = items;
        }

        /**
         * Don't know why should we write this method. However, if we remove it and called
         * {@link SingleChoiceAdapter#getPickedPosition()} directly, there may be a crash.
         * For example, {@link ChooserDialogFragment#getPickedIndex()} called in SettingsActivity
         * will always return -1, which is very strange. I think this is caused by Jack compiler.
         * Anyway let's make it work again at first.
         */
        @Override
        public int getPickedPosition() {
            return mPickedPosition;
        }

        @Override
        public void pick(int position) {
            notifyItemChanged(mPickedPosition);
            mPickedPosition = position;
        }

        @Override
        public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ChoiceHolder(mInflater.inflate(R.layout.rv_fragment_chooser, parent, false));
        }

        @Override
        public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
            ChoiceHolder holder = (ChoiceHolder) viewHolder;
            holder.tv.setText(mItems.get(position));
            Context context = holder.tv.getContext();
            int uncheckedColor = ContextCompat.getColor(context, R.color.black_54);
            Drawable d;
            if (mPickedPosition == position) { // -15310698
                d = ContextCompat.getDrawable(context, R.drawable.ic_radiobutton_checked);
                d.mutate().setColorFilter(mAccentColor, PorterDuff.Mode.SRC_ATOP);
                Log.i(TAG, "mAccentColor: " + mAccentColor);
            } else {
                d = ContextCompat.getDrawable(context, R.drawable.ic_radiobutton_unchecked);
                d.mutate().setColorFilter(uncheckedColor, PorterDuff.Mode.SRC_ATOP);
            }
            holder.tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class ChoiceHolder extends BaseViewHolder {

            final TextView tv;

            public ChoiceHolder(View itemView) {
                super(itemView);

                tv = f(R.id.tv_rv_chooser_fragment);

                tv.setOnClickListener(view -> {
                    pick(getAdapterPosition());
                    notifyItemChanged(mPickedPosition);
                    if (mOnItemClickListener != null) {
                        mOnItemClickListener.onClick(view);
                    }
                });
            }
        }
    }
}
