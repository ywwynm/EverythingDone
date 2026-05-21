@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.graphics.drawable.DrawableCompat
import androidx.cardview.widget.CardView
import android.text.Layout
import android.util.SparseArray
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar

import com.ywwynm.everythingdone.R

import java.util.Random

/**
 * Created by ywwynm on 2015/6/28.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A helper class to get necessary screen information and update UI with color.
 */
object DisplayUtil {

    const val TAG: String = "DisplayUtil"

    @JvmStatic
    fun getScreenDensity(context: Context?): Float {
        return context!!.resources.displayMetrics.density
    }

    @SuppressLint("NewApi")
    @JvmStatic
    fun getDisplaySize(context: Context?): Point {
        val screen = Point()
        val display: Display = (context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay
        // Content can overlay Navigation Bar above Lollipop.
        display.getRealSize(screen)
        return screen
    }

    // Get physical screen size of phone/tablet.
    @JvmStatic
    fun getScreenSize(context: Context?): Point {
        val realScreen = Point()
        val display: Display = (context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay
        display.getRealSize(realScreen)
        return realScreen
    }

    @JvmStatic
    fun isTablet(context: Context?): Boolean { // improved on 2016/5/11~
        return context!!.resources.getBoolean(R.bool.isTablet)
    }

    @JvmStatic
    fun getStatusbarHeight(context: Context?): Int {
        val resources: Resources = context!!.resources
        val resourceId: Int = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else 0
    }

    @JvmStatic
    fun hasNavigationBar(context: Context?): Boolean { // improved on 2016/11/21
        val hasMenuKey: Boolean = ViewConfiguration.get(context!!).hasPermanentMenuKey()
        val hasBackKey: Boolean = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK)
        val con1: Boolean = !hasMenuKey && !hasBackKey

        val resources: Resources = context!!.resources
        val id: Int = resources.getIdentifier("config_showNavigationBar", "bool", "android")
        val con2: Boolean = id > 0 && resources.getBoolean(id)

        val con3: Boolean
        val displaySize = Point()
        val display: Display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay
        display.getSize(displaySize)
        val screenSize: Point = getScreenSize(context)!!
        con3 = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            displaySize.y != screenSize.y
        } else {
            displaySize.x != screenSize.x
        }

        return con1 || con2 || con3
    }

    @JvmStatic
    fun getNavigationBarHeight(context: Context?): Int { // improved on 2016/11/21
        var res1 = 0
        val resources: Resources = context!!.resources
        val resourceId: Int = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            res1 = resources.getDimensionPixelSize(resourceId)
        }

        val res2: Int
        val displaySize = Point()
        val display: Display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay
        display.getSize(displaySize)
        val screenSize: Point = getScreenSize(context)!!
        res2 = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            screenSize.y - displaySize.y
        } else {
            screenSize.x - displaySize.x
        }

        return Math.max(res1, res2)
    }

    // This method has a sexy history~
    // Someday if you see this code again, wish that your dream had come true
    private val sRng: Random = Random()

    @JvmStatic
    fun getRandomColor(context: Context?): Int {
        val colors: IntArray = context!!.resources.getIntArray(R.array.thing)
        return colors[sRng.nextInt(colors.size)]
    }

    @JvmStatic
    fun getColorIndex(color: Int, context: Context?): Int {
        val colors: IntArray = context!!.resources.getIntArray(R.array.thing)
        for (i in 0 until colors.size) {
            if (colors[i] == color) {
                return i
            }
        }
        return -1
    }

    /**
     * Slightly darker variant of `color`.
     *
     * For colors that are part of the original 10-entry palette, returns the exact
     * value from `R.array.thing_dark` (preserving zero visual change for
     * existing data). For any other color, falls back to algorithmic blending via
     * [BackgroundUtil.darker].
     *
     * Phase 1 of the color-system migration; see COLOR_MIGRATION_PLAN.md.
     */
    @JvmStatic
    fun getDarkColor(color: Int, context: Context?): Int {
        val index: Int = getColorIndex(color, context)
        if (index != -1) {
            return context!!.resources.getIntArray(R.array.thing_dark)[index]
        }
        return BackgroundUtil.darker(color, 0.15f)
    }

    /**
     * Slightly lighter (washed-out) variant of `color`.
     *
     * For colors that are part of the original 10-entry palette, returns the exact
     * value from `R.array.thing_light` — the legacy values were hand-tuned and
     * a uniform 66% white blend doesn't match them within tolerable LSB delta for
     * some entries (notably pine_green). For any other color, falls back to
     * algorithmic blending via [BackgroundUtil.lighter].
     */
    @JvmStatic
    fun getLightColor(color: Int, context: Context?): Int {
        val index: Int = getColorIndex(color, context)
        if (index != -1) {
            return context!!.resources.getIntArray(R.array.thing_light)[index]
        }
        return BackgroundUtil.lighter(color, 0.66f)
    }

    @JvmStatic
    fun getTransparentColor(color: Int, alpha: Int): Int {
        val red: Int   = Color.red(color)
        val green: Int = Color.green(color)
        val blue: Int  = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    /**
     * Play drawer toggle animation(from drawer to arrow and vice versa).
     * @param d the [DrawerArrowDrawable] object to play toggle animation.
     */
    @JvmStatic
    fun playDrawerToggleAnim(d: DrawerArrowDrawable?) {
        val start: Float = d!!.progress
        val end: Float = Math.abs(start - 1)
        val offsetAnimator: ValueAnimator = ValueAnimator.ofFloat(start, end)
        offsetAnimator.setDuration(300)
        offsetAnimator.interpolator = AccelerateDecelerateInterpolator()
        offsetAnimator.addUpdateListener { animation: ValueAnimator ->
            val progress: Float = animation.getAnimatedValue() as Float
            d.progress = progress
        }
        offsetAnimator.start()
    }

    /**
     * Set backgroundTint to [View] across all targeting platform level.
     * @param view the [View] to tint.
     * @param color color used to tint.
     */
    @JvmStatic
    fun tintView(view: View?, color: Int) {
        val wrappedDrawable: Drawable = DrawableCompat.wrap(view!!.background.mutate())
        DrawableCompat.setTint(wrappedDrawable, color)
        view.background = wrappedDrawable
    }

    @JvmStatic
    fun expandLayoutToStatusBarAboveLollipop(activity: Activity?) {
        WindowCompat.setDecorFitsSystemWindows(activity!!.window, false)
    }

    @JvmStatic
    fun expandLayoutToFullscreenAboveLollipop(activity: Activity?) {
        WindowCompat.setDecorFitsSystemWindows(activity!!.window, false)
    }

    @JvmStatic
    fun expandStatusBarViewAboveKitkat(statusBar: View?) {
        ViewCompat.setOnApplyWindowInsetsListener(statusBar!!) { v, insets ->
            val topInset: Int = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val vlp: ViewGroup.LayoutParams = v.layoutParams
            vlp.height = topInset
            v.requestLayout()
            insets
        }
        statusBar.requestApplyInsets()
    }

    /**
     * Apply the bottom system-bar inset (gesture bar / 3-button nav) as
     * additional margin on the given view. Used for FABs and floating bottom
     * buttons that should "sit above" the system bar instead of being hidden
     * behind it.
     *
     * The original layout's bottom margin is preserved; the inset is added
     * on top of it, and re-applied if the configuration changes.
     */
    @JvmStatic
    fun applyBottomInsetAsMargin(view: View?) {
        val initial: ViewGroup.MarginLayoutParams =
                view!!.layoutParams as ViewGroup.MarginLayoutParams
        val originalBottom: Int = initial.bottomMargin
        chainDecorInsetsCallback(view) { insets ->
            val bottom: Int = computeBottomInset(insets)
            val mlp: ViewGroup.MarginLayoutParams =
                    view.layoutParams as ViewGroup.MarginLayoutParams
            mlp.bottomMargin = originalBottom + bottom
            view.setLayoutParams(mlp)
        }
    }

    /**
     * Apply the bottom system-bar / IME inset as padding on the given view.
     * The original padding (top/left/right and existing bottom) is preserved;
     * the inset is added on top of the original bottom padding.
     *
     * The applied bottom equals `max(systemBars.bottom, ime.bottom)`
     * so a sticky bottom container stays above the soft keyboard when one
     * appears — important on edge-to-edge windows (Android 11+) where the
     * default `adjustResize` no longer auto-resizes the layout.
     */
    @JvmStatic
    fun applyBottomInsetAsPadding(view: View?) {
        val origLeft: Int = view!!.getPaddingLeft()
        val origTop: Int = view.paddingTop
        val origRight: Int = view.getPaddingRight()
        val origBottom: Int = view.paddingBottom
        chainDecorInsetsCallback(view) { insets ->
            val bottom: Int = computeBottomInset(insets)
            view.setPadding(origLeft, origTop, origRight, origBottom + bottom)
        }
    }

    /**
     * For scrollable containers (RecyclerView / NestedScrollView / ScrollView
     * etc.) on an edge-to-edge window: extend the visible content area down
     * to the nav bar while still letting the user scroll past it.
     *
     * Sets `clipToPadding=false` (so the container's background and
     * fling overscroll keep filling the screen all the way to the bottom)
     * and adds `paddingBottom = systemBars.bottom + displayCutout.bottom`
     * so the last item in the list can be scrolled clear of the nav bar
     * instead of being permanently clipped underneath it.
     *
     * Unlike [applyBottomInsetAsPadding], this helper deliberately
     * ignores the IME inset. A scrolled list with the keyboard open should
     * not grow another keyboard-height of padding at the bottom — that
     * pushes the list contents up by twice the expected amount.
     */
    @JvmStatic
    fun applyBottomInsetAsScrollPadding(view: View?) {
        val origLeft: Int = view!!.getPaddingLeft()
        val origTop: Int = view.paddingTop
        val origRight: Int = view.getPaddingRight()
        val origBottom: Int = view.paddingBottom
        if (view is ViewGroup) {
            view.clipToPadding = false
        }
        chainDecorInsetsCallback(view) { insets ->
            val bars: androidx.core.graphics.Insets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(origLeft, origTop, origRight, origBottom + bars.bottom)
        }
    }

    /**
     * Tag id used by [chainDecorInsetsCallback] to attach a list of
     * per-target callbacks onto the activity's decor view. Hash of a stable
     * unique string — no R.id.* dependency needed.
     */
    private val TAG_DECOR_INSETS_CHAIN: Int =
            "DisplayUtil#decorInsetsChain".hashCode()

    /**
     * Register `callback` so it runs with the activity decor view's raw
     * insets every time the platform dispatches them — including IME show / hide
     * animations (via [androidx.core.view.WindowInsetsAnimationCompat]).
     *
     * Listening on the **decor view** is the only reliable
     * place: any intermediate ancestor with `fitsSystemWindows="true"`
     * (e.g. `DrawerLayout`) calls
     * `insets.consumeSystemWindowInsets()` before children see them, and
     * the activity's content view may itself consume insets via
     * [android.view.View.setFitsSystemWindows] fitsSystemWindows.
     * The decor view is the topmost owner and is fed the raw [WindowInsetsCompat]
     * the system computed for the window.
     *
     * Multiple callers share a single listener attached to the decor view
     * via a tag, so two separate `applyBottomInsetAsMargin` /
     * `applyBottomInsetAsPadding` calls in the same activity don't
     * clobber each other.
     *
     * Pattern adapted from Everything-Android's `BaseActivity.kt`
     * where decor-view listening solves the same family of OEM /
     * folding-screen / multi-window edge cases.
     */
    @Suppress("UNCHECKED_CAST")
    private fun chainDecorInsetsCallback(
            target: View,
            callback: java.util.function.Consumer<WindowInsetsCompat>) {
        val install: Runnable = object : Runnable {
            override fun run() {
                val decor: View = target.getRootView()
                var chain: MutableList<java.util.function.Consumer<WindowInsetsCompat>>? =
                        decor.getTag(TAG_DECOR_INSETS_CHAIN)
                                as MutableList<java.util.function.Consumer<WindowInsetsCompat>>?
                if (chain == null) {
                    val list: MutableList<java.util.function.Consumer<WindowInsetsCompat>> =
                            java.util.ArrayList<java.util.function.Consumer<WindowInsetsCompat>>()
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
                    val imeAnimating: BooleanArray = booleanArrayOf(false)
                    decor.setTag(TAG_DECOR_INSETS_CHAIN, list)
                    chain = list
                    // Stable-state path — fired on insets changes outside an
                    // IME animation (rotation, multi-window, gesture-nav
                    // entering / leaving). Skipped during IME animations to
                    // avoid the pre-animation target-insets flash described
                    // above.
                    ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
                        if (!imeAnimating[0]) {
                            for (c in java.util.ArrayList(list)) {
                                c.accept(insets)
                            }
                        }
                        insets  // don't consume
                    }
                    // IME-animation path — fired every frame while the soft
                    // keyboard slides in / out. Without this, padding /
                    // margin would freeze at the pre-animation value and
                    // only catch the post-animation state if the platform
                    // happens to re-dispatch insets at the end (some OEM
                    // ROMs skip that). DISPATCH_MODE_CONTINUE_ON_SUBTREE so
                    // child views with their own animation callbacks still
                    // receive frames.
                    ViewCompat.setWindowInsetsAnimationCallback(decor,
                            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(
                                DISPATCH_MODE_CONTINUE_ON_SUBTREE
                            ) {
                                override fun onPrepare(
                                        animation: androidx.core.view.WindowInsetsAnimationCompat) {
                                    if ((animation.typeMask
                                            and WindowInsetsCompat.Type.ime()) != 0) {
                                        imeAnimating[0] = true
                                    }
                                }

                                override fun onProgress(
                                        insets: WindowInsetsCompat,
                                        running: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): WindowInsetsCompat {
                                    for (c in java.util.ArrayList(list)) {
                                        c.accept(insets)
                                    }
                                    return insets
                                }

                                override fun onEnd(
                                        animation: androidx.core.view.WindowInsetsAnimationCompat) {
                                    if ((animation.typeMask
                                            and WindowInsetsCompat.Type.ime()) != 0) {
                                        imeAnimating[0] = false
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
                                        val current: WindowInsetsCompat? =
                                                ViewCompat.getRootWindowInsets(decor)
                                        if (current != null) {
                                            for (c in java.util.ArrayList(list)) {
                                                c.accept(current)
                                            }
                                        }
                                    }
                                }
                            })
                }
                chain.add(callback)
                decor.requestApplyInsets()
            }
        }
        if (target.isAttachedToWindow) {
            install.run()
        } else {
            target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    install.run()
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }

    /** `max(systemBars.bottom + displayCutout.bottom, ime.bottom)` —
     *  picks the larger of "gesture / 3-button nav bar" or "soft-keyboard
     *  height" so a sticky bottom view sits above either system overlay. */
    private fun computeBottomInset(insets: WindowInsetsCompat): Int {
        val bars: androidx.core.graphics.Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout())
        val ime: androidx.core.graphics.Insets = insets.getInsets(
                WindowInsetsCompat.Type.ime())
        return Math.max(bars.bottom, ime.bottom)
    }

    @JvmStatic
    fun darkStatusBar(activity: Activity?) {
        val window: Window = activity!!.window
        val decor: View = window.decorView
        val controller = WindowInsetsControllerCompat(window, decor)
        controller.isAppearanceLightStatusBars = true
    }

    @JvmStatic
    fun cancelDarkStatusBar(activity: Activity?) {
        val window: Window = activity!!.window
        val decor: View = window.decorView
        val controller = WindowInsetsControllerCompat(window, decor)
        controller.isAppearanceLightStatusBars = false
    }

    @JvmStatic
    fun isInMultiWindow(activity: Activity?): Boolean {
        return activity!!.isInMultiWindowMode
    }

    /**
     * @deprecated Reflection-based approach is blocked by non-SDK interface restrictions on API 36+.
     * Use theme attributes `android:textSelectHandle`, `android:textSelectHandleLeft`,
     * and `android:textSelectHandleRight` instead.
     */
    @Deprecated("Reflection-based approach is blocked by non-SDK interface restrictions on API 36+.")
    @JvmStatic
    fun setSelectionHandlersColor(editText: EditText?, color: Int) {
    }

    @JvmStatic
    fun getThingCardWidth(context: Context?): Int {
        var span = 2
        val res: Resources = context!!.resources
        val density: Float = res.displayMetrics.density

        if (res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            span++
        }
        if (isTablet(context)) {
            span++
        }

        val basePadding: Int = (density * 6).toInt()

        return (res.displayMetrics.widthPixels - basePadding * 2 * (span + 1)) / span
    }

    private var sSldMap: SparseArray<StateListDrawable?>? = null

    //private static HashMap<Integer, StateListDrawable> sSldMap;

    @JvmStatic
    fun setRippleColorForCardView(cardView: CardView?, color: Int) {
        val rp: RippleDrawable = cardView!!.foreground as RippleDrawable
        rp.setColor(ColorStateList.valueOf(color))
    }

    @JvmStatic
    fun setSeekBarColor(seekBar: SeekBar?, color: Int) {
        seekBar!!.setProgressTintList(ColorStateList.valueOf(color))
        seekBar.thumb!!.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    @JvmStatic
    fun setButtonColor(button: Button?, color: Int) {
        button!!.background.setColorFilter(color, PorterDuff.Mode.MULTIPLY)
    }

    @JvmStatic
    fun setCheckBoxColor(checkBox: AppCompatCheckBox?, accentColor: Int) {
        setCheckBoxColor(checkBox,
                ContextCompat.getColor(checkBox!!.context, R.color.black_54), accentColor)
    }

    @JvmStatic
    fun setCheckBoxColor(checkBox: AppCompatCheckBox?, uncheckedColor: Int, checkedColor: Int) {
        val colorStateList = ColorStateList(
                arrayOf(
                        intArrayOf(-android.R.attr.state_checked), // unchecked
                        intArrayOf( android.R.attr.state_checked)  // checked
                ),
                intArrayOf(
                        uncheckedColor,
                        checkedColor
                )
        )
        checkBox!!.supportButtonTintList = colorStateList
    }

    @JvmStatic
    fun getCursorY(et: EditText?): Int {
        val pos: Int = et!!.selectionStart
        val layout: Layout = et.layout
        val line: Int = layout.getLineForOffset(pos)
        val baseline: Int = layout.getLineBaseline(line)
        val ascent: Int = layout.getLineAscent(line)
        return baseline + ascent
    }
}
