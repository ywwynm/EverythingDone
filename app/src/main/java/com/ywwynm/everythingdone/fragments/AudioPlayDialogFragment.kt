@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

import com.github.adnansm.timelytextview.TimelyClockView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.views.recording.FableSolAudioFilePlayer
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolPerformanceMonitor
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolTuning
import com.ywwynm.everythingdone.views.recording.fablesol.FableSolVideoExportLauncher
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolGl
import com.ywwynm.everythingdone.views.recording.fablesol.WaveVisualizerFableSolHost

import java.io.File
import kotlin.math.roundToInt

/**
 * 音频附件的播放对话框：与录音对话框同一套 FableSol 水体（实时分析链），
 * 只是 PCM 来自正在播放的文件。上方是文件名 + [TimelyClockView] 计时器 + 进度滑杆，
 * 下方是「上一曲 / 播放暂停 / 下一曲」。当前音频播完后按附件顺序自动播下一个。
 */
class AudioPlayDialogFragment : BaseDialogFragment() {

    private var mActivity: Activity? = null

    private var mPaths: List<String> = emptyList()
    private var mIndex: Int = 0
    /** 播到最后一个的结尾且没有下一首：再点播放键从当前这首重新开始。 */
    private var mFinished: Boolean = false

    private var mPlayer: FableSolAudioFilePlayer? = null

    private var mTvFileName: TextView? = null
    private var mClockView: TimelyClockView? = null
    private var mSeekBar: SeekBar? = null
    private var mVisualizer: WaveVisualizerFableSolHost? = null

    private var mIvMainAction: ImageView? = null
    private var mIvPrevious: ImageView? = null
    private var mIvNext: ImageView? = null
    private var mIvExportVideo: ImageView? = null

    private var mAccentBackground: ThingBackground? = null

    private var mUserSeeking: Boolean = false
    private var mLastClockSecond: Long = -1L

    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
    private var mSensorThread: HandlerThread? = null
    private var mTiltSensorRegistered: Boolean = false
    /**
     * 画面是否跟随设备姿态（[FableSolTuning.liveTiltEnabled]）。对话框打开时读一次并固定：
     * 它同时决定要不要锁方向与要不要注册传感器，中途换值会让两者对不上。关掉时详情页恢复
     * 自动旋转。
     */
    private var mLiveTiltEnabled: Boolean = true
    private var mPerformanceMonitor: FableSolPerformanceMonitor? = null
    private var mOriginalRequestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var mOrientationLocked: Boolean = false
    private var mLockedRotation: Int = Surface.ROTATION_0
    private val mClockHandler: Handler = Handler(Looper.getMainLooper())

    override fun getLayoutResource(): Int = R.layout.fragment_play_audio

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        mActivity = activity
        val arguments = arguments
        mPaths = arguments?.getStringArrayList(KEY_PATHS) ?: emptyList()
        mIndex = (arguments?.getInt(KEY_INDEX) ?: 0).coerceIn(0, maxOf(mPaths.size - 1, 0))
        if (mPaths.isEmpty()) {
            dismiss()
            return mContentView
        }

        mLiveTiltEnabled = FableSolTuning.liveTiltEnabled(requireContext())
        lockHostOrientation()
        prepareTiltSensor()

        mTvFileName = f(R.id.tv_playing_audio_file_name)
        mClockView  = f(R.id.clock_play_audio)
        mSeekBar    = f(R.id.sb_audio_progress)
        mVisualizer = f(R.id.voice_visualizer)

        mIvMainAction = f(R.id.iv_play_main_action)
        mIvPrevious   = f(R.id.iv_play_previous_audio)
        mIvNext       = f(R.id.iv_play_next_audio)
        mIvExportVideo = f(R.id.iv_export_fablesol_video)

        val detail = mActivity as? DetailActivity
        mAccentBackground = detail?.getAccentBackground()
            ?: detail?.getAccentColor()?.let { ThingBackground.pure(it) }
            ?: App.defaultAccentBackground
        val accentBg: ThingBackground = mAccentBackground!!
        installTransportRipple(mIvMainAction, accentBg)
        installTransportRipple(mIvPrevious, accentBg)
        installTransportRipple(mIvNext, accentBg)
        tintTransportIcon(mIvPrevious, R.drawable.act_fablesol_previous, accentBg)
        tintTransportIcon(mIvNext, R.drawable.act_fablesol_next, accentBg)
        setUpExportVideoAction(accentBg)
        DisplayUtil.setSeekBarBackground(mSeekBar, accentBg, inactiveTrackColor())
        installClockContentAlignment()

        configureClockView(accentBg)
        mVisualizer!!.setThingBackground(accentBg)
        // 水体的模拟容器恒为 420dp 高、且以视口中心对齐；本对话框高 450dp，
        // 不补偿的话水线会比录音对话框高出 (450-420)/2 = 15dp。取景整体下移 15dp，
        // 让水线与录音对话框贴底位置逐 dp 一致，多出的 30dp 全部留在上方给滑杆。
        mVisualizer!!.setContentVerticalOffsetDp(CONTENT_OFFSET_DP)

        val player = FableSolAudioFilePlayer(mActivity!!.applicationContext)
        mPlayer = player
        player.linkFableSol(mVisualizer!!)
        player.setListener(mPlayerListener)

        setEvents()
        openTrack(mIndex, autoPlay = true)

        return mContentView
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val attributes = window.attributes
        attributes.preferredRefreshRate = TARGET_REFRESH_RATE
        window.attributes = attributes
        if (Build.VERSION.SDK_INT >= 35) {
            // 与录音对话框同理：水面是连续动画，必须退出「省电平衡」的帧率投票。
            window.isFrameRatePowerSavingsBalanced = false
        }
        if (BuildConfig.DEBUG && mPerformanceMonitor == null) {
            val monitor = FableSolPerformanceMonitor(window.context)
            mPerformanceMonitor = monitor
            mVisualizer?.setPerformanceMonitor(monitor)
            monitor.start(window)
        }
    }

    override fun onResume() {
        super.onResume()
        startTiltSensor()
    }

    override fun onPause() {
        stopTiltSensor()
        // 对话框不可见时不该继续出声：暂停后由用户自己决定何时续播。
        mPlayer?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        removeClockContentAlignment()
        stopTiltSensor()
        stopPerformanceMonitor()
        restoreHostOrientation()
        mVisualizer?.setContainerGravity(0f, 1f, 0f)
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        mClockHandler.removeCallbacks(mClockIntro)
        stopTiltSensor()
        stopPerformanceMonitor()
        restoreHostOrientation()
        val player = mPlayer
        mPlayer = null
        player?.release()
        super.onDismiss(dialog)
    }

    // ---- 播放控制 ----

    private fun openTrack(index: Int, autoPlay: Boolean) {
        if (index !in mPaths.indices) return
        val player = mPlayer ?: return
        mIndex = index
        mFinished = false
        mLastClockSecond = -1L
        mUserSeeking = false

        mTvFileName!!.text = File(mPaths[index]).name
        mSeekBar!!.max = PROGRESS_UNKNOWN_MAX
        mSeekBar!!.progress = 0
        mClockView!!.setTimeMillis(0L, false)
        updateTransportAvailability()
        setMainButtonIcon(playing = autoPlay)
        mVisualizer!!.setRecordingHdrActive(autoPlay && FableSolTuning.isHdrEnabled(mActivity!!))
        mVisualizer!!.animatePresentationAlpha(1.0f, ANIM_DURATION.toLong())

        player.open(mPaths[index], autoPlay)
    }

    private fun togglePlay() {
        val player = mPlayer ?: return
        // 已经放到结尾（自然播完，或被拖到了结尾）时按播放键从头重放这条：
        // 否则解码器一起播就立刻 EOS，只看到播放图标闪一下，还要再点一次才有反应。
        if (mFinished || atEndOfTrack(player)) {
            openTrack(mIndex, autoPlay = true)
            return
        }
        if (player.isPlaying()) player.pause() else player.play()
    }

    private fun atEndOfTrack(player: FableSolAudioFilePlayer): Boolean {
        val duration = player.durationMs()
        return duration > 0 && player.positionMs() >= duration - END_EPSILON_MS
    }

    private fun playPrevious() {
        if (mIndex <= 0) return
        openTrack(mIndex - 1, autoPlay = true)
    }

    private fun playNext() {
        if (mIndex >= mPaths.size - 1) return
        openTrack(mIndex + 1, autoPlay = true)
    }

    private val mPlayerListener = object : FableSolAudioFilePlayer.Listener {
        override fun onPrepared(durationMs: Int) {
            if (!isAdded) return
            mSeekBar!!.max = if (durationMs > 0) durationMs else PROGRESS_UNKNOWN_MAX
            // 自然结束后的 seek 会以暂停态重建解码线程；不能让 onPrepared 再把用户选定位置
            // 擦回 0，否则随后的播放虽然能启动，UI 与声音却从一开始就错位。
            val preparedPosition = (mPlayer?.positionMs() ?: 0)
                .coerceIn(0, mSeekBar!!.max)
            mSeekBar!!.progress = preparedPosition
            updateClock(preparedPosition.toLong())
        }

        override fun onPlayingChanged(playing: Boolean) {
            if (!isAdded) return
            setMainButtonIcon(playing)
            mVisualizer?.setRecordingHdrActive(playing && FableSolTuning.isHdrEnabled(mActivity!!))
        }

        override fun onPositionChanged(positionMs: Int) {
            if (!isAdded || mUserSeeking) return
            mSeekBar!!.progress = positionMs.coerceAtMost(mSeekBar!!.max)
            updateClock(positionMs.toLong())
        }

        override fun onCompleted() {
            if (!isAdded) return
            if (mIndex < mPaths.size - 1) {
                openTrack(mIndex + 1, autoPlay = true)
            } else {
                mFinished = true
                setMainButtonIcon(playing = false)
                mVisualizer?.setRecordingHdrActive(false)
            }
        }

        override fun onFailed(message: String) {
            if (!isAdded) return
            mFinished = true
            setMainButtonIcon(playing = false)
            mTvFileName!!.text = getString(R.string.error_play_audio_attachment)
        }
    }

    private fun setEvents() {
        mIvMainAction!!.setOnClickListener { togglePlay() }
        mIvPrevious!!.setOnClickListener { playPrevious() }
        mIvNext!!.setOnClickListener { playNext() }

        mSeekBar!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateClock(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mUserSeeking = false
                val player = mPlayer ?: return
                mFinished = false
                player.seekTo(seekBar?.progress ?: 0)
            }
        })
    }

    private fun updateTransportAvailability() {
        setTransportEnabled(mIvPrevious, mIndex > 0)
        setTransportEnabled(mIvNext, mIndex < mPaths.size - 1)
    }

    private fun setTransportEnabled(view: ImageView?, enabled: Boolean) {
        view ?: return
        view.isClickable = enabled
        view.alpha = if (enabled) 1.0f else SIDE_CONTROL_DISABLED_ALPHA
    }

    private fun updateClock(positionMs: Long) {
        val clock = mClockView ?: return
        val second = positionMs / 1000L
        if (second == mLastClockSecond) return
        val animate = mLastClockSecond >= 0L
        mLastClockSecond = second
        clock.setTimeMillis(second * 1000L, animate)
    }

    private fun setMainButtonIcon(playing: Boolean) {
        val iconRes = if (playing) R.drawable.act_fablesol_pause else R.drawable.act_fablesol_play
        tintTransportIcon(mIvMainAction, iconRes, currentAccentBackground())
        mIvMainAction!!.contentDescription = getString(
            if (playing) R.string.cd_pause_play_audio_attachment
            else R.string.cd_play_audio_attachment
        )
    }

    /**
     * 走带图标不是 FAB，没有悬浮面兜底，直接压在流动的水体上；颜色因此按记事颜色的
     * 明暗取黑或白（[BackgroundUtil.onColor]），亮色记事的水面上走黑、暗色记事上走白。
     */
    private fun tintTransportIcon(view: ImageView?, iconRes: Int, accentBg: ThingBackground) {
        view ?: return
        view.setImageResource(iconRes)
        view.imageTintList = ColorStateList.valueOf(
            BackgroundUtil.onColor(accentBg, TRANSPORT_ICON_ALPHA)
        )
    }

    /**
     * 圆形涟漪，颜色按记事颜色的明暗自适应为偏黑或偏白
     * （[BackgroundUtil.adaptiveRippleColor]）——不用记事色本身，那会和同色系的水面糊在一起。
     */
    private fun installTransportRipple(view: ImageView?, accentBg: ThingBackground) {
        view ?: return
        BackgroundUtil.installCircleRipple(view, BackgroundUtil.adaptiveRippleColor(accentBg))
    }

    /**
     * 滑杆未播那段的颜色：仍沿用 `app_chrome_on_surface_hint` 的**黑白极性**，只把不透明度
     * 压到 [INACTIVE_TRACK_ALPHA_SCALE]。
     *
     * 极性不能按记事颜色取（那是 2026-07-25 试过的做法，结果是"淡得看不见"）：滑杆压在水面
     * **上方的天空**上，而天空由主题的 `colorBackground` 与记事色的高度白化版混成——亮色主题
     * 的天空恒为浅色、暗色主题恒为深色，跟记事颜色深浅无关。深色记事在亮色主题下会被
     * `onColor` 判成"该用白"，白线画在浅色天空上就消失了。
     */
    private fun inactiveTrackColor(): Int {
        val hint = ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_hint)
        val alpha = (Color.alpha(hint) * INACTIVE_TRACK_ALPHA_SCALE).roundToInt().coerceIn(0, 255)
        return DisplayUtil.getTransparentColor(hint, alpha)
    }

    private fun currentAccentBackground(): ThingBackground =
        mAccentBackground ?: App.defaultAccentBackground

    private fun configureClockView(accentBg: ThingBackground) {
        val clock = mClockView ?: return
        val sp = mActivity!!.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
        val digitStyle = sp.getString(Def.Meta.KEY_DOING_DIGIT_STYLE, "poppins") ?: "poppins"
        val digitFill = (sp.getString(Def.Meta.KEY_DOING_DIGIT_RENDER, "fill") ?: "fill") == "fill"
        clock.setStyleName(digitStyle)
        clock.setRenderMode(digitFill)
        clock.setClockMode(TimelyClockView.MODE_FULL)
        clock.setColonWidthFactor(0.42f)
        clock.setHostDark(AppearanceUtil.isDarkMode(mActivity!!))
        if (accentBg.mode == ThingBackground.Mode.GRADIENT) {
            clock.setInkGradient(accentBg.color, accentBg.endColor, timelyOrientation(accentBg.orientation))
        } else {
            clock.setInkColor(accentBg.color)
        }
        clock.alpha = CLOCK_ALPHA
        mClockHandler.removeCallbacks(mClockIntro)
        mClockHandler.postDelayed(mClockIntro, CLOCK_INTRO_DELAY_MS)
    }

    private val mClockIntro: Runnable = Runnable {
        if (mLastClockSecond <= 0L) mClockView?.animateIn(0L)
    }

    private fun timelyOrientation(orientation: ThingBackground.Orientation): Int {
        return when (orientation) {
            ThingBackground.Orientation.L_R -> TimelyClockView.ORIENTATION_L_R
            ThingBackground.Orientation.R_L -> TimelyClockView.ORIENTATION_R_L
            ThingBackground.Orientation.T_B -> TimelyClockView.ORIENTATION_T_B
            ThingBackground.Orientation.B_T -> TimelyClockView.ORIENTATION_B_T
            ThingBackground.Orientation.LT_RB -> TimelyClockView.ORIENTATION_LT_RB
            ThingBackground.Orientation.RB_LT -> TimelyClockView.ORIENTATION_RB_LT
            ThingBackground.Orientation.RT_LB -> TimelyClockView.ORIENTATION_RT_LB
            ThingBackground.Orientation.LB_RT -> TimelyClockView.ORIENTATION_LB_RT
        }
    }

    // ---- 与录音对话框一致的重力倾斜 / 方向锁 / 性能仪表 ----

    private val mTiltListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            dispatchGravityToVisualizer(event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 把进度条与导出图标对齐到时钟**实际绘制的内容**边缘，而不是它的布局边界。
     *
     * `TimelyClockView.onMeasure()` 上报的是字体 advance 字槽宽度，真正的字形只按字高 80%
     * 绘制，还带各字体自己的侧边留白。这里使用 View 在测量后按轮廓算出的稳定着墨包络；
     * 不能把 View 的 left/right 当成数字边缘。
     *
     * 挂在整棵树的 `OnGlobalLayoutListener` 上，而不是时钟自己的 `addOnLayoutChangeListener`
     * 或一次性的 `post`：后两者在这个对话框里实测都不会兑现（对话框的窗口宽度是 wrap_content，
     * 测量要反复几轮，单次回调很容易落在还没定型的那一轮上）。全局回调每次布局都来，
     * 内部按值判等，稳定后自然不再改动。
     */
    private val mAlignListener = ViewTreeObserver.OnGlobalLayoutListener {
        alignToClockContent()
    }

    private fun installClockContentAlignment() {
        val seekBar = mSeekBar ?: return
        // AbsSeekBar 在进度为 0 时把 thumb 画在
        // x = paddingLeft - thumbOffset；setThumb() 又会把 thumbOffset 自动设成
        // thumb 固有宽度的一半。水平 padding 因而必须等于实际 thumbOffset，才能让
        // handle 外缘（而不是轨道外缘）与 View 左右缘重合。
        val thumbInset = seekBar.thumbOffset.coerceAtLeast(0)
        seekBar.setPadding(
            thumbInset,
            seekBar.paddingTop,
            thumbInset,
            seekBar.paddingBottom
        )
        mContentView?.viewTreeObserver?.addOnGlobalLayoutListener(mAlignListener)
    }

    private fun removeClockContentAlignment() {
        val observer = mContentView?.viewTreeObserver ?: return
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(mAlignListener)
    }

    private fun alignToClockContent() {
        val clock = mClockView ?: return
        val seekBar = mSeekBar ?: return
        val export = mIvExportVideo ?: return
        if (clock.width <= 0 || clock.height <= 0) return
        val parentWidth = (clock.parent as? View)?.width ?: return
        if (parentWidth <= 0) return

        val contentLeft = (clock.left + clock.contentLeftPx()).roundToInt()
        val contentRight = (clock.left + clock.contentRightPx()).roundToInt()

        // video_frame_save 的最右侧保存横线在 x=23 结束，右侧还留 1dp；对齐的是可见
        // 图形而不是 ImageView 内容盒，因此除 paddingRight 外还要扣掉这 1dp。
        val exportParams = export.layoutParams as? ViewGroup.MarginLayoutParams
        var exportMarginEnd = exportParams?.marginEnd ?: 0
        if (exportParams != null) {
            val iconRightInset = (
                EXPORT_ICON_RIGHT_INSET_DP * clock.resources.displayMetrics.density
            ).roundToInt()
            val target = (
                parentWidth - contentRight - export.paddingRight - iconRightInset
            ).coerceAtLeast(0)
            if (exportParams.marginEnd != target || exportParams.rightMargin != target) {
                exportParams.marginEnd = target
                exportParams.rightMargin = target
                export.layoutParams = exportParams
            }
            exportMarginEnd = target
        }

        val seekParams = seekBar.layoutParams as? ViewGroup.MarginLayoutParams
        if (seekParams != null) {
            val gap = (SEEK_TO_EXPORT_GAP_DP * clock.resources.displayMetrics.density).toInt()
            val exportWidth = if (export.width > 0) export.width else export.layoutParams.width
            // 几何上 handle 外缘已与数字着墨左缘相等；再把整条 SeekBar（handle + 轨道）
            // 固定左移 2dp，补偿圆形 handle 相对竖直数字边缘产生的视觉内缩。
            val opticalOverhang = (
                SEEK_OPTICAL_START_OVERHANG_DP * clock.resources.displayMetrics.density
            ).roundToInt()
            val startTarget = (contentLeft - opticalOverhang).coerceAtLeast(0)
            val endTarget = (exportMarginEnd + exportWidth + gap).coerceAtLeast(0)
            if (seekParams.marginStart != startTarget ||
                seekParams.leftMargin != startTarget ||
                seekParams.marginEnd != endTarget ||
                seekParams.rightMargin != endTarget
            ) {
                seekParams.marginStart = startTarget
                seekParams.leftMargin = startTarget
                seekParams.marginEnd = endTarget
                seekParams.rightMargin = endTarget
                seekBar.layoutParams = seekParams
            }
        }
    }

    /**
     * 导出水体视频。GLES 不可用（走了 Canvas 回退）时整个按钮不出现，而不是点了才失败
     * ——离线渲染同样依赖 GLES（fablesol-video-export D14）。
     */
    private fun setUpExportVideoAction(accentBg: ThingBackground) {
        val button = mIvExportVideo ?: return
        if (!FableSolVideoExportLauncher.isSupported(mVisualizer)) {
            button.visibility = View.GONE
            return
        }
        // Material 的 video_frame_save 图标直接使用完整 ThingBackground；渐变不能退化成
        // representativeColor。触摸范围仍属于 App Chrome，保留其中性的圆形 ripple。
        // GLES 是异步失败的：回退发生时立刻隐藏，不用等到用户点一下才发现。
        mVisualizer?.onGlFallback = { button.post { button.visibility = View.GONE } }
        BackgroundUtil.installAppChromeCircleRipple(button, mActivity!!)
        button.imageTintList = null
        button.setImageDrawable(
            BackgroundUtil.tintDrawable(
                button.resources,
                ContextCompat.getDrawable(
                    mActivity!!, R.drawable.act_fablesol_export_video
                ),
                accentBg
            )
        )
        button.setOnClickListener {
            val path = mPaths.getOrNull(mIndex) ?: return@setOnClickListener
            // 装配时查过一次还不够：GLES 是异步失败的，可能在对话框开着的时候才回退到
            // Canvas，那时再点会启动一个必然失败的离线 GLES 导出。
            if (!FableSolVideoExportLauncher.isSupported(mVisualizer)) {
                button.visibility = View.GONE
                return@setOnClickListener
            }
            FableSolVideoExportLauncher.launch(
                mActivity!!,
                path,
                accentBg,
                accentBg,
                mVisualizer
            )
        }
    }

    private fun dispatchGravityToVisualizer(gx: Float, gy: Float, gz: Float) {
        val (screenX, screenY) = when (mLockedRotation) {
            Surface.ROTATION_90 -> -gy to gx
            Surface.ROTATION_180 -> -gx to -gy
            Surface.ROTATION_270 -> gy to -gx
            else -> gx to gy
        }
        mVisualizer?.setContainerGravity(-screenX, screenY, gz)
    }

    /**
     * 锁方向只为倾斜服务：重力到屏幕坐标的换算按打开时的 rotation 定死，中途转屏就会算错。
     * 因此画面不跟随倾斜时不锁——详情页照常自动旋转。
     */
    private fun lockHostOrientation() {
        val host = mActivity ?: return
        if (!mLiveTiltEnabled) return
        if (mOrientationLocked) return
        mLockedRotation = host.windowManager.defaultDisplay.rotation
        mOriginalRequestedOrientation = host.requestedOrientation
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        mOrientationLocked = true
    }

    private fun restoreHostOrientation() {
        val host = mActivity ?: return
        if (!mOrientationLocked) return
        host.requestedOrientation = mOriginalRequestedOrientation
        mOrientationLocked = false
    }

    private fun prepareTiltSensor() {
        if (!mLiveTiltEnabled) return
        val host = mActivity ?: return
        val manager = host.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        mSensorManager = manager
        mGravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun startTiltSensor() {
        val manager = mSensorManager ?: return
        val sensor = mGravitySensor ?: return
        if (mTiltSensorRegistered) return
        val thread = HandlerThread("FableSolTiltSensor").also { it.start() }
        mSensorThread = thread
        mTiltSensorRegistered = manager.registerListener(
            mTiltListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
            Handler(thread.looper)
        )
        if (!mTiltSensorRegistered) {
            thread.quitSafely()
            mSensorThread = null
        }
    }

    private fun stopTiltSensor() {
        if (mTiltSensorRegistered) {
            mSensorManager?.unregisterListener(mTiltListener)
            mTiltSensorRegistered = false
        }
        mSensorThread?.quitSafely()
        mSensorThread = null
    }

    private fun stopPerformanceMonitor() {
        mVisualizer?.setPerformanceMonitor(null)
        mPerformanceMonitor?.stop()
        mPerformanceMonitor = null
    }

    companion object {

        /** 进度条轨道右缘与导出按钮之间的间隙。 */
        private const val SEEK_TO_EXPORT_GAP_DP = 6f
        /** handle 与轨道整体越过 Timely 着墨左缘的光学校正。 */
        private const val SEEK_OPTICAL_START_OVERHANG_DP = 2f
        /** video_frame_save 的最右侧保存横线在 960 viewport 的 x=920，即 24dp 中的 x=23。 */
        private const val EXPORT_ICON_RIGHT_INSET_DP = 1f
        const val TAG: String = "AudioPlayDialogFragment"

        private const val KEY_PATHS = "paths"
        private const val KEY_INDEX = "index"

        private const val TARGET_REFRESH_RATE =
            WaveVisualizerFableSolGl.MAX_RENDER_FPS.toFloat()

        private const val ANIM_DURATION = 360
        private const val CLOCK_ALPHA = 1.0f
        private const val CLOCK_INTRO_DELAY_MS = 160L
        /** 走带图标的前景不透明度（黑/白由记事颜色明暗决定）。 */
        private const val TRANSPORT_ICON_ALPHA = 0.92f
        /** 未播那段滑杆轨道相对 hint 色的不透明度倍率：亮色 26%→16%、暗色 40%→24%。 */
        private const val INACTIVE_TRACK_ALPHA_SCALE = 0.6f
        /** 距离结尾这么近就算"已经在结尾"，按播放键改为从头重放。 */
        private const val END_EPSILON_MS = 250
        /** 对话框比录音对话框高 30dp，取景下移一半，把水线按回同一贴底位置。 */
        private const val CONTENT_OFFSET_DP = 15f
        private const val SIDE_CONTROL_DISABLED_ALPHA = 0.24f
        /** 时长还没读出来之前的滑杆量程，避免 max=0 让拇指卡在最右。 */
        private const val PROGRESS_UNKNOWN_MAX = 1000

        /** 从音频附件卡片打开：[paths] 是去掉类型前缀的完整路径，顺序即卡片顺序。 */
        @JvmStatic
        fun show(manager: FragmentManager, paths: List<String>, index: Int) {
            if (paths.isEmpty()) return
            val fragment = AudioPlayDialogFragment()
            fragment.arguments = Bundle().apply {
                putStringArrayList(KEY_PATHS, ArrayList(paths))
                putInt(KEY_INDEX, index)
            }
            fragment.show(manager, TAG)
        }
    }
}
