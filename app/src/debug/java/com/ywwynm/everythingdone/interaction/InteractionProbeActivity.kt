package com.ywwynm.everythingdone.interaction

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.github.chrisbanes.photoview.PhotoView
import androidx.activity.result.contract.ActivityResultContracts
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.activities.UiInteractionActivity
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.BaseDialogFragment
import com.ywwynm.everythingdone.views.pickers.ColorPicker

/** 无记事数据的交互夹具；窗口、选择器、图片手势均使用生产实现。 */
class InteractionProbeActivity : UiInteractionActivity() {
    lateinit var photo: PhotoView
    lateinit var popup: ColorPicker
    lateinit var content: LinearLayout
    var ordinaryFirst = 0
    var ordinarySecond = 0
    val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        alert("registry-result").show(supportFragmentManager, "registry-result")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (80 * resources.displayMetrics.density).toInt(), 0,
                (24 * resources.displayMetrics.density).toInt())
        }
        setContentView(content)
        content.addView(Button(this).apply {
            text = "first"
            setOnClickListener { ordinaryFirst++ }
        })
        content.addView(Button(this).apply {
            text = "second"
            setOnClickListener { ordinarySecond++ }
        })
        photo = PhotoView(this)
        content.addView(photo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        photo.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
        popup = ColorPicker(this, content, Def.PickerType.COLOR_EDIT).apply {
            setOnPickFromCameraListener { alert("popup-next").show(supportFragmentManager, "popup-next") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 73) alert("legacy-result").show(supportFragmentManager, "legacy-result")
    }

    companion object {
        fun alert(title: String) = AlertDialogFragment().apply { setTitle(title); setContent(title) }
    }
}

class InteractionResultActivity : UiInteractionActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}

class InteractionParentDialog : BaseDialogFragment() {
    override fun getLayoutResource() = 0
    override fun createDialogContent(inflater: LayoutInflater, container: ViewGroup?): View =
        LinearLayout(inflater.context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = (280 * resources.displayMetrics.density).toInt()
            fun button(title: String, action: () -> Unit) {
                addView(Button(context).apply { text = title; setOnClickListener { action() } })
            }
            button("child") { InteractionProbeActivity.alert("child").show(parentFragmentManager, "child") }
            button("replace") {
                val manager = parentFragmentManager
                dismiss()
                InteractionProbeActivity.alert("replacement").show(manager, "replacement")
            }
            button("page") { startActivity(Intent(requireActivity(), SettingsActivity::class.java)) }
            button("close") { dismiss() }
        }
}
