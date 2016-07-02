package com.ywwynm.everythingdone.model;

import android.os.Bundle;
import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by ywwynm on 2016/7/1.
 * Thing update action model
 */
public class ThingAction {

    public static final int UPDATE_TITLE             = 0;
    public static final int UPDATE_CONTENT           = 1;
    public static final int TOGGLE_CHECKLIST         = 2;
    public static final int UPDATE_CHECKLIST         = 3;
    public static final int MOVE_CHECKLIST           = 4;
    public static final int UPDATE_COLOR             = 5;
    public static final int ADD_ATTACHMENT           = 6;
    public static final int DELETE_ATTACHMENT        = 7;
    public static final int MOVE_ATTACHMENT          = 8;
    public static final int TOGGLE_REMINDER_OR_HABIT = 9;
    public static final int UPDATE_REMINDER_OR_HABIT = 10;
    public static final int TOGGLE_PRIVATE           = 11;

    @IntDef({UPDATE_TITLE, UPDATE_CONTENT,
            TOGGLE_CHECKLIST, UPDATE_CHECKLIST, MOVE_CHECKLIST,
            UPDATE_COLOR,
            ADD_ATTACHMENT, DELETE_ATTACHMENT, MOVE_ATTACHMENT,
            TOGGLE_REMINDER_OR_HABIT, UPDATE_REMINDER_OR_HABIT,
            TOGGLE_PRIVATE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    public static final String KEY_ATTACHMENT_TYPE   = "attachment_type";
    public static final String KEY_CHECKBOX_STATE    = "checkbox_state";
    public static final String KEY_CURSOR_POS_BEFORE = "cursor_pos_before";
    public static final String KEY_CURSOR_POS_AFTER  = "cursor_pos_after";
    public static final String KEY_PICKED_BEFORE     = "picked_before";
    public static final String KEY_PICKED_AFTER      = "picked_after";

    private @Type int mType;

    private Object mBefore;

    private Object mAfter;

    private Bundle mExtras;

    public ThingAction(@Type int type, Object before, Object after) {
        mType   = type;
        mBefore = before;
        mAfter  = after;
        mExtras = new Bundle();
    }

    public int getType() {
        return mType;
    }

    public Object getBefore() {
        return mBefore;
    }

    public Object getAfter() {
        return mAfter;
    }

    public Bundle getExtras() {
        return mExtras;
    }
}
