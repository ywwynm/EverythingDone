package com.ywwynm.everythingdone.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.RadioChooserAdapter;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2016/3/11.
 * chooser dialog fragment
 */
public class ChooserDialogFragment extends BaseDialogFragment {

    public static final String TAG = "ChooserDialogFragment";

    private int mAccentColor;
    private String mTitle;
    private String mConfirmText;
    private List<String> mItems;
    private int mInitialIndex;

    private View.OnClickListener mConfirmListener;
    private View.OnClickListener mMoreListener;

    private TextView mTvTitle;
    private RecyclerView mRecyclerView;
    private RadioChooserAdapter mAdapter;
    private TextView mTvConfirmAsBt;
    private TextView mTvCancelAsBt;
    private TextView mTvMoreAsBt;

    private boolean mShouldOverScroll          = false;
    private boolean mShouldShowMore            = true;
    private boolean mShouldDismissAfterConfirm = true;

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

        if (mItems == null) {
            mItems = new ArrayList<>(1);
        }

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

        mConfirmListener     = null;
        mOnDismissListener   = null;
        mMoreListener        = null;
        mOnItemClickListener = null;

        super.onDismiss(dialog);
    }

    private void initUI() {
        mTvTitle.setText(mTitle);
        if (mConfirmText != null) {
            mTvConfirmAsBt.setText(mConfirmText);
        }
        // Phase 8: gradient text fill via TextPaint.setShader when an accent
        // ThingBackground was supplied; falls back to plain setTextColor with
        // the legacy int accent otherwise.
        applyAccent(mTvTitle);
        applyAccent(mTvConfirmAsBt);

        if (!mShouldShowMore) {
            mTvMoreAsBt.setVisibility(View.GONE);
        } else {
            applyAccent(mTvMoreAsBt);
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

        mAdapter = new RadioChooserAdapter(getActivity(), mItems, mAccentColor);
        // Phase 8: propagate the gradient accent to the radio list so the
        // picked row's text renders gradient too (rather than collapsing to
        // its representative int).
        if (mAccentBackground != null) {
            mAdapter.setAccentBackground(mAccentBackground);
        }
        mAdapter.setOnItemClickListener(mOnItemClickListener);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mAdapter.pick(mInitialIndex);

        if (mItems.size() > 9) {
            mRecyclerView.post(new Runnable() {
                @Override
                public void run() {
                    mRecyclerView.scrollToPosition(mInitialIndex);
                    updateSeparators();
                }
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
        mTvCancelAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        mTvConfirmAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConfirmListener != null) {
                    mConfirmListener.onClick(v);
                }
                if (mShouldDismissAfterConfirm) {
                    dismiss();
                }
            }
        });
        if (mShouldShowMore) {
            mTvMoreAsBt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mMoreListener != null) {
                        mMoreListener.onClick(v);
                    }
                }
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

    public void notifyDataSetChanged() {
        mAdapter.notifyDataSetChanged();
    }

    public void pick(int position) {
        mAdapter.pick(position);
    }

    public int getPickedIndex() {
        return mAdapter.getPickedPosition();
    }

    /** Phase 8: ThingBackground-typed accent so dialog title / buttons can render gradient text. */
    private com.ywwynm.everythingdone.model.ThingBackground mAccentBackground;

    public void setAccentBackground(com.ywwynm.everythingdone.model.ThingBackground bg) {
        mAccentBackground = bg;
        if (bg != null) mAccentColor = bg.representativeColor();
    }

    private void applyAccent(android.widget.TextView tv) {
        if (mAccentBackground != null) {
            com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                    tv, mAccentBackground);
        } else {
            tv.setTextColor(mAccentColor);
        }
    }

    public void setAccentColor(int accentColor) {
        mAccentBackground = null;
        mAccentColor = accentColor;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public void setConfirmText(String confirmText) {
        mConfirmText = confirmText;
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

    public void setShouldDismissAfterConfirm(boolean shouldDismissAfterConfirm) {
        mShouldDismissAfterConfirm = shouldDismissAfterConfirm;
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
        if (!mRecyclerView.canScrollVertically(-1)) {
            mSeparator1.setVisibility(View.INVISIBLE);
            mSeparator2.setVisibility(View.VISIBLE);
        } else if (!mRecyclerView.canScrollVertically(1)) {
            mSeparator1.setVisibility(View.VISIBLE);
            mSeparator2.setVisibility(View.INVISIBLE);
        } else {
            mSeparator1.setVisibility(View.VISIBLE);
            mSeparator2.setVisibility(View.VISIBLE);
        }
    }
}
