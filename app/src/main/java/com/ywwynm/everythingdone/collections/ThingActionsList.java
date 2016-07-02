package com.ywwynm.everythingdone.collections;

import com.ywwynm.everythingdone.model.ThingAction;

import java.util.ArrayList;

/**
 * Created by ywwynm on 2016/7/1.
 * A collection class to provide some convenient methods for undo/redo
 * updating thing
 */
public class ThingActionsList {

    private static final int DEFAULT_MAX_UNDO_TIMES = 60;

    private int mMaxUndoTimes;

    private ArrayList<ThingAction> mActions;

    private int mCurPosition;

    private AddActionCallback mAddActionCallback;

    public ThingActionsList() {
        this(DEFAULT_MAX_UNDO_TIMES);
    }

    public ThingActionsList(int maxUndoTimes) {
        mMaxUndoTimes = maxUndoTimes;
        mActions = new ArrayList<>(maxUndoTimes);
        mCurPosition = -1;
    }

    public void setAddActionCallback(AddActionCallback addActionCallback) {
        mAddActionCallback = addActionCallback;
    }

    public void addAction(ThingAction action) {
        int size = mActions.size();
        for (int i = mCurPosition + 1; i < size; size--) {
            mActions.remove(i);
        }

        mActions.add(action);
        mCurPosition++;

        if (mCurPosition >= mMaxUndoTimes) {
            mActions.remove(0);
            mCurPosition--;
        }

        if (mAddActionCallback != null) {
            mAddActionCallback.onAddAction();
        }
    }

    public boolean canUndo() {
        return mCurPosition >= 0;
    }

    public boolean canRedo() {
        return mCurPosition < mActions.size() - 1;
    }

    public ThingAction undo() {
        mCurPosition--;
        return mActions.get(mCurPosition + 1);
    }

    public ThingAction redo() {
        mCurPosition++;
        return mActions.get(mCurPosition);
    }

    public interface AddActionCallback {
        void onAddAction();
    }

}
