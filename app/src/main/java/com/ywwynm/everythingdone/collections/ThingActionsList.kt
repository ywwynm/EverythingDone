package com.ywwynm.everythingdone.collections

import com.ywwynm.everythingdone.model.ThingAction
import java.util.ArrayList

/**
 * Created by ywwynm on 2016/7/1.
 * A collection class to provide some convenient methods for undo/redo
 * updating thing
 */
open class ThingActionsList(maxUndoTimes: Int) {

    private var mMaxUndoTimes: Int = maxUndoTimes

    private var mActions: ArrayList<ThingAction?>? = ArrayList(maxUndoTimes)

    private var mCurPosition: Int = -1

    private var mAddActionCallback: AddActionCallback? = null

    constructor() : this(DEFAULT_MAX_UNDO_TIMES)

    open fun setAddActionCallback(addActionCallback: AddActionCallback?) {
        mAddActionCallback = addActionCallback
    }

    open fun addAction(action: ThingAction?) {
        var size = mActions!!.size
        var i = mCurPosition + 1
        while (i < size) {
            mActions!!.removeAt(i)
            size--
        }

        mActions!!.add(action)
        mCurPosition++

        if (mCurPosition >= mMaxUndoTimes) {
            mActions!!.removeAt(0)
            mCurPosition--
        }

        if (mAddActionCallback != null) {
            mAddActionCallback!!.onAddAction()
        }
    }

    open fun canUndo(): Boolean {
        return mCurPosition >= 0
    }

    open fun canRedo(): Boolean {
        return mCurPosition < mActions!!.size - 1
    }

    open fun undo(): ThingAction? {
        mCurPosition--
        return mActions!![mCurPosition + 1]
    }

    open fun redo(): ThingAction? {
        mCurPosition++
        return mActions!![mCurPosition]
    }

    interface AddActionCallback {
        fun onAddAction()
    }

    companion object {
        private const val DEFAULT_MAX_UNDO_TIMES: Int = 60
    }
}
