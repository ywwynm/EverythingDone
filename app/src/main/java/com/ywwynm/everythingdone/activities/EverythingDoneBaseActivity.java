package com.ywwynm.everythingdone.activities;

import android.os.Bundle;
import android.support.annotation.LayoutRes;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

/**
 * Created by ywwynm on 2015/6/4.
 * A base Activity class to reduce same codes in different subclasses.
 */
public abstract class EverythingDoneBaseActivity extends AppCompatActivity {

    public static final String TAG = "EverythingDoneBaseActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getLayoutResource());

        beforeInit();
        init();
    }

    protected abstract @LayoutRes int getLayoutResource();

    protected void beforeInit() {}

    protected void init() {
        initMembers();
        findViews();
        initUI();
        setActionbar();
        setEvents();
    }

    protected abstract void initMembers();

    protected abstract void findViews();

    protected abstract void initUI();

    protected abstract void setActionbar();

    protected abstract void setEvents();

}
