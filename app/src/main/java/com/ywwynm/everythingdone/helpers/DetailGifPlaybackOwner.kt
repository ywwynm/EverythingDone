package com.ywwynm.everythingdone.helpers

import com.bumptech.glide.load.Option
import com.bumptech.glide.request.RequestOptions

/**
 * 让需要独立播放进度的动图请求独占解码状态。
 *
 * Glide 缓存会返回不同 GifDrawable，但它们共享 GifFrameLoader；一个 drawable 已停止，
 * 不代表其它订阅者也停止。仅检查 isRunning 仍可能在 startFromFirstFrame 时崩溃。
 * 内存选项同时隔离活动资源、内存缓存和在途任务，磁盘文件与裁切缓存仍可复用。
 * 平台动态 WebP 的 Resource.get() 直接返回同一个 Drawable；详情循环与全屏重复页也需隔离。
 */
internal class DetailGifPlaybackOwner {
    // 不直接使用 holder，避免 Glide 缓存键持有 View／Activity；同一 holder 重绑时沿用身份。
    val requestOptions: RequestOptions = RequestOptions().set(OWNER, Any()).lock()

    private companion object {
        val OWNER: Option<Any> = Option.memory(
            "com.ywwynm.everythingdone.detail.gifPlaybackOwner"
        )
    }
}
