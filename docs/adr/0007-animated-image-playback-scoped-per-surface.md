# 动图播放按界面分级(Animated image playback is scoped per surface)

我们只在"把附件作为实时 Glide Drawable 显示"的界面上给 **Animated Image**(GIF、动态 WebP)以 **Animated Playback**;凡是烘焙裁切、保留 HDR、或 RemoteViews 的界面,一律停在单帧。

**播放**:全屏预览(`ImageViewerActivity` + PhotoView);详情附件列表(默认与定制模式);所有应用内 Thing Card 面(首页列表、文件夹卡片子项缩略图、DoingActivity、NoticeableNotificationActivity、以及 widget 配置页里**选择记事的浏览列表**)。

**单帧**:裁切编辑器;widget 配置页里的 **widget 预览**(RemoteViews,本就静态);桌面已放置的 widget;以及任何视频缩略图。注意区分:配置页里"挑选记事的列表"是应用内浏览列表,会播放;只有那个"这个 widget 长什么样"的预览保持静态。

## 为什么

动画与两条既有管线直接冲突,二者不能兼得:

- **烘焙裁切管线破坏动画。** 卡片缩略图、侧栏媒体、媒体背景、详情定制模式、裁切编辑器都通过 `MediaCropBitmapRenderer` 的软件 Canvas 把自定义 center/scale/比例裁切烘焙成单张 Bitmap;单张位图无法承载逐帧动画。
- **`asBitmap` 的 HDR 路径破坏动画。** 全屏预览用 `asBitmap` 解码以保留 UltraHDR gain map(见 ADR-0006),`asBitmap` 只取第一帧。

此外 **RemoteViews 根本无法播动画**:桌面 widget 在 launcher 进程渲染,只能显示烘焙位图(ADR-0006 也据此把 widget 排除在 HDR 之外)。

为了不改动这套经过大量打磨的烘焙裁切管线,只在"源是 Animated Image"时开一条独立分支:用自定义 Glide `Transformation` 逐帧套用既有的 center/scale/比例裁切,得到"每帧已裁好、仍以 `CENTER_CROP` 显示"的 GifDrawable——与烘焙位图是同一显示契约,不回到脆弱的 `imageMatrix`。非 Animated Image 源一行不改。

两个刻意的"不播":

- **widget 配置页预览保持静态**,以忠实反映那个无法播放的真实 widget——预览不应展示桌面上不会发生的动画。
- **裁切编辑器保持第一帧**,因为它是选择空间裁切的工具,与视频在该处固定取一帧的行为一致。

## 影响

- 全屏预览对 Animated Image 单独走 Drawable 路径;GIF / 动态 WebP 不带 gain map,HDR 徽标自然关闭。
- 滚动列表中多个 GIF 同时解码有性能成本。首版依赖 Glide 的屏外自动暂停(仅可见 GIF 解码),必要时再加 fling 期间暂停(M2)。
- 候选判定按扩展名 `{gif, webp}`;静态 WebP 走 Drawable 路径不出错也不损失 HDR(动图/静态 WebP 不带 gain map)。
- 未来若想让 widget 配置页预览或裁切编辑器"也动",需先认识到这分别会偏离真实 widget、以及偏离静态裁切工具的定位。
