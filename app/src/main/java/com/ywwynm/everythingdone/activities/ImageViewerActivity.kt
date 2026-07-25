@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import android.graphics.Point
import android.graphics.PorterDuff
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.content.ActivityNotFoundException
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView

import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.OnPhotoTapListener
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.adapters.ImageViewerPagerAdapter
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.MotionPhotoDetector
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.HdrBadgeView

import java.io.File
import java.util.ArrayList
import java.util.concurrent.Executors

open class ImageViewerActivity : EverythingDoneBaseActivity() {

    private var mSystemUiVisible: Boolean = true

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for gradient text on title / confirm. */
    private var mAccentBackground: ThingBackground? = null
    private var mNightModeMask: Int = 0
    private var mEditable: Boolean = false
    private var mTypePathNames: ArrayList<String>? = null
    private var mPosition: Int = 0

    private var mUpdated: Boolean = false

    private var mActionbar: Toolbar? = null

    private var mVpImage: ViewPager? = null
    private var mAdapter: ImageViewerPagerAdapter? = null
    private var mTabs: MutableList<View?>? = null

    /** HDR badge shown for gain-map images on API 34+. */
    private var mTvHdrBadge: HdrBadgeView? = null
    /** Motion Photo“实况”徽标（活动级 LinearLayout，与 HDR 徽标同排；播放时不隐藏）。 */
    private var mLiveBadge: View? = null
    /** Per-page: the decoded image carries a gain map (content is HDR). */
    private var mHasGainmap: BooleanArray = BooleanArray(0)
    /** Per-page, ephemeral: user tapped the badge to force SDR on this page. */
    private var mForcedSdr: BooleanArray = BooleanArray(0)

    /** 每页 Motion Photo 检测结果(null = 非动态照片 / 尚未检测出)。 */
    private var mMotionInfos: Array<MotionPhotoDetector.MotionPhotoInfo?> = arrayOf()
    /** 后台单线程做内容检测(读整文件),不阻塞主线程。 */
    private val mMotionDetectExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MotionPhotoDetect").apply { priority = Thread.MIN_PRIORITY }
    }
    /** 活动级手势识别:长按当前 Motion Photo 页 → 播放;不覆盖 PhotoView 自身触摸(缩放不受影响)。 */
    private var mMotionGesture: GestureDetector? = null
    private var mMotionPlayer: MediaPlayer? = null
    private var mMotionPfd: ParcelFileDescriptor? = null
    private var mMotionSurface: Surface? = null
    private var mMotionTexture: TextureView? = null
    private var mMotionPlayingPage: Int = -1
    /** 当前这次播放是否为“长按按住播放”：true=按住播放(抬手即停);false=自动播放一遍(抬手不停)。 */
    private var mMotionHoldToPlay: Boolean = false
    /** 播放中被隐藏的下层静图;停止时恢复其可见(矩阵不变→用户缩放得以保留)。 */
    private var mMotionPhotoView: PhotoView? = null
    /** 播放起始时静图的显示区域(含缩放/平移);视频铺到此区域以保持原位原缩放。 */
    private val mMotionTargetRect = RectF()
    /** 首帧确定的视频“基准区域”；之后缩放/平移用 TextureView 的 View 属性相对它跟随（顺滑，避免逐帧 setTransform）。 */
    private val mMotionBaseRect = RectF()
    private val mMotionMatrix = Matrix()
    private var mMotionRevealed = false

    // —— 普通视频页的播放（ADR-0017 / decisions.md D11–D12）——

    /** 与 [mTypePathNames] 等长的 Thing Card Video Frame（毫秒），-1 表示未设置。 */
    private var mVideoFrameMs: MutableList<Long> = ArrayList()

    /** 当前播放的是普通视频（true）还是 Motion Photo 的内嵌视频（false）。 */
    private var mPlayingPlainVideo: Boolean = false

    /** 视频页长按的播放头：第一次从头，之后从上次松手处继续；翻页 / 播完 / 退出重置为 0。 */
    private var mVideoResumeMs: Int = 0

    /** 本次播放是否已播到结尾——播完就把播放头归零，否则下次长按会瞬间又结束。 */
    private var mVideoPlaybackCompleted: Boolean = false

    /** 自动播放那 3 秒的定时停止；翻页 / 长按接管 / 停止时必须取消。 */
    private var mAutoStopRunnable: Runnable? = null

    /** 翻页防抖与 TextureView 就绪重试用的待执行起播任务。 */
    private var mAutoplayRunnable: Runnable? = null

    /** 播放期间持有的瞬时音频焦点（可 duck），Motion Photo 与视频共用。 */
    private var mAudioFocusRequest: AudioFocusRequest? = null

    override fun getLayoutResource(): Int = R.layout.activity_image_viewer

    override fun initMembers() {
        mNightModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val intent: Intent = getIntent()
        mAccentColor = intent.getIntExtra(Def.Communication.KEY_COLOR, 0)
        val bgJson = intent.getStringExtra(Def.Communication.KEY_BACKGROUND)
        mAccentBackground = ThingBackground.fromJson(bgJson)
        if (mAccentBackground == null) {
            mAccentBackground = ThingBackground.pure(mAccentColor)
        }
        mEditable = intent.getBooleanExtra(Def.Communication.KEY_EDITABLE, true)
        @Suppress("UNCHECKED_CAST")
        mTypePathNames = intent.getStringArrayListExtra(
            Def.Communication.KEY_TYPE_PATH_NAME
        )
        mPosition = intent.getIntExtra(Def.Communication.KEY_POSITION, 0)

        val size = mTypePathNames!!.size
        mTabs = ArrayList(size)
        mHasGainmap = BooleanArray(size)
        mForcedSdr = BooleanArray(size)
        mMotionInfos = arrayOfNulls(size)

        val frames = intent.getLongArrayExtra(Def.Communication.KEY_VIDEO_FRAME_MS_LIST)
        mVideoFrameMs = ArrayList(size)
        for (i in 0 until size) {
            mVideoFrameMs.add(frames?.getOrNull(i) ?: -1L)
        }
    }

    override fun findViews() {
        mActionbar = f(R.id.actionbar)
        mVpImage   = f(R.id.vp_image_viewer)
        mTvHdrBadge = f(R.id.tv_hdr_badge)
        mLiveBadge = f(R.id.ll_live_badge)
    }

    override fun initUI() {
        val decorView: View = window.decorView
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        decorView.systemUiVisibility = flags

        val appAccent = App.defaultAccentBackground.representativeColor()
        EdgeEffectUtil.forViewPager(mVpImage, appAccent)

        val size: IntArray = getImageSize()
        val imageListener: OnPhotoTapListener = getImageListener()
        val videoListener: View.OnClickListener = getVideoListener()

        val inflater = LayoutInflater.from(this)
        for ((index, typePathName) in mTypePathNames!!.withIndex()) {
            @SuppressLint("InflateParams")
            val tab: View = inflater.inflate(R.layout.tab_image_attachment, null)

            val type = if (typePathName[0] == '0') AttachmentHelper.IMAGE else AttachmentHelper.VIDEO
            val pathName = typePathName.substring(1, typePathName.length)

            val pb: ProgressBar       = f(tab, R.id.pb_image_attachment)!!
            val iv: PhotoView         = f(tab, R.id.iv_image_attachment)!!
            val videoSignal: ImageView = f(tab, R.id.iv_video_signal)!!

            BackgroundUtil.applyProgressBarGradient(pb, App.defaultAccentBackground)

            iv.setScaleLevels(1.0f, 3.0f, 6.0f)

            if (type == 0) {
                iv.contentDescription = getString(R.string.cd_image_attachment)
                videoSignal.visibility = View.GONE
                iv.setOnPhotoTapListener(imageListener)
            } else {
                iv.contentDescription = getString(R.string.cd_video_attachment)
                videoSignal.visibility = View.VISIBLE
                videoSignal.setOnClickListener(videoListener)
                // 视频页此前禁用缩放（那时它只是一张不会动的封面帧）。现在它会自动播放、
                // 长按还能接着看正片，而播放层本就跟随 PhotoView 的 displayRect，
                // 所以放开缩放，与 Motion Photo 一致。见 ADR-0017。
                iv.isZoomable = true
            }

            loadImage(index, pathName, iv, pb, size, videoFrameMsOf(index))

            if (type == AttachmentHelper.IMAGE && AttachmentHelper.isMotionPhotoCandidate(pathName)) {
                detectMotionPhotoAsync(index)
            }

            mTabs!!.add(tab)
        }

        mAdapter = ImageViewerPagerAdapter(mTabs)
        mVpImage!!.adapter = mAdapter

        mVpImage!!.currentItem = mPosition

        mMotionGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val page = mVpImage?.currentItem ?: return
                if (isVideoPage(page)) {
                    startVideoPlaybackForCurrentPage()
                } else {
                    startMotionPlaybackForCurrentPage()
                }
            }
        })

        // 两个徽标同处 fl_top_badges 横条,顶部 inset 统一加到容器(子项各自 center_vertical、y 方向对齐)。
        DisplayUtil.applyTopInsetAsMargin(findViewById(R.id.fl_top_badges))
        mTvHdrBadge!!.setOnClickListener {
            val pos = mVpImage?.currentItem ?: return@setOnClickListener
            if (pos in mForcedSdr.indices) {
                mForcedSdr[pos] = !mForcedSdr[pos]
                applyHdrStateForCurrentPage()
            }
        }
    }

    private fun getImageSize(): IntArray {
        val screen: Point = DisplayUtil.getScreenSize(this)
        val width  = screen.x
        val height = screen.y
        return intArrayOf(width, height)
    }

    private fun getImageListener(): OnPhotoTapListener {
        return OnPhotoTapListener { _, _, _ ->
            toggleSystemUI()
        }
    }

    private fun getVideoListener(): View.OnClickListener {
        return View.OnClickListener {
            val pos = mVpImage!!.currentItem
            val typePathName: String = mTypePathNames!![pos]
            val pathName = typePathName.substring(1, typePathName.length)
            val file = File(pathName)

            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // getUriForFile throws IllegalArgumentException for paths outside the
            // FileProvider roots (e.g. a removable volume); startActivity throws
            // ActivityNotFoundException when no player is installed. Guard both so
            // a tap on a video can never crash the viewer.
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    this@ImageViewerActivity,
                    "com.ywwynm.everythingdone", file
                )
                intent.setDataAndType(uri, "video/*")
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    R.string.image_viewer_no_video_player,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    R.string.image_viewer_no_video_player,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadImage(
        position: Int, pathName: String, iv: PhotoView,
        pb: ProgressBar, size: IntArray, videoFrameMs: Long
    ) {
        // Animated Image (GIF / animated WebP) carries no HDR gain map, so the
        // asBitmap HDR path has nothing to preserve here; load it as a Drawable so
        // Glide animates it. See ADR-0007.
        if (AttachmentHelper.isAnimatedImageCandidate(pathName)) {
            loadAnimatedImage(position, pathName, iv, pb, size)
            return
        }
        // asBitmap + dontTransform + disallowHardwareConfig: decode straight to
        // an ARGB_8888 bitmap that still carries the UltraHDR gain map, with no
        // software-Canvas transform step that would flatten it to SDR. PhotoView
        // does its own matrix fit/zoom, so no Glide fitting transform is needed.
        var request = Glide.with(this)
            .asBitmap()
            .load(pathName)
            .dontTransform()
            .disallowHardwareConfig()
        if (videoFrameMs > 0L) {
            // 视频页的静帧用 Thing Card Video Frame，与详情网格看到的是同一帧；
            // 自动播放也从这里起播、播完回到这里。见 ADR-0017。
            request = request.apply(RequestOptions.frameOf(videoFrameMs * 1000L))
        }
        request
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Bitmap>,
                    isFirstResource: Boolean
                ): Boolean {
                    pb.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap, model: Any, target: Target<Bitmap>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    iv.setImageBitmap(resource)
                    pb.visibility = View.GONE
                    val hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            resource.hasGainmap()
                    if (position in mHasGainmap.indices) {
                        mHasGainmap[position] = hdr
                    }
                    if (position == mVpImage?.currentItem) {
                        applyHdrStateForCurrentPage()
                    }
                    return true
                }
            })
            .override(size[0], size[1])
            .into(iv)
    }

    private fun loadAnimatedImage(
        position: Int, pathName: String, iv: PhotoView,
        pb: ProgressBar, size: IntArray
    ) {
        // Load as a Drawable so GIF / animated WebP plays. These never carry a
        // gain map, so this page is never HDR. See ADR-0007.
        if (position in mHasGainmap.indices) {
            mHasGainmap[position] = false
        }
        Glide.with(this)
            .load(pathName)
            .override(size[0], size[1])
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    pb.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    pb.visibility = View.GONE
                    if (position == mVpImage?.currentItem) {
                        applyHdrStateForCurrentPage()
                    }
                    return false
                }
            })
            .into(iv)
    }

    // —— Motion Photo(动态照片)按住播放 ——

    /** 后台检测该图片是否为 Motion Photo;是则在主线程记录区间,并在其为当前页时刷新“实况”徽标。 */
    private fun detectMotionPhotoAsync(page: Int) {
        val typePathName = mTypePathNames?.getOrNull(page) ?: return
        val pathName = typePathName.substring(1)
        mMotionDetectExecutor.submit {
            val info = MotionPhotoDetector.detect(pathName)
            if (!info.isMotionPhoto) return@submit
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (page in mMotionInfos.indices) mMotionInfos[page] = info
                if (page == mVpImage?.currentItem) {
                    updateLiveBadge()
                    maybeAutoplayCurrentMotionPage()
                }
            }
        }
    }

    /** 由活动级手势的 onLongPress 触发:播放当前页 Motion Photo 的内嵌视频。 */
    private fun startMotionPlaybackForCurrentPage() {
        val page = mVpImage?.currentItem ?: return
        val info = mMotionInfos.getOrNull(page) ?: return
        val tab = mTabs?.getOrNull(page) ?: return
        val typePathName = mTypePathNames?.getOrNull(page) ?: return
        val pathName = typePathName.substring(1)
        val texture: TextureView = tab.findViewById(R.id.tv_motion_surface) ?: return
        val photoView: PhotoView = tab.findViewById(R.id.iv_image_attachment) ?: return
        // 长按可以从"自动播放一遍"手里接管：不然那 1–3 秒里的长按会被静默吞掉。
        if (mMotionPlayer != null) {
            if (mMotionHoldToPlay) return
            stopMotionPlayback()
        }
        startMotionPlayback(page, pathName, info, texture, photoView, loop = true)
    }

    /** 打开或翻到某 Motion Photo 页时,自动播放一遍(带触感),播完回到静态。 */
    private fun maybeAutoplayCurrentMotionPage() {
        if (mMotionPlayer != null) return
        val page = mVpImage?.currentItem ?: return
        val info = mMotionInfos.getOrNull(page) ?: return
        val tab = mTabs?.getOrNull(page) ?: return
        val typePathName = mTypePathNames?.getOrNull(page) ?: return
        val pathName = typePathName.substring(1)
        val texture: TextureView = tab.findViewById(R.id.tv_motion_surface) ?: return
        val photoView: PhotoView = tab.findViewById(R.id.iv_image_attachment) ?: return
        startMotionPlayback(page, pathName, info, texture, photoView, loop = false)
    }

    // —— 普通视频页：自动播关键帧起 3 秒，长按接着看正片 ——

    private fun isVideoPage(page: Int): Boolean =
        mTypePathNames?.getOrNull(page)?.firstOrNull() == '1'

    private fun videoFrameMsOf(page: Int): Long = mVideoFrameMs.getOrNull(page) ?: -1L

    /**
     * 翻页/进入时安排当前页的自动播放。**停稳后**才起播（[AUTOPLAY_SETTLE_MS]）：视频不静音，
     * 快速左右连续翻页若立刻起播会产生一串声音碎片。见 decisions.md D12。
     */
    private fun scheduleAutoplayForCurrentPage() {
        cancelScheduledAutoplay()
        val page = mVpImage?.currentItem ?: return
        if (!isVideoPage(page)) {
            // Motion Photo 走既有路径：检测完成回调本身就是"已稳定"的时刻。
            maybeAutoplayCurrentMotionPage()
            return
        }
        postAutoplay(page, 0, AUTOPLAY_SETTLE_MS)
    }

    /**
     * TextureView 的 SurfaceTexture 未必已就绪（首次进入尤其如此），故带重试；
     * 每次只保留一个待执行任务，翻页时统一撤销。
     */
    private fun postAutoplay(page: Int, attempt: Int, delayMs: Long) {
        if (attempt > AUTOPLAY_SURFACE_RETRY) return
        val runnable = Runnable {
            mAutoplayRunnable = null
            if (isFinishing || isDestroyed) return@Runnable
            if (mVpImage?.currentItem != page) return@Runnable
            if (mMotionPlayer != null) return@Runnable
            val tab = mTabs?.getOrNull(page) ?: return@Runnable
            val texture: TextureView = tab.findViewById(R.id.tv_motion_surface) ?: return@Runnable
            if (!texture.isAvailable) {
                postAutoplay(page, attempt + 1, AUTOPLAY_SURFACE_RETRY_MS)
                return@Runnable
            }
            val typePathName = mTypePathNames?.getOrNull(page) ?: return@Runnable
            val photoView: PhotoView = tab.findViewById(R.id.iv_image_attachment) ?: return@Runnable
            val frameMs = videoFrameMsOf(page)
            startMotionPlayback(
                page, typePathName.substring(1), null, texture, photoView,
                loop = false,
                startMs = if (frameMs > 0L) frameMs.toInt() else 0,
                autoStopAfterMs = AUTOPLAY_DURATION_MS,
                holdToPlay = false
            )
        }
        mAutoplayRunnable = runnable
        mVpImage?.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduledAutoplay() {
        mAutoplayRunnable?.let { mVpImage?.removeCallbacks(it) }
        mAutoplayRunnable = null
    }

    /**
     * 长按视频页：第一次从视频**开头**播（不受自动播放那 3 秒窗口限制），松手回静帧并
     * 记住播放头，再长按从那里继续。见 decisions.md D12。
     */
    private fun startVideoPlaybackForCurrentPage() {
        val page = mVpImage?.currentItem ?: return
        if (!isVideoPage(page)) return
        val tab = mTabs?.getOrNull(page) ?: return
        val typePathName = mTypePathNames?.getOrNull(page) ?: return
        val texture: TextureView = tab.findViewById(R.id.tv_motion_surface) ?: return
        if (!texture.isAvailable) return
        val photoView: PhotoView = tab.findViewById(R.id.iv_image_attachment) ?: return
        if (mMotionPlayer != null) {
            if (mMotionHoldToPlay) return
            // 从自动播放那 3 秒手里接管。
            stopMotionPlayback()
        }
        cancelScheduledAutoplay()
        startMotionPlayback(
            page, typePathName.substring(1), null, texture, photoView,
            loop = false, startMs = mVideoResumeMs, autoStopAfterMs = 0L, holdToPlay = true
        )
    }

    /**
     * 播放期间隐藏中央播放按钮（它是操作入口，会盖在动起来的画面上）。
     * 遍历全部页而不是只改当前页：停止播放常发生在翻页途中（此时 currentItem 已是新页），
     * 只改当前页会把刚离开那页的按钮永久留在隐藏态。
     */
    private fun updateVideoSignalVisibility() {
        val tabs = mTabs ?: return
        for ((index, tab) in tabs.withIndex()) {
            if (tab == null || !isVideoPage(index)) continue
            val signal: ImageView = tab.findViewById(R.id.iv_video_signal) ?: continue
            val playingHere = mMotionPlayer != null && mMotionPlayingPage == index
            signal.visibility = if (playingHere) View.GONE else View.VISIBLE
        }
    }

    /**
     * 播放不静音（与 Motion Photo 现状一致），因此必须请求瞬时音频焦点，否则会直接盖在
     * 用户正在放的音乐上。Motion Photo 与视频共用这一条路径。见 decisions.md D12。
     */
    private fun requestPlaybackAudioFocus() {
        if (mAudioFocusRequest != null) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            am.requestAudioFocus(request)
            mAudioFocusRequest = request
        } catch (_: Exception) {
        }
    }

    private fun abandonPlaybackAudioFocus() {
        val request = mAudioFocusRequest ?: return
        mAudioFocusRequest = null
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        try { am.abandonAudioFocusRequest(request) } catch (_: Exception) {}
    }

    private fun cancelAutoStop() {
        mAutoStopRunnable?.let { mVpImage?.removeCallbacks(it) }
        mAutoStopRunnable = null
    }

    /**
     * @param info 为 null 表示这是一个普通视频文件（整文件就是数据源）；非 null 时用
     *             Motion Photo 的内嵌区间。
     * @param startMs 起播位置，0 表示从头。
     * @param autoStopAfterMs 大于 0 时在起播后该毫秒数停止（自动播放那 3 秒用它；
     *                        MediaPlayer 没有"播到某时刻停"的原生能力）。
     * @param holdToPlay true 表示这是"按住播放"，抬手即停。
     */
    private fun startMotionPlayback(
        page: Int, pathName: String,
        info: MotionPhotoDetector.MotionPhotoInfo?,
        texture: TextureView, photoView: PhotoView, loop: Boolean,
        startMs: Int = 0,
        autoStopAfterMs: Long = 0L,
        holdToPlay: Boolean = loop
    ) {
        if (mMotionPlayer != null) return
        if (!texture.isAvailable) return
        val surfaceTexture = texture.surfaceTexture ?: return
        // 记录静图当前显示区域(含缩放/平移),让视频铺到同一区域——按住即在原位、原缩放下动起来。
        val dr = photoView.displayRect
        if (dr != null && dr.width() > 0f && dr.height() > 0f) {
            mMotionTargetRect.set(dr)
        } else {
            mMotionTargetRect.set(0f, 0f, texture.width.toFloat(), texture.height.toFloat())
        }
        mMotionPhotoView = photoView
        // 播放期间视频跟随静图的缩放/平移：监听 PhotoView 矩阵变化，实时把视频铺到其当前显示区域。
        // 这样自动/长按播放时都能缩放、视频随之缩放，且缩放结果在播放结束后由 PhotoView 天然保留。
        photoView.setOnMatrixChangeListener { rect ->
            if (rect != null && rect.width() > 0f && rect.height() > 0f) {
                mMotionTargetRect.set(rect)
                trackMotionZoom(texture, rect)
            }
        }
        val surface = Surface(surfaceTexture)
        val pfd = try {
            ParcelFileDescriptor.open(File(pathName), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            surface.release(); return
        }
        val mp = MediaPlayer()
        try {
            if (info != null) {
                mp.setDataSource(pfd.fileDescriptor, info.videoOffset, info.videoLength)
            } else {
                mp.setDataSource(pfd.fileDescriptor)
            }
            mp.setSurface(surface)
            mp.isLooping = loop
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    try { surfaceTexture.setDefaultBufferSize(w, h) } catch (_: Exception) {}
                    if (mMotionRevealed) setupMotionBaseTransform(texture)
                }
            }
            mp.setOnPreparedListener { player ->
                if (mMotionPlayer !== player) return@setOnPreparedListener
                val vw = player.videoWidth
                val vh = player.videoHeight
                if (vw > 0 && vh > 0) {
                    try { surfaceTexture.setDefaultBufferSize(vw, vh) } catch (_: Exception) {}
                }
                // 在首帧之前就把基准变换套好(此刻 alpha 仍 0、不显示),避免揭示瞬间闪现未变换的拉伸帧。
                setupMotionBaseTransform(texture)
                if (startMs > 0) {
                    try { player.seekTo(startMs) } catch (_: Exception) {}
                }
                player.start()
                if (autoStopAfterMs > 0L) {
                    // MediaPlayer 没有"播到某时刻停"的原生能力，用定时停实现那 3 秒。
                    cancelAutoStop()
                    val stop = Runnable {
                        mAutoStopRunnable = null
                        if (mMotionPlayer === player) stopMotionPlayback()
                    }
                    mAutoStopRunnable = stop
                    mVpImage?.postDelayed(stop, autoStopAfterMs)
                }
                // 兜底:个别机型不发 VIDEO_RENDERING_START 时,延迟揭示避免卡在照片。
                mVpImage?.postDelayed({ revealMotionSurface() }, 180L)
            }
            mp.setOnInfoListener { _, what, _ ->
                // 首帧真正渲染出来后才切换到视频层,做到从照片“无缝”变实况,消除起播闪烁/拉伸。
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    revealMotionSurface()
                }
                false
            }
            if (!loop) {
                // 自动播放一遍后回到静态。
                mp.setOnCompletionListener {
                    mVideoPlaybackCompleted = true
                    stopMotionPlayback()
                }
            }
            mp.setOnErrorListener { _, _, _ -> stopMotionPlayback(); true }
            mp.prepareAsync()
            mMotionPlayer = mp
            mMotionPfd = pfd
            mMotionSurface = surface
            mMotionTexture = texture
            mMotionPlayingPage = page
            mMotionHoldToPlay = holdToPlay
            mPlayingPlainVideo = info == null
            mVideoPlaybackCompleted = false
            requestPlaybackAudioFocus()
            updateVideoSignalVisibility()
            // 起播触感(自动与长按都给,遵循系统触感设置)。
            mVpImage?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            surface.release()
        }
    }

    /** 首帧就绪时把变换套准并显示视频层、隐藏静图(矩阵不变→缩放保留)。幂等,已停止则不动。 */
    /**
     * 建立视频“基准变换”：把视频按宽高比 fit-center 到整个 TextureView（基准区域只依赖视频与视图
     * 尺寸，**不依赖静图 displayRect**，故不受静图加载时序影响、不会出现“上静下动”的双图）。随后立即
     * 用 View 属性同步到静图当前显示区域。此刻不改 alpha。
     */
    private fun setupMotionBaseTransform(texture: TextureView) {
        val videoW = mMotionPlayer?.videoWidth ?: 0
        val videoH = mMotionPlayer?.videoHeight ?: 0
        if (videoW <= 0 || videoH <= 0) return
        val texW = texture.width.toFloat()
        val texH = texture.height.toFloat()
        if (texW <= 0f || texH <= 0f) return
        val scale = minOf(texW / videoW, texH / videoH)
        val dw = videoW * scale
        val dh = videoH * scale
        val left = (texW - dw) / 2f
        val top = (texH - dh) / 2f
        mMotionBaseRect.set(left, top, left + dw, top + dh)
        val m = mMotionMatrix
        m.reset()
        m.setScale(dw / texW, dh / texH)
        m.postTranslate(left, top)
        texture.setTransform(m)
        texture.pivotX = left
        texture.pivotY = top
        texture.scaleX = 1f
        texture.scaleY = 1f
        texture.translationX = 0f
        texture.translationY = 0f
        mMotionRevealed = true
        // 立即同步到静图当前显示区域（含可能已有的缩放）；未加载好则等矩阵监听回调再同步。
        mMotionPhotoView?.displayRect?.let {
            if (it.width() > 0f && it.height() > 0f) trackMotionZoom(texture, it)
        }
    }

    private fun revealMotionSurface() {
        if (mMotionPlayer == null) return
        val texture = mMotionTexture ?: return
        // 基准变换已在 onPrepared 套好；此刻仅显示视频层（首帧已渲染，故无缝、不拉伸）。
        if (!mMotionRevealed) setupMotionBaseTransform(texture)
        texture.alpha = 1f
        // 不隐藏静图：保持它可见可触（可缩放），视频覆在其显示区域上、并随缩放平移用 View 属性跟随。
    }

    private fun stopMotionPlayback() {
        cancelAutoStop()
        val mp = mMotionPlayer
        if (mp != null) {
            if (mPlayingPlainVideo && mMotionHoldToPlay) {
                // 长按松手：记住播放头，下次长按接着往下看；播到结尾则归零。
                mVideoResumeMs = if (mVideoPlaybackCompleted) {
                    0
                } else {
                    try { mp.currentPosition } catch (_: Exception) { mVideoResumeMs }
                }
            }
            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        mMotionPlayer = null
        mPlayingPlainVideo = false
        mVideoPlaybackCompleted = false
        abandonPlaybackAudioFocus()
        try { mMotionPfd?.close() } catch (_: Exception) {}
        mMotionPfd = null
        mMotionRevealed = false
        mMotionTexture?.let {
            it.alpha = 0f
            it.scaleX = 1f; it.scaleY = 1f
            it.translationX = 0f; it.translationY = 0f
            it.setTransform(Matrix())
        }
        mMotionTexture = null
        mMotionSurface?.release()
        mMotionSurface = null
        // 移除矩阵监听;静图始终可见、其矩阵由用户缩放决定,播放中的缩放得以保留到播放之后。
        mMotionPhotoView?.setOnMatrixChangeListener(null)
        mMotionPhotoView = null
        mMotionPlayingPage = -1
        mMotionHoldToPlay = false
        updateVideoSignalVisibility()
        updateLiveBadge()
    }

    /** 活动级“实况”徽标:仅当当前页是 Motion Photo、未在播放、且系统 UI 可见时显示。 */
    private fun updateLiveBadge() {
        val badge = mLiveBadge ?: return
        val page = mVpImage?.currentItem ?: -1
        val isMotion = page in mMotionInfos.indices && mMotionInfos[page] != null
        // 播放时也保持显示（用户要求）。
        badge.visibility = if (isMotion && mSystemUiVisible) View.VISIBLE else View.GONE
    }

    /**
     * 缩放/平移跟随：相对 [mMotionBaseRect]，用 TextureView 的 View 属性(scale/translation)平滑跟随，
     * 走 RenderThread、无每帧 Matrix 分配与 setTransform，避免卡顿。View 变换将基准区域精确映射到 [rect]。
     */
    private fun trackMotionZoom(texture: TextureView, rect: RectF) {
        if (!mMotionRevealed) return
        val base = mMotionBaseRect
        if (base.width() <= 0f || base.height() <= 0f) return
        val s = rect.width() / base.width()
        texture.pivotX = base.left
        texture.pivotY = base.top
        texture.scaleX = s
        texture.scaleY = s
        texture.translationX = rect.left - base.left
        texture.translationY = rect.top - base.top
    }

    override fun setActionbar() {
        DisplayUtil.applyTopInsetAsMargin(mActionbar)

        setSupportActionBar(mActionbar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
        updateAttachmentNumber()
        mActionbar!!.setNavigationOnClickListener { returnToDetailActivity() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_image_viewer, menu)
        if (!mEditable) {
            val item: MenuItem = menu.findItem(R.id.act_delete_attachment)
            item.isVisible = false
            item.isEnabled = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.act_show_attachment_info) {
            showAttachmentInfoDialogForCurrentImage()
        } else if (id == R.id.act_delete_attachment) {
            val adf = AlertDialogFragment()
            adf.setContentColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_medium))
            adf.setConfirmBackground(mAccentBackground)
            adf.setContent(getString(R.string.alert_delete_attachment))
            adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
                override fun onConfirm() {
                    val currentIndex = mVpImage!!.currentItem
                    mTypePathNames!!.removeAt(currentIndex)
                    // 与 mTypePathNames 逐位对齐，删除后必须同步移除，否则关键帧会错位。
                    if (currentIndex in mVideoFrameMs.indices) {
                        mVideoFrameMs.removeAt(currentIndex)
                    }
                    mAdapter!!.removeTab(mVpImage, currentIndex)
                    updateAttachmentNumber()
                    mUpdated = true
                    if (mAdapter!!.count == 0) {
                        returnToDetailActivity()
                    }
                }
            })
            adf.show(supportFragmentManager, AlertDialogFragment.TAG)
            return true
        }
        return false
    }

    private fun showAttachmentInfoDialogForCurrentImage() {
        val typePathNames = mTypePathNames ?: return
        if (typePathNames.isEmpty()) return
        val currentItem = mVpImage?.currentItem ?: return
        val index = currentItem.coerceIn(0, typePathNames.size - 1)
        AttachmentHelper.showAttachmentInfoDialog(this, mAccentBackground, typePathNames[index])
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToDetailActivity()
            }
        })

        mVpImage!!.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                stopMotionPlayback()
                // 播放头只在本页内有意义，翻页即重置（见 decisions.md D12）。
                mVideoResumeMs = 0
                updateAttachmentNumber()
                applyHdrStateForCurrentPage()
                updateLiveBadge()
                updateVideoSignalVisibility()
                scheduleAutoplayForCurrentPage()
            }
        })

        // ViewPager 的初始页不会触发 onPageSelected，首页的自动播放要自己安排。
        mVpImage!!.post { scheduleAutoplayForCurrentPage() }
    }

    private fun updateAttachmentNumber() {
        val current = mVpImage!!.currentItem + 1
        val total   = mTypePathNames!!.size
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            actionBar.title = "$current / $total"
        }
    }

    /**
     * Apply HDR for the currently visible page: switch the window to
     * [ActivityInfo.COLOR_MODE_HDR] (API 34+) when the page's image carries a
     * gain map and the user has not forced SDR on it, and refresh the badge.
     * On a non-HDR display the window mode is harmless; the gain map simply
     * isn't boosted.
     */
    private fun applyHdrStateForCurrentPage() {
        val pos = mVpImage?.currentItem ?: return
        val isHdr = pos in mHasGainmap.indices && mHasGainmap[pos]
        val boostOn = isHdr && !(pos in mForcedSdr.indices && mForcedSdr[pos])
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val mode = if (boostOn) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            if (window.colorMode != mode) {
                window.colorMode = mode
            }
        }
        updateHdrBadge(isHdr, boostOn)
    }

    private fun updateHdrBadge(isHdr: Boolean, boostOn: Boolean) {
        val badge = mTvHdrBadge ?: return
        if (!isHdr || !mSystemUiVisible) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        badge.setBoostOn(boostOn)
        if (boostOn) {
            badge.alpha = 1f
            badge.contentDescription = getString(R.string.cd_hdr_badge_on)
        } else {
            badge.alpha = 0.9f
            badge.contentDescription = getString(R.string.cd_hdr_badge_off)
        }
    }

    private fun toggleSystemUI() {
        val decorView: View = window.decorView
        val visibility = decorView.systemUiVisibility
        if (mSystemUiVisible) {
            decorView.systemUiVisibility = (visibility
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE)
            mActionbar!!.visibility = View.GONE
        } else {
            decorView.systemUiVisibility = (visibility
                    and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
                    and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
                    and View.SYSTEM_UI_FLAG_IMMERSIVE.inv())
            mActionbar!!.visibility = View.VISIBLE
        }
        mSystemUiVisible = !mSystemUiVisible
        applyHdrStateForCurrentPage()
        updateLiveBadge()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val newNightModeMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightModeMask == mNightModeMask) return

        mNightModeMask = newNightModeMask
        delegate.applyDayNight()

        val attachmentInfoWasShowing =
            supportFragmentManager.findFragmentByTag(AttachmentInfoDialogFragment.TAG) is AttachmentInfoDialogFragment
        dismissDialogFragment(AttachmentInfoDialogFragment.TAG)
        dismissDialogFragment(AlertDialogFragment.TAG)

        if (attachmentInfoWasShowing) {
            mVpImage?.post {
                if (!isFinishing && !isDestroyed) {
                    showAttachmentInfoDialogForCurrentImage()
                }
            }
        }
    }

    private fun dismissDialogFragment(tag: String) {
        val fragment = supportFragmentManager.findFragmentByTag(tag)
        if (fragment is androidx.fragment.app.DialogFragment) {
            fragment.dismissAllowingStateLoss()
        }
    }

    private fun returnToDetailActivity() {
        if (mUpdated) {
            val intent = Intent()
            intent.putExtra(Def.Communication.KEY_TYPE_PATH_NAME, mTypePathNames)
            setResult(Def.Communication.RESULT_UPDATE_IMAGE_DONE, intent)
        } else {
            setResult(Def.Communication.RESULT_NO_UPDATE)
        }
        finish()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val page = mVpImage?.currentItem ?: -1
        val isMotionPage = page in mMotionInfos.indices && mMotionInfos[page] != null
        // 自动播放进行中也接受长按（由它接管），只有"按住播放"进行中不再重复触发。
        if ((isMotionPage || isVideoPage(page)) && !mMotionHoldToPlay) {
            mMotionGesture?.onTouchEvent(ev)
        }
        // 仅“长按按住播放”在抬手/取消时停止；自动播放一遍时任何触摸都不打断(播完由 OnCompletion 收尾)。
        if (mMotionPlayer != null && mMotionHoldToPlay &&
            (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            stopMotionPlayback()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        cancelScheduledAutoplay()
        stopMotionPlayback()
        mVideoResumeMs = 0
    }

    override fun onDestroy() {
        cancelScheduledAutoplay()
        stopMotionPlayback()
        mMotionDetectExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val TAG: String = "ImageViewerActivity"

        /** 翻页停稳多久后才起播——视频不静音，快速划过时不该发出声音碎片。 */
        private const val AUTOPLAY_SETTLE_MS = 360L

        /** 自动播放的时长，与 Thing Card Video Preview 的单次循环时长一致。 */
        private const val AUTOPLAY_DURATION_MS = 3000L

        /** TextureView 就绪的重试次数与间隔（首次进入时 SurfaceTexture 未必已可用）。 */
        private const val AUTOPLAY_SURFACE_RETRY = 12
        private const val AUTOPLAY_SURFACE_RETRY_MS = 120L
    }
}
