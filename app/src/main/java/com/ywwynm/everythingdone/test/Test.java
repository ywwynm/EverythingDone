/**
 * Created by ywwynm on 2015/5/8.
 */

package com.ywwynm.everythingdone.test;

import android.content.Context;

import com.ywwynm.everythingdone.R;

public class Test {

    public Test(Context context) {
        int[] colors = context.getResources().getIntArray(R.array.thing);
        for (int i = 0; i < 6; i++) {
            System.out.println(colors[i]);
        }

    }
}
