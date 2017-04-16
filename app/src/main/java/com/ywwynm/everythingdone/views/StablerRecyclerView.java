package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;

import com.ywwynm.everythingdone.helpers.PossibleMistakeHelper;

/**
 * Created by ywwynm on 2017/4/16.
 * A subclass of {@link RecyclerView} that ignores possible NPEs when detached from window, which
 * seems a bug of original version.
 */
public class StablerRecyclerView extends RecyclerView {

    public StablerRecyclerView(Context context) {
        super(context);
    }

    public StablerRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public StablerRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            // sometimes this will cause an NPE
            super.onDetachedFromWindow();
        } catch (Exception e) {
            e.printStackTrace();
            PossibleMistakeHelper.outputNewMistakeInBackground(e);
        }
    }
}
