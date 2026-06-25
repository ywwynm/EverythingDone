@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.util.Random

/**
 * 一个「色区」：颜色条 + 随机/从世界取色两图标 + 一行 R/G/B/Hex。
 *
 * 内部只有一个 [currentColor]，任一输入改它，再由它单向回刷其余视图（用
 * [isUpdating] 标志防止回环递归）。纯色 tab 用 1 个，渐变 tab 用 2 个（起始 / 结束）。
 */
class ColorAreaView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 当前色变更回调（用户操作或随机产生）。外部 [setColor] 默认不触发。 */
    var onColorChanged: ((color: Int) -> Unit)? = null

    /** 点「从世界取色」图标时触发，由宿主弹相机，取回后调 [setColor]。 */
    var onRequestPickFromWorld: (() -> Unit)? = null

    private val spectrum: ColorSpectrumBar
    private val btRandom: ImageView
    private val btWorld: ImageView
    private val etR: EditText
    private val etG: EditText
    private val etB: EditText
    private val etHex: EditText

    private val ilR: InputLayout
    private val ilG: InputLayout
    private val ilB: InputLayout
    private val ilHex: InputLayout

    private var currentColor: Int = Color.GRAY
    private var isUpdating: Boolean = false
    private val random = Random()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_color_area, this, true)

        spectrum = findViewById(R.id.spectrum_color_area)
        btRandom = findViewById(R.id.bt_color_area_random)
        btWorld = findViewById(R.id.bt_color_area_pick_from_world)
        etR = findViewById(R.id.et_color_area_r)
        etG = findViewById(R.id.et_color_area_g)
        etB = findViewById(R.id.et_color_area_b)
        etHex = findViewById(R.id.et_color_area_hex)

        ilR = InputLayout(context, findViewById(R.id.tv_color_area_r), etR, currentColor)
        ilG = InputLayout(context, findViewById(R.id.tv_color_area_g), etG, currentColor)
        ilB = InputLayout(context, findViewById(R.id.tv_color_area_b), etB, currentColor)
        ilHex = InputLayout(context, findViewById(R.id.tv_color_area_hex), etHex, currentColor)

        BackgroundUtil.installAppChromeCircleRipple(btRandom, context)
        BackgroundUtil.installAppChromeCircleRipple(btWorld, context)

        val rgbFilter = MinMaxFilter(0, 255)
        etR.filters = arrayOf(rgbFilter, *etR.filters)
        etG.filters = arrayOf(rgbFilter, *etG.filters)
        etB.filters = arrayOf(rgbFilter, *etB.filters)
        etHex.filters = arrayOf(HexFilter(), *etHex.filters)

        spectrum.onColorChanged = { color, fromUser ->
            if (fromUser) applyColor(color, SRC_BAR)
        }
        etR.addTextChangedListener(rgbWatcher())
        etG.addTextChangedListener(rgbWatcher())
        etB.addTextChangedListener(rgbWatcher())
        etHex.addTextChangedListener(hexWatcher())

        btRandom.setOnClickListener {
            applyColor(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)), SRC_EXTERNAL)
        }
        btWorld.setOnClickListener { onRequestPickFromWorld?.invoke() }

        applyColor(currentColor, SRC_EXTERNAL, notify = false)
    }

    fun getColor(): Int = currentColor

    /** 外部赋色（初始/从世界取色回流/工作态接力）。默认不回调 onColorChanged。 */
    fun setColor(color: Int, notify: Boolean = false) {
        applyColor(color, SRC_EXTERNAL, notify)
    }

    private fun applyColor(color: Int, source: Int, notify: Boolean = true) {
        if (isUpdating) return
        isUpdating = true
        val c = color or -0x1000000
        currentColor = c

        if (source != SRC_BAR) spectrum.setColor(c, notify = false)
        if (source != SRC_RGB) {
            ilR.setTextForEditText(Color.red(c).toString())
            ilG.setTextForEditText(Color.green(c).toString())
            ilB.setTextForEditText(Color.blue(c).toString())
        }
        if (source != SRC_HEX) {
            ilHex.setTextForEditText(hexString(c))
        }
        tintTools(c)
        updateAccent(c)

        isUpdating = false
        if (notify) onColorChanged?.invoke(c)
    }

    private fun tintTools(color: Int) {
        btRandom.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        btWorld.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun updateAccent(color: Int) {
        val bg = ThingBackground.pure(color)
        ilR.setAccentBackground(bg)
        ilG.setAccentBackground(bg)
        ilB.setAccentBackground(bg)
        ilHex.setAccentBackground(bg)
    }

    private fun rgbWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (isUpdating) return
            val r = etR.text.toString().toIntOrNull() ?: return
            val g = etG.text.toString().toIntOrNull() ?: return
            val b = etB.text.toString().toIntOrNull() ?: return
            if (r > 255 || g > 255 || b > 255) return
            applyColor(Color.rgb(r, g, b), SRC_RGB)
        }
    }

    private fun hexWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (isUpdating) return
            val digits = (s?.toString() ?: "").removePrefix("#")
            if (digits.length != 6) return
            val parsed = try {
                Color.parseColor("#$digits")
            } catch (_: IllegalArgumentException) {
                return
            }
            applyColor(parsed, SRC_HEX)
        }
    }

    /** 数值范围过滤器（含空串允许，便于删空再输）。 */
    private class MinMaxFilter(private val min: Int, private val max: Int) : InputFilter {
        override fun filter(
            source: CharSequence?, start: Int, end: Int,
            dest: Spanned?, dstart: Int, dend: Int
        ): CharSequence? {
            val builder = StringBuilder(dest?.toString() ?: "")
            builder.replace(dstart, dend, source?.subSequence(start, end)?.toString() ?: "")
            val result = builder.toString()
            if (result.isEmpty()) return null
            val value = result.toIntOrNull() ?: return ""
            return if (value in min..max) null else ""
        }
    }

    /** 只允许 # 与 hex 字符。 */
    private class HexFilter : InputFilter {
        override fun filter(
            source: CharSequence?, start: Int, end: Int,
            dest: Spanned?, dstart: Int, dend: Int
        ): CharSequence? {
            if (source == null) return null
            val sb = StringBuilder()
            for (i in start until end) {
                val ch = source[i]
                if (ch == '#' || ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') sb.append(ch)
            }
            return if (sb.length == end - start) null else sb
        }
    }

    companion object {
        private const val SRC_BAR = 0
        private const val SRC_RGB = 1
        private const val SRC_HEX = 2
        private const val SRC_EXTERNAL = 3

        private fun hexString(color: Int): String =
            String.format("#%06X", color and 0xFFFFFF)
    }
}
