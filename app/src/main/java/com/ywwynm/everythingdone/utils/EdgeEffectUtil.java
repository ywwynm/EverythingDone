package com.ywwynm.everythingdone.utils;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import androidx.viewpager.widget.ViewPager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.EdgeEffect;
import android.widget.ScrollView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Created by ywwynm on 2015/8/20.
 * Util to set over-scroll color for scrollable views like RecyclerView, ViewPager,
 * ScrollView and etc.
 */
public class EdgeEffectUtil {

    public static final String TAG = "EdgeEffectUtil";

    private EdgeEffectUtil() {}

    public static int getEdgeColorDark() {
        return Color.parseColor("#40000000");
    }

    public static void forScrollView(ScrollView sv, int color) {
        try {
            final Class<?> clazz = ScrollView.class;
            final Field fEdgeGlowTop = clazz.getDeclaredField("mEdgeGlowTop");
            final Field fEdgeGlowBottom = clazz.getDeclaredField("mEdgeGlowBottom");
            fEdgeGlowTop.setAccessible(true);
            fEdgeGlowBottom.setAccessible(true);
            setEdgeEffectColor((EdgeEffect) fEdgeGlowTop.get(sv), color);
            setEdgeEffectColor((EdgeEffect) fEdgeGlowBottom.get(sv), color);
        } catch (final Exception ignored) {
        }
    }

    public static void forRecyclerView(RecyclerView recyclerView, int color) {
        try {
            final Class<?> rvClass = RecyclerView.class;
            for (final String name : new String[] { "ensureTopGlow", "ensureBottomGlow" }) {
                Method method = rvClass.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(recyclerView);
            }
            for (final String name : new String[] { "mTopGlow", "mBottomGlow" }) {
                final Field field = rvClass.getDeclaredField(name);
                field.setAccessible(true);
                final Object edge = field.get(recyclerView);
                final Field fEdgeEffect = edge.getClass().getDeclaredField("mEdgeEffect");
                fEdgeEffect.setAccessible(true);
                setEdgeEffectColor((EdgeEffect) fEdgeEffect.get(edge), color);
            }
        } catch (Exception ignored) { }
    }

    public static void forViewPager(ViewPager viewPager, int color) {
        try {
            Class<?> vpClass = ViewPager.class;
            for (String name : new String[] {"mLeftEdge", "mRightEdge"}) {
                Field field = vpClass.getDeclaredField(name);
                field.setAccessible(true);
                Object edge = field.get(viewPager);
                Field fEdgeEffect = edge.getClass().getDeclaredField("mEdgeEffect");
                fEdgeEffect.setAccessible(true);
                setEdgeEffectColor((EdgeEffect) fEdgeEffect.get(edge), color);
            }
        } catch (Exception ignored) { }
    }

    private static void setEdgeEffectColor(EdgeEffect edgeEffect, int color) {
        try {
            edgeEffect.setColor(color);
        } catch (Exception ignored) { }
    }

}
