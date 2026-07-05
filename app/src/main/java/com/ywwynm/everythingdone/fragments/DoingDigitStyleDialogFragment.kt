package com.ywwynm.everythingdone.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.core.content.ContextCompat
import com.github.adnansm.timelytextview.TimelyView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Timer digit style chooser: one row per style previewing "01:29:36" (so the
 * hour/minute/second weight ladder shows), plus a Fill/Outline toggle. Persists
 * the choice to the app settings preferences; DoingActivity reads it on open.
 */
class DoingDigitStyleDialogFragment : BaseDialogFragment() {

    private var fill = true
    private var selected = "poppins"
    private var onChosen: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewExecutor: ExecutorService? = null
    private var previewGeneration = 0

    fun setOnChosen(cb: () -> Unit) {
        onChosen = cb
    }

    override fun getLayoutResource(): Int = R.layout.dialog_doing_digit_style

    override fun getDialogWindowWidthPx(): Int =
        dp(280f)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        val ctx = v!!.context
        val sp = ctx.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        selected = sp.getString(Def.Meta.KEY_DOING_DIGIT_STYLE, "poppins") ?: "poppins"
        fill = (sp.getString(Def.Meta.KEY_DOING_DIGIT_RENDER, "fill") ?: "fill") == "fill"

        val tvFill = f<TextView>(R.id.tv_ddc_fill)
        val tvOutline = f<TextView>(R.id.tv_ddc_outline)
        val tvTitle = f<TextView>(R.id.tv_ddc_title)
        val scroll = f<ScrollView>(R.id.sv_ddc_rows)
        val rows = f<LinearLayout>(R.id.ll_ddc_rows)
        val scrollIndicator = f<View>(R.id.view_ddc_scroll_indicator)
        val accentBackground = App.defaultAccentBackground
        val tabHintColor = ContextCompat.getColor(ctx, R.color.app_chrome_on_surface_hint)
        val selectedLabelColor = BackgroundUtil.onColor(
            accentBackground, BackgroundUtil.ON_ALPHA_TERTIARY
        )
        val rowRadius = dp(8f).toFloat()
        BackgroundUtil.applyTextBackground(tvTitle, accentBackground)

        fun updateScrollIndicator() {
            scrollIndicator.visibility =
                if (scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
        }

        fun scrollToSelectedRow() {
            scroll.post {
                val index = STYLES.indexOfFirst { it.first == selected }
                if (index >= 0) {
                    val child = rows.getChildAt(index)
                    if (child != null) {
                        scroll.scrollTo(0, (child.top - dp(8f)).coerceAtLeast(0))
                    }
                }
                updateScrollIndicator()
            }
        }

        fun buildRows() {
            val generation = ++previewGeneration
            restartPreviewQueue()
            val appContext = ctx.applicationContext
            rows.removeAllViews()
            val wPx = (getDialogWindowWidthPx() - dp(64f)).coerceAtLeast(dp(220f))
            val hPx = dp(52f)
            val pendingPreviews = ArrayList<Pair<ImageView, PreviewKey>>()
            for ((id, label) in STYLES) {
                val isSelected = id == selected
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.VERTICAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(92f)
                ).apply {
                    setMargins(dp(12f), dp(4f), dp(12f), dp(4f))
                }
                row.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                row.background = if (isSelected) selectedRowBackground(accentBackground, rowRadius) else null
                row.foreground = if (isSelected) null else GradientRippleDrawable(
                    accentBackground, shapeOval = false, cornerRadiusPx = rowRadius
                )
                row.isClickable = true
                row.isFocusable = true

                val tv = TextView(ctx)
                tv.text = label
                tv.textSize = 13f
                tv.includeFontPadding = false
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.setTextColor(if (isSelected) selectedLabelColor else tabHintColor)
                tv.setTypeface(Typeface.DEFAULT, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                tv.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20f)
                )
                row.addView(tv)

                val iv = ImageView(ctx)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f))
                lp.setMargins(0, dp(4f), 0, 0)
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                val key = previewKey(id, fill, isSelected, accentBackground, wPx, hPx)
                iv.tag = key
                val cached = PREVIEW_CACHE.get(key)
                if (cached != null) {
                    iv.alpha = 1f
                    iv.setImageBitmap(cached)
                } else {
                    iv.alpha = 0f
                    iv.setImageDrawable(null)
                    pendingPreviews.add(iv to key)
                }
                row.addView(iv)

                row.setOnClickListener {
                    sp.edit()
                        .putString(Def.Meta.KEY_DOING_DIGIT_STYLE, id)
                        .putString(Def.Meta.KEY_DOING_DIGIT_RENDER, if (fill) "fill" else "outline")
                        .apply()
                    onChosen?.invoke()
                    dismiss()
                }
                rows.addView(row)
            }
            pendingPreviews.sortedBy { if (it.second.isSelectedPreview()) 0 else 1 }
                .forEach { (iv, key) -> loadPreviewAsync(appContext, iv, key, generation) }
            prefetchRenderMode(appContext, !fill, selected, accentBackground, wPx, hPx)
            scrollToSelectedRow()
        }

        fun updateToggle() {
            styleTab(tvFill, fill, accentBackground, tabHintColor)
            styleTab(tvOutline, !fill, accentBackground, tabHintColor)
        }

        tvFill.setOnClickListener {
            if (!fill) { fill = true; updateToggle(); buildRows() }
        }
        tvOutline.setOnClickListener {
            if (fill) { fill = false; updateToggle(); buildRows() }
        }

        updateToggle()
        scroll.viewTreeObserver.addOnScrollChangedListener { updateScrollIndicator() }
        buildRows()
        return v
    }

    override fun onDestroyView() {
        previewGeneration++
        previewExecutor?.shutdownNow()
        previewExecutor = null
        super.onDestroyView()
    }

    private fun styleTab(
        tab: TextView,
        selected: Boolean,
        accentBackground: ThingBackground,
        hintColor: Int
    ) {
        val ripple = tab.foreground as? GradientRippleDrawable
        if (ripple != null) {
            ripple.updateBackground(accentBackground)
        } else {
            tab.foreground = GradientRippleDrawable(
                accentBackground, shapeOval = false, cornerRadiusPx = -1f
            )
        }
        if (selected) {
            tab.setTypeface(Typeface.DEFAULT_BOLD)
            BackgroundUtil.applyTextBackground(tab, accentBackground)
        } else {
            tab.setTypeface(Typeface.DEFAULT)
            BackgroundUtil.applyTextBackground(tab, ThingBackground.pure(hintColor))
        }
    }

    private fun selectedRowBackground(bg: ThingBackground, radiusPx: Float): GradientDrawable {
        return BackgroundUtil.makeTranslucentGradient(bg, 255).apply {
            cornerRadius = radiusPx
        }
    }

    private fun previewKey(
        style: String,
        fillMode: Boolean,
        isSelected: Boolean,
        accentBackground: ThingBackground,
        wPx: Int,
        hPx: Int
    ): PreviewKey {
        return if (isSelected) {
            PreviewKey(style, fillMode, Color.WHITE, Color.WHITE, wPx, hPx)
        } else {
            PreviewKey(style, fillMode, accentBackground.color, accentBackground.endColor, wPx, hPx)
        }
    }

    private fun loadPreviewAsync(
        appContext: Context,
        imageView: ImageView,
        key: PreviewKey,
        generation: Int
    ) {
        ensurePreviewExecutor().execute {
            val bitmap = getOrRenderPreview(appContext, key)
            mainHandler.post {
                if (generation != previewGeneration || mContentView == null || imageView.tag != key) {
                    return@post
                }
                imageView.animate().cancel()
                imageView.alpha = 0f
                imageView.setImageBitmap(bitmap)
                imageView.animate().alpha(1f).setDuration(PREVIEW_FADE_IN_MS).start()
            }
        }
    }

    private fun prefetchRenderMode(
        appContext: Context,
        fillMode: Boolean,
        selectedStyle: String,
        accentBackground: ThingBackground,
        wPx: Int,
        hPx: Int
    ) {
        val keys = STYLES.map { (id, _) ->
            previewKey(id, fillMode, id == selectedStyle, accentBackground, wPx, hPx)
        }.filter { PREVIEW_CACHE.get(it) == null }
        if (keys.isEmpty()) return
        ensurePreviewExecutor().execute {
            for (key in keys) {
                if (Thread.currentThread().isInterrupted) return@execute
                getOrRenderPreview(appContext, key)
            }
        }
    }

    private fun ensurePreviewExecutor(): ExecutorService {
        val existing = previewExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { r ->
            Thread(r, "doing-digit-preview")
        }.also { previewExecutor = it }
    }

    private fun restartPreviewQueue() {
        previewExecutor?.shutdownNow()
        previewExecutor = null
    }

    private fun getOrRenderPreview(appContext: Context, key: PreviewKey): Bitmap {
        PREVIEW_CACHE.get(key)?.let { return it }
        val bitmap = TimelyView.renderClock(
            appContext, key.style, key.fillMode, key.startColor, key.endColor, key.wPx, key.hPx
        )
        PREVIEW_CACHE.put(key, bitmap)
        return bitmap
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private data class PreviewKey(
        val style: String,
        val fillMode: Boolean,
        val startColor: Int,
        val endColor: Int,
        val wPx: Int,
        val hPx: Int
    ) {
        fun isSelectedPreview(): Boolean =
            startColor == Color.WHITE && endColor == Color.WHITE
    }

    companion object {
        const val TAG = "DoingDigitStyleDialogFragment"
        private const val PREVIEW_CACHE_BYTES = 8 * 1024 * 1024
        private const val PREVIEW_FADE_IN_MS = 90L

        private val PREVIEW_CACHE = object : LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_BYTES) {
            override fun sizeOf(key: PreviewKey, value: Bitmap): Int = value.byteCount
        }

        /** id (asset name) -> display label. */
        val STYLES = listOf(
            "poppins" to "Poppins",
            "comfortaa" to "Comfortaa",
            "orbitron" to "Orbitron",
            "playfairdisplay" to "Playfair Display",
            "abrilfatface" to "Abril Fatface",
            "zillaslab" to "Zilla Slab",
            "lora" to "Lora",
            "dmserifdisplay" to "DM Serif Display",
            "jetbrainsmono" to "JetBrains Mono",
            "pacifico" to "Pacifico",
            "dancingscript" to "Dancing Script",
            "fraunces" to "Fraunces",
            "bodonimoda" to "Bodoni Moda",
            "librebodoni" to "Libre Bodoni",
            "cinzel" to "Cinzel",
            "librebaskerville" to "Libre Baskerville",
            "josefinsans" to "Josefin Sans",
            "exo2" to "Exo 2",
            "spacegrotesk" to "Space Grotesk",
            "limelight" to "Limelight",
            "righteous" to "Righteous",
            "poiretone" to "Poiret One",
            "majormonodisplay" to "Major Mono Display",
            "genos" to "Genos",
            "italiana" to "Italiana",
            "nixieone" to "Nixie One",
            "outfit" to "Outfit",
            "bigshouldersstencil" to "Big Shoulders Stencil",
            "sirinstencil" to "Sirin Stencil",
            "allertastencil" to "Allerta Stencil",
            "sairastencil" to "Saira Stencil",
            "stardosstencil" to "Stardos Stencil",
            "monoton" to "Monoton"
        )
    }
}
