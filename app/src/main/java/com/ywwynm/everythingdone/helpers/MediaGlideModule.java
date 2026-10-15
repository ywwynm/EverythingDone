package com.ywwynm.everythingdone.helpers;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.module.AppGlideModule;

/** 动态封面逐帧使用软件位图，需要容纳高卡片帧和同时播放的小帧。 */
@GlideModule
public final class MediaGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Glide 在 O 以前默认已有四屏位图池；低内存设备保留其保守策略。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || manager.isLowRamDevice()) return;

        MemorySizeCalculator original = new MemorySizeCalculator.Builder(context).build();
        MemorySizeCalculator animatedFrames = new MemorySizeCalculator.Builder(context)
                .setBitmapPoolScreens(2f)
                .build();
        // 继续由 Glide 按 memoryClass 限制总预算；不为了扩大池而挤占既有资源缓存。
        if (animatedFrames.getMemoryCacheSize() < original.getMemoryCacheSize()) return;
        builder.setMemorySizeCalculator(animatedFrames);
    }
}
