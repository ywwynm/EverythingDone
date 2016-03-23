package com.ywwynm.everythingdone.fragments;

import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.view.Window;

/**
 * Created by ywwynm on 2015/9/29.
 * A subclass of {@link DialogFragment} without dialog title
 */
public abstract class NoTitleDialogFragment extends DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }
}
