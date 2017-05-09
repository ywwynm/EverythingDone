package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.R;

import java.util.Arrays;
import java.util.List;

/**
 * Created by ywwynm on 2017/5/9.
 * A helper class for creating daily to-do automatically
 */
public class DailyTodoHelper {

    public static final String TAG = "DailyTodoHelper";

    private DailyTodoHelper() {}

    public static List<String> getDailyTodoItems(Context context) {
        return Arrays.asList(
                context.getString(R.string.disable),
                "5:00",
                "5:30",
                "6:00",
                "6:30",
                "7:00",
                "7:30",
                "8:00",
                "8:30"
        );
    }

    public static List<Pair<Integer, Integer>> getDailyTodoTimePairs() {
        return Arrays.asList(
                new Pair<>(-1, -1),
                new Pair<>(5, 0),
                new Pair<>(5, 30),
                new Pair<>(6, 0),
                new Pair<>(6, 30),
                new Pair<>(7, 0),
                new Pair<>(7, 30),
                new Pair<>(8, 0),
                new Pair<>(8, 30)
        );
    }

}
