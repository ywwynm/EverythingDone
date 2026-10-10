package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.thingfile.ThingFileUpdate
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable

/** 必须选定设置策略后才更新；生命周期关闭不等同于用户选择。 */
class TapUpdateSettingsDialog : BaseDialogFragment() {
    var onChoice: ((ThingFileUpdate.Settings) -> Unit)? = null
    private var choice: ThingFileUpdate.Settings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun getLayoutResource(): Int = R.layout.fragment_tap_update_settings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        val accent = ThingBackground.fromJson(arguments?.getString(KEY_BACKGROUND)) ?: App.defaultAccentBackground
        BackgroundUtil.applyTextBackground(f<TextView>(R.id.tap_update_settings_title), accent)
        fun option(id: Int, value: ThingFileUpdate.Settings) {
            f<TextView>(id)!!.apply {
                BackgroundUtil.applyTextBackground(this, accent)
                background = GradientRippleDrawable(accent, shapeOval = false, cornerRadiusPx = 0f)
                setOnClickListener { if (choice == null) { choice = value; dismiss() } }
            }
        }
        option(R.id.tap_update_keep, ThingFileUpdate.Settings.KEEP_CURRENT)
        option(R.id.tap_update_replace, ThingFileUpdate.Settings.USE_RECEIVED)
        return mContentView
    }

    override fun onDismiss(dialog: DialogInterface) {
        val callback = onChoice
        onChoice = null
        super.onDismiss(dialog)
        choice?.let { callback?.invoke(it) }
    }

    companion object {
        const val TAG = "TapUpdateSettings"
        private const val KEY_BACKGROUND = "thing_background"

        fun newInstance(background: ThingBackground) = TapUpdateSettingsDialog().apply {
            arguments = Bundle().apply { putString(KEY_BACKGROUND, background.toJson()) }
        }
    }
}
