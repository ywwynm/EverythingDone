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
        display.getRealSize(realScreen);
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
    private static final Random sRng = new Random();

    public static int getRandomColor(Context context) {
        int[] colors = context.getResources().getIntArray(R.array.thing);
        return colors[sRng.nextInt(colors.length)];
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

    /**
     * Slightly darker variant of {@code color}.
     *
     * <p>For colors that are part of the original 10-entry palette, returns the exact
     * value from {@code R.array.thing_dark} (preserving zero visual change for
     * existing data). For any other color, falls back to algorithmic blending via
     * {@link BackgroundUtil#darker(int, float)}.
     *
     * <p>Phase 1 of the color-system migration; see COLOR_MIGRATION_PLAN.md.
     */
    public static int getDarkColor(int color, Context context) {
        int index = getColorIndex(color, context);
        if (index != -1) {
            return context.getResources().getIntArray(R.array.thing_dark)[index];
        }
        return BackgroundUtil.darker(color, 0.15f);
    }

    /**
     * Slightly lighter (washed-out) variant of {@code color}.
     *
     * <p>For colors that are part of the original 10-entry palette, returns the exact
     * value from {@code R.array.thing_light} — the legacy values were hand-tuned and
     * a uniform 66% white blend doesn't match them within tolerable LSB delta for
     * some entries (notably pine_green). For any other color, falls back to
     * algorithmic blending via {@link BackgroundUtil#lighter(int, float)}.
     */
    public static int getLightColor(int color, Context context) {
        int index = getColorIndex(color, context);
        if (index != -1) {
            return context.getResources().getIntArray(R.array.thing_light)[index];
        }
        return BackgroundUtil.lighter(color, 0.66f);
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
    public static void applyBottomInsetAsMargin(final View view) {
        final ViewGroup.MarginLayoutParams initial =
                (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        final int originalBottom = initial.bottomMargin;
        chainDecorInsetsCallback(view, insets -> {
            int bottom = computeBottomInset(insets);
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            mlp.bottomMargin = originalBottom + bottom;
            view.setLayoutParams(mlp);
        });
    }

    /**
     * Apply the bottom system-bar / IME inset as padding on the given view.
     * The original padding (top/left/right and existing bottom) is preserved;
     * the inset is added on top of the original bottom padding.
     *
     * <p>The applied bottom equals {@code max(systemBars.bottom, ime.bottom)}
     * so a sticky bottom container stays above the soft keyboard when one
     * appears — important on edge-to-edge windows (Android 11+) where the
     * default {@code adjustResize} no longer auto-resizes the layout.
     */
    public static void applyBottomInsetAsPadding(final View view) {
        final int origLeft = view.getPaddingLeft();
        final int origTop = view.getPaddingTop();
        final int origRight = view.getPaddingRight();
        final int origBottom = view.getPaddingBottom();
        chainDecorInsetsCallback(view, insets -> {
            int bottom = computeBottomInset(insets);
            view.setPadding(origLeft, origTop, origRight, origBottom + bottom);
        });
    }

    /**
     * For scrollable containers (RecyclerView / NestedScrollView / ScrollView
     * etc.) on an edge-to-edge window: extend the visible content area down
     * to the nav bar while still letting the user scroll past it.
     *
     * <p>Sets {@code clipToPadding=false} (so the container's background and
     * fling overscroll keep filling the screen all the way to the bottom)
     * and adds {@code paddingBottom = systemBars.bottom + displayCutout.bottom}
     * so the last item in the list can be scrolled clear of the nav bar
     * instead of being permanently clipped underneath it.
     *
     * <p>Unlike {@link #applyBottomInsetAsPadding}, this helper deliberately
     * ignores the IME inset. A scrolled list with the keyboard open should
     * not grow another keyboard-height of padding at the bottom — that
     * pushes the list contents up by twice the expected amount.
     */
    public static void applyBottomInsetAsScrollPadding(final View view) {
        final int origLeft = view.getPaddingLeft();
        final int origTop = view.getPaddingTop();
        final int origRight = view.getPaddingRight();
        final int origBottom = view.getPaddingBottom();
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setClipToPadding(false);
        }
        chainDecorInsetsCallback(view, insets -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(origLeft, origTop, origRight, origBottom + bars.bottom);
        });
    }

    /**
     * Tag id used by {@link #chainDecorInsetsCallback} to attach a list of
     * per-target callbacks onto the activity's decor view. Hash of a stable
     * unique string — no R.id.* dependency needed.
     */
    private static final int TAG_DECOR_INSETS_CHAIN =
            "DisplayUtil#decorInsetsChain".hashCode();

    /**
     * Register {@code callback} so it runs with the activity decor view's raw
     * insets every time the platform dispatches them — including IME show / hide
     * animations (via {@link androidx.core.view.WindowInsetsAnimationCompat}).
     *
     * <p>Listening on the <strong>decor view</strong> is the only reliable
     * place: any intermediate ancestor with {@code fitsSystemWindows="true"}
     * (e.g. {@code DrawerLayout}) calls
     * {@code insets.consumeSystemWindowInsets()} before children see them, and
     * the activity's content view may itself consume insets via
     * {@link android.view.View#setFitsSystemWindows(boolean) fitsSystemWindows}.
     * The decor view is the topmost owner and is fed the raw {@link WindowInsetsCompat}
     * the system computed for the window.
     *
     * <p>Multiple callers share a single listener attached to the decor view
     * via a tag, so two separate {@code applyBottomInsetAsMargin} /
     * {@code applyBottomInsetAsPadding} calls in the same activity don't
     * clobber each other.
     *
     * <p>Pattern adapted from Everything-Android's {@code BaseActivity.kt}
     * where decor-view listening solves the same family of OEM /
     * folding-screen / multi-window edge cases.
     */
    @SuppressWarnings("unchecked")
    private static void chainDecorInsetsCallback(
            final View target,
            final java.util.function.Consumer<WindowInsetsCompat> callback) {
        Runnable install = new Runnable() {
            @Override public void run() {
                View decor = target.getRootView();
                java.util.List<java.util.function.Consumer<WindowInsetsCompat>> chain =
                        (java.util.List<java.util.function.Consumer<WindowInsetsCompat>>)
                                decor.getTag(TAG_DECOR_INSETS_CHAIN);
                if (chain == null) {
                    final java.util.List<java.util.function.Consumer<WindowInsetsCompat>>
                            list = new java.util.ArrayList<>();
                    // Shared "an IME animation is in flight" flag. The platform
                    // dispatches the *target* insets to the apply listener
                    // BEFORE the animation starts to play (between onPrepare
                    // and the first onProgress frame). If we apply those
                    // target insets to the chain immediately, padding snaps
                    // to the final value and then onProgress interpolates
                    // back from the pre-animation state to the same final
                    // value — visible as a "flash to final, jump back,
                    // animate up" flicker on every IME show. Skipping the
                    // apply path while imeAnimating[0] is true leaves the
                    // chain entirely under onProgress's control for the
                    // duration of the animation.
                    final boolean[] imeAnimating = { false };
                    decor.setTag(TAG_DECOR_INSETS_CHAIN, list);
                    chain = list;
                    // Stable-state path — fired on insets changes outside an
                    // IME animation (rotation, multi-window, gesture-nav
                    // entering / leaving). Skipped during IME animations to
                    // avoid the pre-animation target-insets flash described
                    // above.
                    ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
                        if (!imeAnimating[0]) {
                            for (java.util.function.Consumer<WindowInsetsCompat> c
                                    : new java.util.ArrayList<>(list)) {
                                c.accept(insets);
                            }
                        }
                        return insets;  // don't consume
                    });
                    // IME-animation path — fired every frame while the soft
                    // keyboard slides in / out. Without this, padding /
                    // margin would freeze at the pre-animation value and
                    // only catch the post-animation state if the platform
                    // happens to re-dispatch insets at the end (some OEM
                    // ROMs skip that). DISPATCH_MODE_CONTINUE_ON_SUBTREE so
                    // child views with their own animation callbacks still
                    // receive frames.
                    ViewCompat.setWindowInsetsAnimationCallback(decor,
                            new androidx.core.view.WindowInsetsAnimationCompat.Callback(
                                    androidx.core.view.WindowInsetsAnimationCompat.Callback
                                            .DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                                @Override
                                public void onPrepare(
                                        androidx.core.view.WindowInsetsAnimationCompat animation) {
                                    if ((animation.getTypeMask()
                                            & WindowInsetsCompat.Type.ime()) != 0) {
                                        imeAnimating[0] = true;
                                    }
                                }

                                @Override
                                public WindowInsetsCompat onProgress(
                                        WindowInsetsCompat insets,
                                        java.util.List<androidx.core.view.WindowInsetsAnimationCompat> running) {
                                    for (java.util.function.Consumer<WindowInsetsCompat> c
                                            : new java.util.ArrayList<>(list)) {
                                        c.accept(insets);
                                    }
                                    return insets;
                                }

                                @Override
                                public void onEnd(
                                        androidx.core.view.WindowInsetsAnimationCompat animation) {
                                    if ((animation.getTypeMask()
                                            & WindowInsetsCompat.Type.ime()) != 0) {
                                        imeAnimating[0] = false;
                                        // Read the current *stable* insets and
                                        // hand them to the chain once. Two
                                        // failure modes this guards against:
                                        //
                                        //  1. Multi-window: while IME is
                                        //     animating, the system temporarily
                                        //     folds the navbar inset into the
                                        //     IME envelope (the focused half
                                        //     resizes up). onProgress's final
                                        //     frame reports bars.bottom = 0,
                                        //     so the chain leaves padding at
                                        //     0 once the animation ends. The
                                        //     platform restores the correct
                                        //     bars.bottom in the post-animation
                                        //     stable insets — but doesn't
                                        //     always re-dispatch them, and we
                                        //     skip the apply listener while
                                        //     imeAnimating[0] is true anyway.
                                        //
                                        //  2. Some OEM ROMs skip the post-
                                        //     animation stable redispatch
                                        //     entirely.
                                        //
                                        // We use ViewCompat.getRootWindowInsets
                                        // (local read) rather than
                                        // decor.requestApplyInsets() to avoid
                                        // triggering a layout pass that
                                        // collides with concurrent popup /
                                        // DialogFragment show timing.
                                        WindowInsetsCompat current =
                                                ViewCompat.getRootWindowInsets(decor);
                                        if (current != null) {
                                            for (java.util.function.Consumer<WindowInsetsCompat> c
                                                    : new java.util.ArrayList<>(list)) {
                                                c.accept(current);
                                            }
                                        }
                                    }
                                }
                            });
                }
                chain.add(callback);
                decor.requestApplyInsets();
            }
        };
        if (target.isAttachedToWindow()) {
            install.run();
        } else {
            target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    install.run();
                }
                @Override public void onViewDetachedFromWindow(View v) {}
            });
        }
    }

    /** {@code max(systemBars.bottom + displayCutout.bottom, ime.bottom)} —
     *  picks the larger of "gesture / 3-button nav bar" or "soft-keyboard
     *  height" so a sticky bottom view sits above either system overlay. */
    private static int computeBottomInset(WindowInsetsCompat insets) {
        androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
        androidx.core.graphics.Insets ime = insets.getInsets(
                WindowInsetsCompat.Type.ime());
        return Math.max(bars.bottom, ime.bottom);
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
        return activity.isInMultiWindowMode();
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