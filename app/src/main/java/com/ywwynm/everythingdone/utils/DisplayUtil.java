package com.ywwynm.everythingdone.utils;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
import androidx.core.content.ContextCompat;
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.cardview.widget.CardView;
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
        // Content can overlay Navigation Bar above Lollipop.
        display.getRealSize(screen);
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
        Drawable wrappedDrawable = DrawableCompat.wrap(view.getBackground().mutate());
        DrawableCompat.setTint(wrappedDrawable, color);
        view.setBackground(wrappedDrawable);
    }

    public static void expandLayoutToStatusBarAboveLollipop(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    }

    public static void expandLayoutToFullscreenAboveLollipop(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    }

    public static void expandStatusBarViewAboveKitkat(View statusBar) {
        ViewCompat.setOnApplyWindowInsetsListener(statusBar, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ViewGroup.LayoutParams vlp = v.getLayoutParams();
            vlp.height = topInset;
            v.requestLayout();
            return insets;
        });
        statusBar.requestApplyInsets();
    }

    /**
     * Apply the bottom system-bar inset (gesture bar / 3-button nav) as
     * additional margin on the given view. Used for FABs and floating bottom
     * buttons that should "sit above" the system bar instead of being hidden
     * behind it.
     *
     * <p>The original layout's bottom margin is preserved; the inset is added
     * on top of it, and re-applied if the configuration changes.
     */
    public static void applyBottomInsetAsMargin(View view) {
        ViewGroup.MarginLayoutParams initial =
                (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        final int originalBottom = initial.bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.bottomMargin = originalBottom + bars.bottom;
            v.setLayoutParams(mlp);
            return insets;
        });
        view.requestApplyInsets();
    }

    /**
     * Apply the bottom system-bar inset as padding on the given view. The
     * original padding (top/left/right and existing bottom) is preserved; the
     * bottom inset is added on top of the original bottom padding.
     */
    public static void applyBottomInsetAsPadding(View view) {
        final int origLeft = view.getPaddingLeft();
        final int origTop = view.getPaddingTop();
        final int origRight = view.getPaddingRight();
        final int origBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(origLeft, origTop, origRight, origBottom + bars.bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    public static void darkStatusBar(Activity activity) {
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, decor);
        controller.setAppearanceLightStatusBars(true);
    }

    public static void cancelDarkStatusBar(Activity activity) {
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, decor);
        controller.setAppearanceLightStatusBars(false);
    }

    public static boolean isInMultiWindow(Activity activity) {
        if (DeviceUtil.hasNougatApi()) {
            return activity.isInMultiWindowMode();
        }
        return false;
    }

    /**
     * @deprecated Reflection-based approach is blocked by non-SDK interface restrictions on API 36+.
     * Use theme attributes {@code android:textSelectHandle}, {@code android:textSelectHandleLeft},
     * and {@code android:textSelectHandleRight} instead.
     */
    @Deprecated
    public static void setSelectionHandlersColor(EditText editText, int color) {
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

        int basePadding = (int) (density * 6);

        return (res.getDisplayMetrics().widthPixels - basePadding * 2 * (span + 1)) / span;
    }

    private static SparseArray<StateListDrawable> sSldMap;

    //private static HashMap<Integer, StateListDrawable> sSldMap;

    public static void setRippleColorForCardView(CardView cardView, int color) {
        RippleDrawable rp = (RippleDrawable) cardView.getForeground();
        rp.setColor(ColorStateList.valueOf(color));
    }

    public static void setSeekBarColor(SeekBar seekBar, int color) {
        seekBar.setProgressTintList(ColorStateList.valueOf(color));
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