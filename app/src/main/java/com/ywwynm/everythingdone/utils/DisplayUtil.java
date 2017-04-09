package com.ywwynm.everythingdone.utils;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
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
import android.support.v4.content.ContextCompat;
import android.support.v7.graphics.drawable.DrawerArrowDrawable;
import android.support.v7.widget.AppCompatCheckBox;
import android.support.v7.widget.AppCompatDrawableManager;
import android.support.v7.widget.CardView;
import android.text.Layout;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

/**
 * Created by ywwynm on 2015/6/28.
 * A helper class to get necessary screen information and update UI with color.
 */
public class DisplayUtil {

    public static final String TAG = "DisplayUtil";

    private DisplayUtil() {}

    public static float getScreenDensity(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    @SuppressLint("NewApi")
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
        if (DeviceUtil.hasJellyBeanMR1Api()) {
            display.getRealSize(realScreen);
        } else {
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

    public static boolean hasNavigationBar(Context context) { // improved on 2016/11/21
        boolean hasMenuKey = ViewConfiguration.get(context).hasPermanentMenuKey();
        boolean hasBackKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK);
        boolean con1 = !hasMenuKey && !hasBackKey;

        Resources resources = context.getResources();
        int id = resources.getIdentifier("config_showNavigationBar", "bool", "android");
        boolean con2 = id > 0 && resources.getBoolean(id);

        boolean con3;
        Point displaySize = new Point();
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        display.getSize(displaySize);
        Point screenSize = getScreenSize(context);
        if (context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            con3 = displaySize.y != screenSize.y;
        } else {
            con3 = displaySize.x != screenSize.x;
        }

        return con1 || con2 || con3;
    }

    public static int getNavigationBarHeight(Context context) { // improved on 2016/11/21
        int res1 = 0;
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            res1 = resources.getDimensionPixelSize(resourceId);
        }

        int res2;
        Point displaySize = new Point();
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        display.getSize(displaySize);
        Point screenSize = getScreenSize(context);
        if (context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            res2 = screenSize.y - displaySize.y;
        } else {
            res2 = screenSize.x - displaySize.x;
        }

        return Math.max(res1, res2);
    }

    // This method has a sexy history~
    // Someday if you see this code again, wish that your dream had come true
    public static int getRandomColor(Context context) {
        int[] colors = context.getResources().getIntArray(R.array.thing);
        return colors[new Random().nextInt(colors.length)];
    }

    public static int getColorIndex(int color, Context context) {
        int[] colors = context.getResources().getIntArray(R.array.thing);
        for (int i = 0; i < colors.length; i++) {
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
        Drawable.ConstantState constantState = d.getConstantState();
        if (constantState == null) return;
        final Drawable nd = constantState.newDrawable();
        nd.setColorFilter(AppCompatDrawableManager.getPorterDuffColorFilter(
                color, PorterDuff.Mode.SRC_IN));
        view.setBackground(nd);

        // Drawable wrappedDrawable = DrawableCompat.wrap(view.getBackground().mutate());
        // DrawableCompat.setTint(wrappedDrawable, color);
        // view.setBackground(wrappedDrawable);

        // ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(color));
    }

    public static void expandLayoutToStatusBarAboveLollipop(Activity activity) {
        if (DeviceUtil.hasLollipopApi()) {
            View decor = activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    public static void expandLayoutToFullscreenAboveLollipop(Activity activity) {
        if (DeviceUtil.hasLollipopApi()) {
            View decor = activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    public static void expandStatusBarViewAboveKitkat(View statusBar) {
        if (DeviceUtil.hasKitKatApi()) {
            final int height = getStatusbarHeight(statusBar.getContext());
            ViewGroup.LayoutParams vlp = statusBar.getLayoutParams();
            vlp.height = height;
            statusBar.requestLayout();
        }
    }

    public static void darkStatusBar(Activity activity) {
        Window window = activity.getWindow();
        if (DeviceUtil.hasMarshmallowApi()) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        darkStatusBarForMIUI(window);
        darkStatusBarForFlyme(window);
    }

    public static void cancelDarkStatusBar(Activity activity) {
        Window window = activity.getWindow();
        if (DeviceUtil.hasMarshmallowApi()) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private static void darkStatusBarForMIUI(Window window) {
        Class<? extends Window> clazz = window.getClass();
        try {
            Class<?> layoutParams = Class.forName("android.view.MiuiWindowManager$LayoutParams");
            Field field = layoutParams.getField("EXTRA_FLAG_STATUS_BAR_DARK_MODE");
            int darkModeFlag = field.getInt(layoutParams);
            Method extraFlagField = clazz.getMethod("setExtraFlags", int.class, int.class);
            extraFlagField.invoke(window, darkModeFlag, darkModeFlag);
        } catch (Exception ignored) { }
    }

    private static void darkStatusBarForFlyme(Window window) {
        try {
            WindowManager.LayoutParams lp = window.getAttributes();
            Field darkFlag = WindowManager.LayoutParams.class
                    .getDeclaredField("MEIZU_FLAG_DARK_STATUS_BAR_ICON");
            Field meizuFlags = WindowManager.LayoutParams.class
                    .getDeclaredField("meizuFlags");
            darkFlag.setAccessible(true);
            meizuFlags.setAccessible(true);
            int bit = darkFlag.getInt(null);
            int value = meizuFlags.getInt(lp);
            value |= bit;
            meizuFlags.setInt(lp, value);
            window.setAttributes(lp);
        } catch (Exception ignored) { }
    }

    public static boolean isInMultiWindow(Activity activity) {
        if (DeviceUtil.hasNougatApi()) {
            return activity.isInMultiWindowMode();
        }
        return false;
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

    private static SparseArray<StateListDrawable> sSldMap;

    //private static HashMap<Integer, StateListDrawable> sSldMap;

    public static void setRippleColorForCardView(CardView cardView, int color) {
        if (DeviceUtil.hasLollipopApi()) {
            RippleDrawable rp = (RippleDrawable) cardView.getForeground();
            rp.setColor(ColorStateList.valueOf(color));
        } else {
            if (sSldMap == null) {
                sSldMap = new SparseArray<>();
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

    public static void setSeekBarColor(SeekBar seekBar, int color) {
        if (DeviceUtil.hasLollipopApi()) {
            seekBar.setProgressTintList(ColorStateList.valueOf(color));
        } else {
            seekBar.getProgressDrawable().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        seekBar.getThumb().setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    public static void setButtonColor(Button button, int color) {
        button.getBackground().setColorFilter(color, PorterDuff.Mode.MULTIPLY);
    }

    public static void setCheckBoxColor(AppCompatCheckBox checkBox, int accentColor) {
        setCheckBoxColor(checkBox,
                ContextCompat.getColor(checkBox.getContext(), R.color.black_54), accentColor);
    }

    public static void setCheckBoxColor(AppCompatCheckBox checkBox, int uncheckedColor, int checkedColor) {
        ColorStateList colorStateList = new ColorStateList(
                new int[][] {
                        new int[] { -android.R.attr.state_checked }, // unchecked
                        new int[] {  android.R.attr.state_checked }  // checked
                },
                new int[] {
                        uncheckedColor,
                        checkedColor
                }
        );
        checkBox.setSupportButtonTintList(colorStateList);
    }

    public static int getCursorY(EditText et) {
        int pos = et.getSelectionStart();
        Layout layout = et.getLayout();
        int line = layout.getLineForOffset(pos);
        int baseline = layout.getLineBaseline(line);
        int ascent = layout.getLineAscent(line);
        return baseline + ascent;
    }
}