/*
Changed by ywwynm

Copyright 2016 Matthew Precious

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License
 */

package com.mattprecious.swirl;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.mattprecious.swirl.R;

public final class SwirlView extends ImageView {
    // Keep in sync with attrs.
    public enum State {
        OFF,
        ON,
        ERROR,
    }

    private State state = State.OFF;

    public SwirlView(Context context) {
        this(context, null);
    }

    public SwirlView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.swirl_Swirl);
        int state = a.getInteger(R.styleable.swirl_Swirl_swirl_state, -1);
        if (state != -1) {
            setState(State.values()[state], false);
        }
        a.recycle();
    }

    public void setState(State state) {
        setState(state, true);
    }

    public State getState() {
        return state;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void setState(State state, boolean animate) {
        if (state == this.state) return;

        int resId = getDrawable(this.state, state, animate);
        if (resId == 0) {
            setImageResource(resId);
        } else {
            Drawable icon = getContext().getDrawable(resId);
            setImageDrawable(icon);

            if (icon instanceof AnimatedVectorDrawable) {
                ((AnimatedVectorDrawable) icon).start();
            }
        }

        this.state = state;
    }

    private static int getDrawable(State currentState, State newState, boolean animate) {
        switch (newState) {
            case OFF:
                if (animate) {
                    if (currentState == State.ON) {
                        return R.drawable.swirl_draw_off_animation;
                    } else if (currentState == State.ERROR) {
                        return R.drawable.swirl_error_off_animation;
                    }
                }

                return 0;
            case ON:
                if (animate) {
                    if (currentState == State.OFF) {
                        return R.drawable.swirl_draw_on_animation;
                    } else if (currentState == State.ERROR) {
                        return R.drawable.swirl_error_state_to_fp_animation;
                    }
                }

                return R.drawable.swirl_fingerprint;
            case ERROR:
                if (animate) {
                    if (currentState == State.ON) {
                        return R.drawable.swirl_fp_to_error_state_animation;
                    } else if (currentState == State.OFF) {
                        return R.drawable.swirl_error_on_animation;
                    }
                }

                return R.drawable.swirl_error;
            default:
                throw new IllegalArgumentException("Unknown state: " + newState);
        }
    }
}
