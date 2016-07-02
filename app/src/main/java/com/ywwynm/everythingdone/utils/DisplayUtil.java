package com.ywwynm.everythingdone.utils;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.support.v4.content.ContextCompat;
import android.support.v7.graphics.drawable.DrawerArrowDrawable;
import android.support.v7.widget.AppCompatDrawableManager;
import android.support.v7.widget.CardView;
import android.util.Log;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Random;

/**
 * Created by ywwynm on 2015/6/28.
 * A helper class to get necessary screen information and update UI with color.
 */
public class DisplayUtil {

    public static final String TAG = "EverythingDone$DisplayUtil";

    public static float getScreenDensity(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    public static Point getDisplaySize(Context context) {
        Point screen = new Point();
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        if (!DeviceUtil.hasLollipopApi()) {
            display.getSize(screen);
        } else {
            // Content can overlay Navigation Bar above Lollipop.
            display.getRealSize(screen);
        }
        return screen;
    }

    // Get physical screen size of phone/tablet.
    public static Point getScreenSize(Context context) {
        Point realScreen = new Point();
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                Method mGetRawH = Display.class.getMethod("getRawHeight");
                Method mGetRawW = Display.class.getMethod("getRawWidth");
                realScreen.x = (Integer) mGetRawW.invoke(display);
                realScreen.y = (Integer) mGetRawH.invoke(display);
            } catch (Exception e) {
                display.getSize(realScreen);
                Log.e(TAG, "Cannot use reflection to get real screen size. " +
                        "Returned size may be wrong.");
            }
        } else {
            display.getRealSize(realScreen);
        }
        return realScreen;
    }

    public static boolean isTablet(Context context) { // improved on 2016/5/11~
        return context.getResources().getBoolean(R.bool.isTablet);
    }

    public static int getStatusbarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        } else return 0;
    }

    public static boolean hasNavigationBar(Context context) {
        boolean hasMenuKey = ViewConfiguration.get(context).hasPermanentMenuKey();
        boolean hasBackKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK);
        return !hasMenuKey && !hasBackKey;
    }

    public static int getNavigationBarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        } else return 0;
    }

    public static int getRandomColor(Context context) {
        // instead of 5 times, 6 sounds better~
        int[] mlTimesWithQQPerNight = { 1, 1, 2, 2, 3, 3, 4, 4, 5, 6, 6 };
        Random r = new Random();
        int decision = r.nextInt(mlTimesWithQQPerNight.length);
        return context.getResources().getIntArray(R.array.thing)[mlTimesWithQQPerNight[decision] - 1];
    }

    public static int getColorIndex(int color, Context context) {
        int[] colors = context.getResources().getIntArray(R.array.thing);
        for (int i = 0; i < 6; i++) {
            if (colors[i] == color) {
                return i;
            }
        }
        return -1;
    }

    public static int getDarkColor(int color, Context context) {
        int[] colorsDark = context.getResources().getIntArray(R.array.thing_dark);
        int index = getColorIndex(color, context);
        if (index != -1) {
            return colorsDark[index];
        } else return 0;
    }

    public static int getLightColor(int color, Context context) {
        int[] colorsLight = context.getResources().getIntArray(R.array.thing_light);
        int index = getColorIndex(color, context);
        if (index != -1) {
            return colorsLight[index];
        } else return 0;
    }

    public static int getTransparentColor(int color, int alpha) {
        int red   = Color.red(color);
        int green = Color.green(color);
        int blue  = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    /**
     * Play drawer toggle animation(from drawer to arrow and vice versa).
     * @param d the {@link DrawerArrowDrawable} object to play toggle animation.
     */
    public static void playDrawerToggleAnim(final DrawerArrowDrawable d) {
        float start = d.getProgress();
        float end = Math.abs(start - 1);
        ValueAnimator offsetAnimator = ValueAnimator.ofFloat(start, end);
        offsetAnimator.setDuration(300);
        offsetAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        offsetAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (Float) animation.getAnimatedValue();
                d.setProgress(progress);
            }
        });
        offsetAnimator.start();
    }

    /**
     * Set backgroundTint to {@link View} across all targeting platform level.
     * @param view the {@link View} to tint.
     * @param color color used to tint.
     */
    public static void tintView(View view, int color) {
        final Drawable d = view.getBackground();
        final Drawable nd = d.getConstantState().newDrawable();
        nd.setColorFilter(AppCompatDrawableManager.getPorterDuffColorFilter(
                color, PorterDuff.Mode.SRC_IN));
        view.setBackground(nd);

        // Drawable wrappedDrawable = DrawableCompat.wrap(view.getBackground().mutate());
        // DrawableCompat.setTint(wrappedDrawable, color);
        // view.setBackground(wrappedDrawable);

        // ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(color));
    }

    public static void expandStatusBarAboveKitkat(View statusBar) {
        if (DeviceUtil.hasKitKatApi()) {
            final int height = getStatusbarHeight(statusBar.getContext());
            ViewGroup.LayoutParams vlp = statusBar.getLayoutParams();
            if (vlp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) vlp;
                llp.height = height;
            } else if (vlp instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams) vlp;
                rlp.height = height;
            } else if (vlp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) vlp;
                flp.height = height;
            }
            statusBar.requestLayout();
        }
    }

    public static void darkStatusBarForMIUI(Activity activity) {
        Class<? extends Window> clazz = activity.getWindow().getClass();
        try {
            Class<?> layoutParams = Class.forName("android.view.MiuiWindowManager$LayoutParams");
            Field field = layoutParams.getField("EXTRA_FLAG_STATUS_BAR_DARK_MODE");
            int darkModeFlag = field.getInt(layoutParams);
            Method extraFlagField = clazz.getMethod("setExtraFlags", int.class, int.class);
            extraFlagField.invoke(activity.getWindow(), darkModeFlag, darkModeFlag);
        } catch (Exception ignored) { }
    }

    public static void coverStatusBar(View statusBarCover) {
        if (!shouldCoverStatusBar()) {
            return;
        }
        ViewGroup.LayoutParams vlp = statusBarCover.getLayoutParams();
        vlp.height = getStatusbarHeight(statusBarCover.getContext());
        statusBarCover.requestLayout();
    }

    public static boolean shouldCoverStatusBar() {
        return DeviceUtil.hasMarshmallowApi() && DeviceUtil.isEMUI();
    }

    /**
     * Set color of handlers appearing when user is selecting content of {@link EditText}.
     * @param editText handlers of which {@link EditText} should be set to {@param color}.
     * @param color color to set for handlers.
     */
    public static void setSelectionHandlersColor(EditText editText, int color) {
        try {
            final Class<?> cTextView = TextView.class;
            final Field fhlRes = cTextView.getDeclaredField("mTextSelectHandleLeftRes");
            final Field fhrRes = cTextView.getDeclaredField("mTextSelectHandleRightRes");
            final Field fhcRes = cTextView.getDeclaredField("mTextSelectHandleRes");
            fhlRes.setAccessible(true);
            fhrRes.setAccessible(true);
            fhcRes.setAccessible(true);

            int hlRes = fhlRes.getInt(editText);
            int hrRes = fhrRes.getInt(editText);
            int hcRes = fhcRes.getInt(editText);

            final Field fEditor = TextView.class.getDeclaredField("mEditor");
            fEditor.setAccessible(true);
            final Object editor = fEditor.get(editText);

            final Class<?> cEditor = editor.getClass();
            final Field fSelectHandleL = cEditor.getDeclaredField("mSelectHandleLeft");
            final Field fSelectHandleR = cEditor.getDeclaredField("mSelectHandleRight");
            final Field fSelectHandleC = cEditor.getDeclaredField("mSelectHandleCenter");
            fSelectHandleL.setAccessible(true);
            fSelectHandleR.setAccessible(true);
            fSelectHandleC.setAccessible(true);

            Drawable selectHandleL = ContextCompat.getDrawable(editText.getContext(), hlRes);
            Drawable selectHandleR = ContextCompat.getDrawable(editText.getContext(), hrRes);
            Drawable selectHandleC = ContextCompat.getDrawable(editText.getContext(), hcRes);

            selectHandleL.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            selectHandleR.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            selectHandleC.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);

            fSelectHandleL.set(editor, selectHandleL);
            fSelectHandleR.set(editor, selectHandleR);
            fSelectHandleC.set(editor, selectHandleC);
        } catch (Exception ignored) { }
    }

    public static int getThingCardWidth(Context context) {
        int span = 2;
        Resources res = context.getResources();
        float density = res.getDisplayMetrics().density;

        if (res.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            span++;
        }
        if (isTablet(context)) {
            span++;
        }

        int basePadding;
        if (!DeviceUtil.hasLollipopApi()) {
            basePadding = (int) (density * 4);
        } else {
            basePadding = (int) (density * 6);
        }

        return (res.getDisplayMetrics().widthPixels - basePadding * 2 * (span + 1)) / span;
    }

    private static HashMap<Integer, StateListDrawable> sSldMap;

    public static void setRippleColorForCardView(CardView cardView, int color) {
        if (DeviceUtil.hasLollipopApi()) {
            RippleDrawable rp = (RippleDrawable) cardView.getForeground();
            rp.setColor(ColorStateList.valueOf(color));
        } else {
            if (sSldMap == null) {
                sSldMap = new HashMap<>();
            }
            StateListDrawable sld = sSldMap.get(color);
            if (sld == null) {
                sld = new StateListDrawable();
                sld.addState(new int[] { android.R.attr.state_pressed },
                        new ColorDrawable(color));
                sld.addState(new int[]{-android.R.attr.state_pressed},
                        new ColorDrawable(Color.TRANSPARENT));
                sSldMap.put(color, sld);
            }
            cardView.setForeground(sld);
        }
    }
}