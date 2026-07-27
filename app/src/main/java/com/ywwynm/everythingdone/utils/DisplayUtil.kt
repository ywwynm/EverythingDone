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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground

import java.util.Random
import kotlin.math.abs
import kotlin.math.max

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
     * Phase 1 of the color-system migration; see
     * docs/features/color-system-migration/plan.md.
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
        val end: Float = abs(start - 1)
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
    fun opaqueTintDrawable(context: Context, drawable: Drawable?, color: Int): Drawable? {
        if (drawable == null) return null

        val src = drawable.mutate()
        val width = if (src.intrinsicWidth > 0) {
            src.intrinsicWidth
        } else {
            (24 * getScreenDensity(context)).toInt()
        }
        val height = if (src.intrinsicHeight > 0) {
            src.intrinsicHeight
        } else {
            (24 * getScreenDensity(context)).toInt()
        }

        val sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(sourceBitmap)
        src.setBounds(0, 0, width, height)
        src.draw(sourceCanvas)

        val pixels = IntArray(width * height)
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var maxAlpha = 0
        for (pixel in pixels) {
            maxAlpha = max(maxAlpha, Color.alpha(pixel))
        }
        if (maxAlpha == 0) {
            sourceBitmap.recycle()
            return src
        }

        val targetAlpha = Color.alpha(color)
        val targetRgb = color and 0x00ffffff
        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i])
            pixels[i] = if (alpha == 0) {
                Color.TRANSPARENT
            } else {
                val scaledAlpha = (alpha * targetAlpha + maxAlpha / 2) / maxAlpha
                (scaledAlpha shl 24) or targetRgb
            }
        }

        val tintedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        tintedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        sourceBitmap.recycle()

        return BitmapDrawable(context.resources, tintedBitmap).also {
            it.setBounds(0, 0, width, height)
        }
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
        expandStatusBarViewAboveKitkat(statusBar, null)
    }

    @JvmStatic
    fun expandStatusBarViewAboveKitkat(
            statusBar: View?,
            onTopInsetChanged: java.util.function.IntConsumer?) {
        val target = statusBar!!
        chainDecorInsetsCallback(target, callbackKey(target, TAG_TOP_INSET_HEIGHT_CALLBACK)) {
            insets ->
            val topInset: Int = computeTopInset(insets)
            val vlp: ViewGroup.LayoutParams = target.layoutParams
            if (vlp.height != topInset) {
                vlp.height = topInset
                target.requestLayout()
            }
            onTopInsetChanged?.accept(topInset)
        }
    }

    @JvmStatic
    fun getCurrentTopSystemInset(view: View?): Int {
        val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(view!!) ?: return 0
        return computeTopInset(insets)
    }

    @JvmStatic
    fun applyTopInsetAsMargin(view: View?) {
        val target = view!!
        val initial: ViewGroup.MarginLayoutParams =
                target.layoutParams as ViewGroup.MarginLayoutParams
        if (target.getTag(TAG_TOP_INSET_MARGIN_ORIGINAL_TOP) == null) {
            target.setTag(TAG_TOP_INSET_MARGIN_ORIGINAL_TOP, initial.topMargin)
        }
        val originalTop = target.getTag(TAG_TOP_INSET_MARGIN_ORIGINAL_TOP) as Int
        chainDecorInsetsCallback(target, callbackKey(target, TAG_TOP_INSET_MARGIN_CALLBACK)) {
            insets ->
            val top: Int = computeTopInset(insets)
            val mlp: ViewGroup.MarginLayoutParams =
                    target.layoutParams as ViewGroup.MarginLayoutParams
            val newTop = originalTop + top
            if (mlp.topMargin != newTop) {
                mlp.topMargin = newTop
                target.layoutParams = mlp
            }
        }
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
        val target = view!!
        val initial: ViewGroup.MarginLayoutParams =
                target.layoutParams as ViewGroup.MarginLayoutParams
        if (target.getTag(TAG_BOTTOM_INSET_MARGIN_ORIGINAL_BOTTOM) == null) {
            target.setTag(TAG_BOTTOM_INSET_MARGIN_ORIGINAL_BOTTOM, initial.bottomMargin)
        }
        val originalBottom = target.getTag(TAG_BOTTOM_INSET_MARGIN_ORIGINAL_BOTTOM) as Int
        chainDecorInsetsCallback(target, callbackKey(target, TAG_BOTTOM_INSET_MARGIN_CALLBACK)) {
            insets ->
            val bottom: Int = computeBottomInset(insets)
            val mlp: ViewGroup.MarginLayoutParams =
                    target.layoutParams as ViewGroup.MarginLayoutParams
            val newBottom = originalBottom + bottom
            if (mlp.bottomMargin != newBottom) {
                mlp.bottomMargin = newBottom
                target.layoutParams = mlp
            }
        }
    }

    @JvmStatic
    fun clearBottomInsetAsMargin(view: View?) {
        val target = view!!
        removeDecorInsetsCallback(target, callbackKey(target, TAG_BOTTOM_INSET_MARGIN_CALLBACK))
        val originalBottom = target.getTag(TAG_BOTTOM_INSET_MARGIN_ORIGINAL_BOTTOM) as Int?
        if (originalBottom != null) {
            val mlp: ViewGroup.MarginLayoutParams =
                    target.layoutParams as ViewGroup.MarginLayoutParams
            if (mlp.bottomMargin != originalBottom) {
                mlp.bottomMargin = originalBottom
                target.layoutParams = mlp
            }
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
        val target = view!!
        if (target.getTag(TAG_BOTTOM_INSET_PADDING_ORIGINAL) == null) {
            target.setTag(TAG_BOTTOM_INSET_PADDING_ORIGINAL, intArrayOf(
                    target.paddingLeft,
                    target.paddingTop,
                    target.paddingRight,
                    target.paddingBottom
            ))
        }
        val original = target.getTag(TAG_BOTTOM_INSET_PADDING_ORIGINAL) as IntArray
        chainDecorInsetsCallback(target, callbackKey(target, TAG_BOTTOM_INSET_PADDING_CALLBACK)) {
            insets ->
            val bottom: Int = computeBottomInset(insets)
            target.setPadding(original[0], original[1], original[2], original[3] + bottom)
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
        val target = view!!
        if (target.getTag(TAG_BOTTOM_INSET_SCROLL_PADDING_ORIGINAL) == null) {
            target.setTag(TAG_BOTTOM_INSET_SCROLL_PADDING_ORIGINAL, intArrayOf(
                    target.paddingLeft,
                    target.paddingTop,
                    target.paddingRight,
                    target.paddingBottom
            ))
        }
        val original = target.getTag(TAG_BOTTOM_INSET_SCROLL_PADDING_ORIGINAL) as IntArray
        if (target is ViewGroup) {
            target.clipToPadding = false
        }
        chainDecorInsetsCallback(target, callbackKey(target, TAG_BOTTOM_INSET_SCROLL_PADDING_CALLBACK)) {
            insets ->
            val bars: androidx.core.graphics.Insets = computeBarsInset(insets)
            target.setPadding(original[0], original[1], original[2], original[3] + bars.bottom)
        }
    }

    /**
     * Immersive Thing List: own the RecyclerView's top AND bottom padding from a
     * single decor-inset callback. The list draws behind the top App Chrome
     * (status bar + actionbar are reserved via top padding, with
     * `clipToPadding=false` so cards can scroll behind them) and clears the
     * navigation bar at rest (bottom padding).
     *
     * Combining top+bottom in one callback avoids the two-callback padding
     * clobber: a separate top setter plus [applyBottomInsetAsScrollPadding]
     * would each rewrite the full padding from its own captured original and
     * fight on every inset dispatch. Use this INSTEAD of
     * [applyBottomInsetAsScrollPadding] for that list.
     *
     * `paddingTop = original.top + systemBars.top + actionBarSize`;
     * `paddingBottom = original.bottom + systemBars.bottom`.
     *
     * `actionBarSize` is resolved per dispatch, so a landscape/tablet
     * configuration change that shrinks the actionbar is picked up on the next
     * inset pass without re-registration.
     */
    @JvmStatic
    fun applyImmersiveListInsetPadding(view: View?) {
        val target = view!!
        if (target.getTag(TAG_IMMERSIVE_LIST_PADDING_ORIGINAL) == null) {
            target.setTag(TAG_IMMERSIVE_LIST_PADDING_ORIGINAL, intArrayOf(
                    target.paddingLeft,
                    target.paddingTop,
                    target.paddingRight,
                    target.paddingBottom
            ))
        }
        val original = target.getTag(TAG_IMMERSIVE_LIST_PADDING_ORIGINAL) as IntArray
        if (target is ViewGroup) {
            target.clipToPadding = false
        }
        chainDecorInsetsCallback(target, callbackKey(target, TAG_IMMERSIVE_LIST_PADDING_CALLBACK)) {
            insets ->
            val bars: androidx.core.graphics.Insets = computeBarsInset(insets)
            target.setPadding(
                    original[0],
                    original[1] + bars.top + resolveActionBarSize(target.context),
                    original[2],
                    original[3] + bars.bottom
            )
        }
    }

    /**
     * Current `?attr/actionBarSize` in pixels for the given (themed) context,
     * falling back to 56dp. Reflects the active configuration (56dp portrait /
     * 48dp landscape phone), so callers reading it per layout pass stay correct
     * across rotation.
     */
    @JvmStatic
    fun resolveActionBarSize(context: Context): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            android.util.TypedValue.complexToDimensionPixelSize(
                    tv.data, context.resources.displayMetrics)
        } else {
            (56 * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Tag id used by [chainDecorInsetsCallback] to attach keyed per-target
     * callbacks onto the activity's decor view. Hash of a stable
     * unique string — no R.id.* dependency needed.
     */
    private val TAG_DECOR_INSETS_CHAIN: Int =
            "DisplayUtil#decorInsetsChain".hashCode()
    private val TAG_TOP_INSET_HEIGHT_CALLBACK: Int =
            "DisplayUtil#topInsetHeightCallback".hashCode()
    private val TAG_TOP_INSET_MARGIN_ORIGINAL_TOP: Int =
            "DisplayUtil#topInsetMarginOriginalTop".hashCode()
    private val TAG_TOP_INSET_MARGIN_CALLBACK: Int =
            "DisplayUtil#topInsetMarginCallback".hashCode()
    private val TAG_BOTTOM_INSET_MARGIN_ORIGINAL_BOTTOM: Int =
            "DisplayUtil#bottomInsetMarginOriginalBottom".hashCode()
    private val TAG_BOTTOM_INSET_MARGIN_CALLBACK: Int =
            "DisplayUtil#bottomInsetMarginCallback".hashCode()
    private val TAG_BOTTOM_INSET_PADDING_ORIGINAL: Int =
            "DisplayUtil#bottomInsetPaddingOriginal".hashCode()
    private val TAG_BOTTOM_INSET_PADDING_CALLBACK: Int =
            "DisplayUtil#bottomInsetPaddingCallback".hashCode()
    private val TAG_BOTTOM_INSET_SCROLL_PADDING_ORIGINAL: Int =
            "DisplayUtil#bottomInsetScrollPaddingOriginal".hashCode()
    private val TAG_BOTTOM_INSET_SCROLL_PADDING_CALLBACK: Int =
            "DisplayUtil#bottomInsetScrollPaddingCallback".hashCode()
    private val TAG_IMMERSIVE_LIST_PADDING_ORIGINAL: Int =
            "DisplayUtil#immersiveListPaddingOriginal".hashCode()
    private val TAG_IMMERSIVE_LIST_PADDING_CALLBACK: Int =
            "DisplayUtil#immersiveListPaddingCallback".hashCode()

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
            callbackKey: Int,
            callback: java.util.function.Consumer<WindowInsetsCompat>) {
        val install: Runnable = object : Runnable {
            override fun run() {
                val decor: View = target.getRootView()
                var chain: MutableMap<Int, java.util.function.Consumer<WindowInsetsCompat>>? =
                        decor.getTag(TAG_DECOR_INSETS_CHAIN)
                                as MutableMap<Int, java.util.function.Consumer<WindowInsetsCompat>>?
                if (chain == null) {
                    val map: MutableMap<Int, java.util.function.Consumer<WindowInsetsCompat>> =
                            java.util.LinkedHashMap<Int, java.util.function.Consumer<WindowInsetsCompat>>()
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
                    decor.setTag(TAG_DECOR_INSETS_CHAIN, map)
                    chain = map
                    // Stable-state path — fired on insets changes outside an
                    // IME animation (rotation, multi-window, gesture-nav
                    // entering / leaving). Skipped during IME animations to
                    // avoid the pre-animation target-insets flash described
                    // above.
                    ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                        if (!imeAnimating[0]) {
                            for (c in java.util.ArrayList(map.values)) {
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
                                    for (c in java.util.ArrayList(map.values)) {
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
                                            for (c in java.util.ArrayList(map.values)) {
                                                c.accept(current)
                                            }
                                        }
                                    }
                                }
                            })
                }
                chain[callbackKey] = callback
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

    @Suppress("UNCHECKED_CAST")
    private fun removeDecorInsetsCallback(target: View, callbackKey: Int) {
        val decor: View = target.rootView ?: return
        val chain: MutableMap<Int, java.util.function.Consumer<WindowInsetsCompat>>? =
                decor.getTag(TAG_DECOR_INSETS_CHAIN)
                        as MutableMap<Int, java.util.function.Consumer<WindowInsetsCompat>>?
        chain?.remove(callbackKey)
    }

    private fun callbackKey(target: View, helperTag: Int): Int {
        return System.identityHashCode(target) * 31 + helperTag
    }

    /** `max(systemBars.bottom + displayCutout.bottom, ime.bottom)` —
     *  picks the larger of "gesture / 3-button nav bar" or "soft-keyboard
     *  height" so a sticky bottom view sits above either system overlay. */
    private fun computeBottomInset(insets: WindowInsetsCompat): Int {
        val bars: androidx.core.graphics.Insets = computeBarsInset(insets)
        val ime: androidx.core.graphics.Insets = insets.getInsets(
                WindowInsetsCompat.Type.ime())
        return max(bars.bottom, ime.bottom)
    }

    private fun computeTopInset(insets: WindowInsetsCompat): Int {
        return computeBarsInset(insets).top
    }

    private fun computeBarsInset(insets: WindowInsetsCompat): androidx.core.graphics.Insets {
        return insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout())
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

        if (res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            span++
        }
        if (isTablet(context)) {
            span++
        }

        val basePadding: Int = res.getDimensionPixelSize(R.dimen.thing_card_outer_spacing)

        return (res.displayMetrics.widthPixels - basePadding * 2 * (span + 1)) / span
    }

    private var sSldMap: SparseArray<StateListDrawable?>? = null

    //private static HashMap<Integer, StateListDrawable> sSldMap;

    @JvmStatic
    fun setRippleColorForCardView(cardView: CardView?, color: Int) {
        val fg = cardView?.foreground
        if (fg is RippleDrawable) {
            fg.setColor(ColorStateList.valueOf(color))
        }
    }

    @JvmStatic
    fun setSeekBarColor(seekBar: SeekBar?, color: Int) {
        setSeekBarBackground(seekBar, ThingBackground.pure(color))
    }

    /**
     * [inactiveTrackColor] 为未播/未选中那段轨道的颜色；默认 [R.color.app_chrome_on_surface_hint]
     * 是给 App Chrome 面用的，压在彩色内容（如 FableSol 水体）上会偏深，这类调用方可以自己
     * 传一个更淡的颜色，其余调用方行为不变。
     */
    @JvmStatic
    @JvmOverloads
    fun setSeekBarBackground(
            seekBar: SeekBar?,
            background: ThingBackground?,
            inactiveTrackColor: Int? = null
    ) {
        if (seekBar == null) return
        val safeBackground = background ?: App.defaultAccentBackground
        seekBar.progressTintList = null
        seekBar.progressBackgroundTintList = null
        seekBar.secondaryProgressTintList = null
        seekBar.progressDrawable =
                buildSeekBarProgressDrawable(seekBar, safeBackground, inactiveTrackColor)
        seekBar.thumb = buildSeekBarThumbDrawable(seekBar, safeBackground)
    }

    private fun buildSeekBarThumbDrawable(
            seekBar: SeekBar,
            background: ThingBackground
    ): Drawable {
        val density = seekBar.resources.displayMetrics.density
        val size = max(16, (20f * density).toInt())
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(size, size)
            if (background.mode === ThingBackground.Mode.GRADIENT) {
                orientation = toGradientDrawableOrientation(background.orientation)
                colors = intArrayOf(background.color, background.endColor)
            } else {
                setColor(background.color)
            }
        }
    }

    /**
     * 给普通 [android.widget.ProgressBar] 套上与滑杆**完全同源**的渐变轨道。
     *
     * 之前导出进度条用的是 `progressTintList = ColorStateList.valueOf(accent.color)`——那只是
     * 渐变的起点单色，换色时看不出渐变方向，与同一个 Dialog 里的其它强调色元素也对不上。
     * 复用 [SeekBarTrackDrawable] 就不会再出现两套着色逻辑。
     *
     * 不定长（indeterminate）那一档仍只能用单色：平台的不定长动画是另一个 drawable，
     * 只接受 tint，塞不进渐变。
     */
    @JvmOverloads
    @JvmStatic
    fun setProgressBarBackground(
            progressBar: android.widget.ProgressBar?,
            background: ThingBackground?,
            inactiveTrackColor: Int? = null
    ) {
        if (progressBar == null) return
        val safeBackground = background ?: App.defaultAccentBackground
        progressBar.progressTintList = null
        progressBar.progressBackgroundTintList = null
        progressBar.secondaryProgressTintList = null
        val density = progressBar.resources.displayMetrics.density
        val trackHeight = max(2, (4f * density).toInt())
        progressBar.progressDrawable = LayerDrawable(
                arrayOf(
                        SeekBarTrackDrawable(
                                ThingBackground.pure(
                                        inactiveTrackColor ?: ContextCompat.getColor(
                                                progressBar.context,
                                                R.color.app_chrome_on_surface_hint
                                        )
                                ),
                                trackHeight
                        ),
                        SeekBarTrackDrawable(
                                ThingBackground.pure(Color.TRANSPARENT), trackHeight
                        ),
                        ClipDrawable(
                                SeekBarTrackDrawable(safeBackground, trackHeight),
                                Gravity.LEFT,
                                ClipDrawable.HORIZONTAL
                        )
                )
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
        }
        progressBar.indeterminateTintList = ColorStateList.valueOf(safeBackground.color)
    }

    private fun buildSeekBarProgressDrawable(
            seekBar: SeekBar,
            background: ThingBackground,
            inactiveTrackColor: Int? = null
    ): Drawable {
        val density = seekBar.resources.displayMetrics.density
        val trackHeight = max(2, (4f * density).toInt())
        val activeTrack = ClipDrawable(
                SeekBarTrackDrawable(background, trackHeight),
                Gravity.LEFT,
                ClipDrawable.HORIZONTAL
        ).apply {
            level = (seekBar.progress * 10000f / max(1, seekBar.max)).toInt()
        }
        return LayerDrawable(
                arrayOf(
                        SeekBarTrackDrawable(
                                ThingBackground.pure(inactiveTrackColor ?: ContextCompat.getColor(
                                        seekBar.context,
                                        R.color.app_chrome_on_surface_hint
                                )),
                                trackHeight
                        ),
                        SeekBarTrackDrawable(ThingBackground.pure(Color.TRANSPARENT), trackHeight),
                        activeTrack
                )
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
        }
    }

    private class SeekBarTrackDrawable(
            private val background: ThingBackground,
            private val trackHeight: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackRect = RectF()

        init {
            updatePaint()
        }

        override fun draw(canvas: Canvas) {
            val currentBounds = bounds
            if (currentBounds.width() <= 0 || currentBounds.height() <= 0) return

            val centerY = currentBounds.exactCenterY()
            val top = centerY - trackHeight / 2f
            val bottom = centerY + trackHeight / 2f
            val radius = trackHeight / 2f
            trackRect.set(
                    currentBounds.left.toFloat(),
                    top,
                    currentBounds.right.toFloat(),
                    bottom
            )
            canvas.drawRoundRect(trackRect, radius, radius, paint)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            updatePaint()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        @Deprecated("Deprecated in Drawable")
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicHeight(): Int = trackHeight

        private fun updatePaint() {
            paint.shader = null
            if (background.mode === ThingBackground.Mode.GRADIENT && bounds.width() > 0) {
                paint.shader = createGradientShader(bounds, background)
            } else {
                paint.color = background.color
            }
        }
    }

    private fun createGradientShader(bounds: Rect, background: ThingBackground): LinearGradient {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = bounds.right.toFloat()
        val bottom = bounds.bottom.toFloat()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val points = when (background.orientation) {
            ThingBackground.Orientation.L_R -> floatArrayOf(left, centerY, right, centerY)
            ThingBackground.Orientation.T_B -> floatArrayOf(centerX, top, centerX, bottom)
            ThingBackground.Orientation.LT_RB -> floatArrayOf(left, top, right, bottom)
            ThingBackground.Orientation.RT_LB -> floatArrayOf(right, top, left, bottom)
            ThingBackground.Orientation.LB_RT -> floatArrayOf(left, bottom, right, top)
            ThingBackground.Orientation.RB_LT -> floatArrayOf(right, bottom, left, top)
            ThingBackground.Orientation.R_L -> floatArrayOf(right, centerY, left, centerY)
            ThingBackground.Orientation.B_T -> floatArrayOf(centerX, bottom, centerX, top)
        }
        return LinearGradient(
                points[0],
                points[1],
                points[2],
                points[3],
                background.color,
                background.endColor,
                Shader.TileMode.CLAMP
        )
    }

    private fun toGradientDrawableOrientation(
            orientation: ThingBackground.Orientation
    ): GradientDrawable.Orientation {
        return when (orientation) {
            ThingBackground.Orientation.L_R -> GradientDrawable.Orientation.LEFT_RIGHT
            ThingBackground.Orientation.T_B -> GradientDrawable.Orientation.TOP_BOTTOM
            ThingBackground.Orientation.LT_RB -> GradientDrawable.Orientation.TL_BR
            ThingBackground.Orientation.RT_LB -> GradientDrawable.Orientation.TR_BL
            ThingBackground.Orientation.LB_RT -> GradientDrawable.Orientation.BL_TR
            ThingBackground.Orientation.RB_LT -> GradientDrawable.Orientation.BR_TL
            ThingBackground.Orientation.R_L -> GradientDrawable.Orientation.RIGHT_LEFT
            ThingBackground.Orientation.B_T -> GradientDrawable.Orientation.BOTTOM_TOP
        }
    }

    @JvmStatic
    fun setButtonColor(button: Button?, color: Int) {
        button!!.background.setColorFilter(color, PorterDuff.Mode.MULTIPLY)
    }

    @JvmStatic
    fun setCheckBoxColor(checkBox: AppCompatCheckBox?, accentColor: Int) {
        if (checkBox == null) return
        BackgroundUtil.applyCheckboxAccent(checkBox, ThingBackground.pure(accentColor))
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
