@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.ColorNameMatcher

import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

open class CameraColorSamplingDialogFragment : BaseDialogFragment() {

    interface OnColorListener {
        fun onPreviewColor(color: Int)
        fun onUseColor(color: Int)
        fun onCancelColorSampling()
    }

    private var mListener: OnColorListener? = null
    private var mInitialColor: Int = Color.WHITE
    private var mLastColor: Int = 0
    private var mAccepted = false
    private var mCameraProvider: ProcessCameraProvider? = null
    private var mAnalysisExecutor: ExecutorService? = null
    private val mMainHandler = Handler(Looper.getMainLooper())

    private var mLastAnalyzedAt = 0L
    private var mLastPublishedColor = 0

    private var mTvName: TextView? = null
    private var mTvEnglishName: TextView? = null
    private var mTvHex: TextView? = null
    private var mPreviewBar: View? = null
    private var mUseButton: TextView? = null

    override fun getLayoutResource(): Int = R.layout.fragment_camera_color_sampling

    override fun getDialogWindowWidthPx(): Int {
        return (320 * resources.displayMetrics.density).toInt()
    }

    open fun setInitialColor(color: Int) {
        mInitialColor = color
        mLastColor = color
        ensureArguments().putInt(ARG_INITIAL_COLOR, color)
    }

    open fun setOnColorListener(listener: OnColorListener?) {
        mListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        if (args != null) {
            mInitialColor = args.getInt(ARG_INITIAL_COLOR, mInitialColor)
            mLastColor = mInitialColor
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val previewView: PreviewView = f(R.id.pv_camera_color)!!
        mTvName = f(R.id.tv_camera_color_name)
        mTvEnglishName = f(R.id.tv_camera_color_english_name)
        mTvHex = f(R.id.tv_camera_color_hex)
        mPreviewBar = f(R.id.v_camera_color_preview_bar)
        mUseButton = f(R.id.tv_use_as_bt_camera_color)

        installPillOutline(mPreviewBar)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        f<TextView>(R.id.tv_cancel_as_bt_camera_color)!!.setOnClickListener {
            dismiss()
        }
        mUseButton!!.setOnClickListener {
            mAccepted = true
            mListener?.onUseColor(mLastColor)
            dismiss()
        }

        bindColor(mInitialColor)
        startCamera(previewView)

        return mContentView
    }

    override fun onDestroyView() {
        mCameraProvider?.unbindAll()
        mAnalysisExecutor?.shutdown()
        if (!mAccepted) {
            mListener?.onCancelColorSampling()
        }
        super.onDestroyView()
    }

    private fun installPillOutline(view: View?) {
        view ?: return
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera(previewView: PreviewView) {
        val activity = activity ?: return
        mAnalysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val lifecycleOwner = activity as? LifecycleOwner ?: return@addListener
                mCameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(mAnalysisExecutor!!) { image ->
                    analyze(image)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (_: Exception) {
                mTvName?.setText(R.string.camera_color_unavailable)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val color = sampleCenterColor(image)
            if (!shouldPublish(color)) return
            val context = activity ?: return
            val description = ColorNameMatcher.describeColor(context, color)
            mMainHandler.post {
                if (!isAdded) return@post
                mLastColor = color
                mListener?.onPreviewColor(color)
                bindColor(description)
            }
        } finally {
            image.close()
        }
    }

    private fun shouldPublish(color: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - mLastAnalyzedAt < 160L) return false
        if (mLastPublishedColor != 0 && colorDelta(color, mLastPublishedColor) < 10) return false
        mLastAnalyzedAt = now
        mLastPublishedColor = color
        return true
    }

    private fun colorDelta(c1: Int, c2: Int): Int {
        return abs(Color.red(c1) - Color.red(c2)) +
            abs(Color.green(c1) - Color.green(c2)) +
            abs(Color.blue(c1) - Color.blue(c2))
    }

    private fun bindColor(color: Int) {
        bindColor(ColorNameMatcher.describeColor(activity!!, color))
    }

    private fun bindColor(description: ColorNameMatcher.ColorDescription) {
        val match = description.match
        mTvName?.text = match.localizedName
        if (match.localizedName == match.englishName) {
            mTvEnglishName?.text = ""
        } else {
            mTvEnglishName?.text = match.englishName
        }
        mTvHex?.text = description.hex
        mPreviewBar?.let {
            BackgroundUtil.applyBackground(it, ThingBackground.pure(description.color))
        }
        mUseButton?.setTextColor(description.color)
    }

    private fun sampleCenterColor(image: ImageProxy): Int {
        val crop = image.cropRect
        val cx = crop.centerX()
        val cy = crop.centerY()
        val radius = 5
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0
        for (y in cy - radius..cy + radius step 2) {
            val yy = min(max(y, crop.top), crop.bottom - 1)
            for (x in cx - radius..cx + radius step 2) {
                val xx = min(max(x, crop.left), crop.right - 1)
                val c = yuvToRgb(image, xx, yy)
                rSum += Color.red(c)
                gSum += Color.green(c)
                bSum += Color.blue(c)
                count++
            }
        }
        if (count == 0) return mLastColor
        return Color.rgb(rSum / count, gSum / count, bSum / count)
    }

    private fun yuvToRgb(image: ImageProxy, x: Int, y: Int): Int {
        val planes = image.planes
        val yValue = planeValue(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, x, y)
        val uvX = x / 2
        val uvY = y / 2
        val uValue = planeValue(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, uvX, uvY)
        val vValue = planeValue(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, uvX, uvY)

        val yf = (yValue - 16).coerceAtLeast(0)
        val uf = uValue - 128
        val vf = vValue - 128
        val r = (1.164f * yf + 1.596f * vf).toInt().coerceIn(0, 255)
        val g = (1.164f * yf - 0.392f * uf - 0.813f * vf).toInt().coerceIn(0, 255)
        val b = (1.164f * yf + 2.017f * uf).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun planeValue(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
        val index = y * rowStride + x * pixelStride
        if (index < 0 || index >= buffer.limit()) return 128
        return buffer.get(index).toInt() and 0xFF
    }

    private fun ensureArguments(): Bundle {
        var args = arguments
        if (args == null) {
            args = Bundle()
            arguments = args
        }
        return args
    }

    companion object {
        const val TAG: String = "CameraColorSamplingDialogFragment"
        private const val ARG_INITIAL_COLOR = "initial_color"
    }
}
