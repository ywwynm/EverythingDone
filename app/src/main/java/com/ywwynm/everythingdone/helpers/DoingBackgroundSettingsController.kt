package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.fragments.ThreeOptionsDialogFragment
import com.ywwynm.everythingdone.model.DoingBackgroundCrops
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.UriPathConverter
import com.ywwynm.everythingdone.views.ThingCardCropEditorView
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.io.File
import java.io.IOException

/** 设置入口、图片导入及两套裁切草稿；做事页只消费已确认的配置。 */
class DoingBackgroundSettingsController(
    private val activity: FragmentActivity,
    private val chooseImage: () -> Unit
) : DefaultLifecycleObserver {
    private val preferences = activity.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
    private var pendingCrop = false
    private var importing = false

    init { activity.lifecycle.addObserver(this) }

    fun bind() {
        refreshLabel()
        activity.findViewById<View>(R.id.ll_doing_background_as_bt).setOnClickListener { showMenu() }
    }

    override fun onResume(owner: LifecycleOwner) {
        refreshLabel()
        if (pendingCrop) showCrop()
    }

    private fun currentFile(): File? = preferences.getString(Def.Meta.KEY_DOING_BACKGROUND, null)
        ?.let(::File)?.takeIf { it.isFile && it.canRead() }

    private fun refreshLabel() {
        val label = activity.findViewById<TextView>(R.id.tv_doing_background_path) ?: return
        val file = currentFile()
        label.text = file?.path ?: activity.getString(R.string.doing_background_default)
        label.textSize = if (file == null) 14f else 12f
        activity.findViewById<View>(R.id.ll_doing_background_as_bt).isEnabled = !importing
    }

    private fun showMenu() {
        val dialog = ThreeOptionsDialogFragment()
        dialog.setStartAction(R.drawable.act_default_drawer_header, R.string.doing_background_default) {
            preferences.edit().remove(Def.Meta.KEY_DOING_BACKGROUND)
                .remove(Def.Meta.KEY_DOING_BACKGROUND_CROPS).apply()
            refreshLabel()
            dialog.dismiss()
        }
        dialog.setMiddleAction(R.drawable.act_select_image_as_drawer_header,
            R.string.settings_drawer_header_choose_image) {
            dialog.dismiss()
            chooseImage()
        }
        if (currentFile() != null) {
            dialog.setEndAction(R.drawable.act_adjust_drawer_header, R.string.settings_drawer_header_adjust_crop) {
                dialog.dismiss()
                showCrop()
            }
        }
        dialog.show(activity.supportFragmentManager, ThreeOptionsDialogFragment.TAG)
    }

    fun onImagePicked(uri: Uri?) {
        if (uri == null || importing) return
        importing = true
        refreshLabel()
        val context = activity.applicationContext
        // 文件提供器可能在云端；复制及解码均不占用 UI 线程。
        Thread({
            var copiedFile: File? = null
            val file = try {
                val original = try {
                    UriPathConverter.getLocalPathName(context, uri)?.let(::File)
                        ?.takeIf { it.isFile && it.canRead() }
                } catch (e: RuntimeException) {
                    // 提供器不支持路径查询时，仍可使用用户已授权的 URI 流。
                    Log.d(TAG, "图片提供器未返回可读文件路径", e)
                    null
                }
                val source = original ?: run {
                    val suffix = FileUtil.getPostfixFromMimeType(context, uri) ?: ".img"
                    val path = FileUtil.copyUriToFile(context, uri, suffix)
                        ?: throw IOException("无法复制所选图片")
                    File(path).also { copiedFile = it }
                }
                val request = Glide.with(context).asBitmap().load(source).submit(128, 128)
                try { request.get() } finally { Glide.with(context).clear(request) }
                source
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                // 外部文件提供器也可能抛出自定义运行时异常；导入失败保留原有设置。
                copiedFile?.delete()
                Log.w(TAG, "无法读取所选做事背景", e)
                null
            }
            Handler(Looper.getMainLooper()).post {
                importing = false
                if (file != null) {
                    preferences.edit().putString(Def.Meta.KEY_DOING_BACKGROUND, file.path)
                        .putString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, DoingBackgroundCrops().toJson()).apply()
                }
                if (!activity.isDestroyed && !activity.isFinishing) {
                    refreshLabel()
                    if (file == null) showImageError() else showCrop()
                }
            }
        }, "doing-background-import").start()
    }

    private fun showImageError() = Toast.makeText(activity, R.string.doing_background_image_unavailable,
        Toast.LENGTH_LONG).show()

    private fun showCrop() {
        if (currentFile() == null) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.supportFragmentManager.isStateSaved) {
            pendingCrop = true
            return
        }
        pendingCrop = false
        val manager = activity.supportFragmentManager
        if (manager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) != null) return
        MediaCropAppearanceDialogFragment.newInstance(MediaCropAppearanceDialogFragment.REQUEST_DOING_BACKGROUND)
            .show(manager, MediaCropAppearanceDialogFragment.TAG)
    }

    private fun windowSize(): android.graphics.Point = if (Build.VERSION.SDK_INT >= 30) {
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        android.graphics.Point(bounds.width(), bounds.height())
    } else DisplayUtil.getScreenSize(activity)

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()
    private fun dimension(id: Int) = activity.resources.getDimensionPixelSize(id)

    fun dialogWidth(): Int = minOf((windowSize().x - dp(32)).coerceAtLeast(1),
        dimension(R.dimen.thing_card_appearance_max_width))

    fun createContent(fragment: MediaCropAppearanceDialogFragment): MediaCropAppearanceDialogFragment.Content? {
        val file = currentFile() ?: return null
        val saved = fragment.restoredContentState?.takeIf { it.getString("path") == file.path }
        var crops = DoingBackgroundCrops.fromJson(saved?.getString("crops")
            ?: preferences.getString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, null))
        var landscape = saved?.getBoolean("landscape") ?: (windowSize().x > windowSize().y)
        var bitmap: Bitmap? = null
        var disposed = false
        val margin = dimension(R.dimen.app_chrome_dialog_title_margin_horizontal)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_app_chrome_surface_elevated_rounded)
        }
        val title = TextView(activity).apply {
            setText(R.string.settings_doing_background)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.app_chrome_dialog_title_text_size))
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            BackgroundUtil.applyTextBackground(this, App.defaultAccentBackground)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(margin, dimension(R.dimen.app_chrome_dialog_title_margin_top), margin, 0)
        })
        val accent = App.defaultAccentBackground
        val hintColor = ContextCompat.getColor(activity, R.color.app_chrome_on_surface_hint)
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        // 与纯色／渐变、计时数字实心／描边切换保持同一文字和触摸反馈样式。
        fun tab(text: Int) = TextView(activity).apply {
            setText(text)
            textSize = 15f
            setAllCaps(true)
            gravity = Gravity.CENTER
            isFocusable = true
            foreground = GradientRippleDrawable(accent, shapeOval = false, cornerRadiusPx = -1f)
            tabs.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val portraitTab = tab(R.string.doing_background_portrait)
        val landscapeTab = tab(R.string.doing_background_landscape)
        fun updateTabs() {
            listOf(portraitTab, landscapeTab).forEachIndexed { index, label ->
                val selected = landscape == (index == 1)
                label.isSelected = selected
                label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                BackgroundUtil.applyTextBackground(label, if (selected) accent else ThingBackground.pure(hintColor))
            }
        }
        updateTabs()
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(margin, dp(12), margin, 0) })
        val editor = ThingCardCropEditorView(activity).apply {
            contentDescription = activity.getString(R.string.doing_background_crop_hint)
        }
        fun saveFrame() {
            if (bitmap != null) crops = crops.withFrame(landscape, DoingBackgroundCrops.Frame(
                editor.getCropCenterX(), editor.getCropCenterY(), editor.getCropUserScale()))
        }
        fun showFrame() {
            val frame = crops.forOrientation(landscape)
            val size = windowSize()
            editor.setCropBitmap(bitmap, DoingBackgroundCrops.aspectRatio(size.x, size.y, landscape),
                frame.centerX, frame.centerY, frame.scale)
        }
        fun previewHeight(): Int = minOf(dp(440), (windowSize().y - dp(240)).coerceAtLeast(dp(96)))
        root.addView(editor, LinearLayout.LayoutParams(-1, previewHeight()).apply {
            setMargins(margin, dp(6), margin, 0)
        })
        showFrame()
        fun selectOrientation(nextLandscape: Boolean) {
            if (landscape != nextLandscape) {
                saveFrame()
                landscape = nextLandscape
                updateTabs()
                showFrame()
            }
        }
        portraitTab.setOnClickListener { selectOrientation(false) }
        landscapeTab.setOnClickListener { selectOrientation(true) }
        val buttons = LinearLayout(activity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fun button(text: Int, useAccent: Boolean, click: () -> Unit) = TextView(activity).apply {
            setText(text)
            gravity = Gravity.CENTER
            setAllCaps(true)
            BackgroundUtil.installAppChromeDialogActionButton(this, activity)
            if (useAccent) {
                BackgroundUtil.applyTextBackground(this, accent)
                GradientRippleDrawable.applyAccentRipple(this, accent, accent.color)
            } else {
                setTextColor(ContextCompat.getColor(activity, R.color.app_chrome_dialog_cancel))
            }
            layoutParams = LinearLayout.LayoutParams(-2, dimension(R.dimen.app_chrome_dialog_action_button_height))
                .apply { marginStart = dp(8) }
            setOnClickListener { click() }
        }
        buttons.addView(button(R.string.cancel, false) { fragment.dismiss() })
        val confirm = button(R.string.confirm, true) {
            saveFrame()
            preferences.edit().putString(Def.Meta.KEY_DOING_BACKGROUND_CROPS, crops.toJson()).apply()
            fragment.dismiss()
        }.apply { isEnabled = false; alpha = 0.4f }
        buttons.addView(confirm)
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply {
            val horizontal = dimension(R.dimen.app_chrome_dialog_action_row_margin_horizontal)
            setMargins(horizontal, dimension(R.dimen.app_chrome_dialog_action_row_margin_top), horizontal,
                dimension(R.dimen.app_chrome_dialog_action_row_margin_bottom))
        })
        val target = object : CustomTarget<Bitmap>(2048, 2048) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (disposed) return
                bitmap = resource
                showFrame()
                confirm.isEnabled = true
                confirm.alpha = 1f
            }
            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
                editor.setCropBitmap(null, 1.0, 0.5, 0.5, 1.0)
            }
            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (!disposed) { showImageError(); fragment.dismissAllowingStateLoss() }
            }
        }
        Glide.with(activity).asBitmap().load(file).dontTransform().into(target)
        return MediaCropAppearanceDialogFragment.Content(root,
            onDestroyView = { disposed = true; Glide.with(activity.applicationContext).clear(target) },
            onSaveInstanceState = { state: Bundle ->
                saveFrame()
                state.putString("path", file.path)
                state.putString("crops", crops.toJson())
                state.putBoolean("landscape", landscape)
            },
            onConfigurationChanged = {
                saveFrame()
                editor.layoutParams = editor.layoutParams.apply { height = previewHeight() }
                showFrame()
            })
    }

    companion object { private const val TAG = "DoingBackgroundSettings" }
}
