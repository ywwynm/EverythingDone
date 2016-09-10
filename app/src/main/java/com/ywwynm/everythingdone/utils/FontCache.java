package com.ywwynm.everythingdone.utils;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;

/**
 * Created by ywwynm on 2016/3/16.
 * cache fonts to prevent memory leak
 */
public class FontCache {

    public static final String TAG = "FontCache";

    private FontCache() {}

    private static HashMap<String, Typeface> fontCache = new HashMap<>();

    public static Typeface get(String name, Context context) {
        Typeface tf = fontCache.get(name);
        if (tf == null) {
            tf = Typeface.createFromAsset(context.getAssets(), name);
            fontCache.put(name, tf);
        }
        return tf;
    }

}
