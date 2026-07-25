# 会话记录 / 详情页动态内容播放（Detail Animated Playback）

## 2026-07-25 — 设计定稿（grill 会话）

用户提出：详情页看不到视频的派生 GIF，想加一个设置项让详情页也自动播放动态内容，且不只是开关，而是四档（关闭 / 逐一播放 / 同时播放一次 / 同时循环播放）。

经 16 轮追问定稿，产出 [decisions.md](decisions.md) 的 D1–D16。会话中被代码事实改写方向的几处：

- 摸清详情附件 `RecyclerView` **全量布局、不按视口回收**，因而 Glide 屏外暂停在此处失效——直接催生了"全部档位按视口生效"（D3），并顺带成为生成请求的限流器（D9）。
- 摸清详情附件的**长按已被拖拽排序占用**，本来推荐"不加手动播放入口"；用户裁定长按播放与拖拽共存后，改用"长按触发、松手继续播完"的手感把误拖拽风险消掉（D14/D15）。
- 用户最初提"全屏视频像 live photo 那样播派生 GIF"，核实后发现 Live Photo 在全屏播的是**真视频**，且 ADR-0012/0014 都写明全屏不该用降质 GIF——改为真视频，并由用户扩展出"长按从头看正片"的状态机（D11/D12）。

## 2026-07-25 — 文档落地与首版实现

写了 [ADR-0017](../../adr/0017-detail-animated-playback-modes.md)（会话里口头说的"ADR-0015"编号已被 timely 倒计时数字占用，0016 被 FableSol 占用，故取 0017），并在 ADR-0007 / 0012 / 0014 末尾各加「更新（2026-07-25）」段落回指；CONTEXT.md 补 **Detail Autoplay** / **Detail Static Representative Frame** 两个术语与六条不变式。

实现分四块落地，`:app:assembleDebug` 通过，已发布阿里云 debug 202607251240：

1. **设置项**：`Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC`、`FrequentSettings.getInt`、`model/DetailAutoplayMode`、设置页四选一行（`ChooserDialogFragment`，确认即落盘，不走 `saveSettings` 批量路径）、13 个语种的文案。
2. **详情播放引擎**：新增 `helpers/DetailAttachmentPlaybackController`；`ImageAttachmentAdapter` 的图片加载重构为 `bindAttachmentImage`，按调度结果在静态代表帧与逐帧播放间分流，播放态折进 loadKey 以绕开同 key 短路与 Glide 缓存复用。
3. **全屏视频**：`ImageViewerActivity` 的 `startMotionPlayback` 泛化为可接普通视频（`info == null`），新增起播位置、定时停、`holdToPlay` 三个参数；播放头、翻页防抖、播放按钮让位、音频焦点。
4. **顺带**：视频的 Thing Card Video Frame 通过新 intent extra 传给全屏，静帧与详情一致。

实现期发现并修掉的两个坑：

- `updateVideoSignalVisibility` 最初只改当前页，但停止播放常发生在翻页途中（`currentItem` 已是新页），会把刚离开那页的播放按钮永久留在隐藏态；改为遍历全部页按 `mMotionPlayingPage` 判定。
- 发布任务只提取日志文件的**第一个 `## ` 条目**，首次发布（202607251239）因日志分了两节而只上传前半，重发覆盖。日志文件顶部已加提醒。

## 2026-07-25 — 首版真机反馈的四处修正（debug 202607251257）

用户在 202607251240 上试用后提了四条，全部成立，见 [decisions.md](decisions.md) D17–D20：

1. 设置项的当前档位挤在标题右侧，长译文下更挤 → 改为标题下一行两行竖排。
2. 逐一播放切换到下一个时画面闪一下 → Glide 起新请求会把 View 置空，改用 `KeepCurrentImageTarget`。
3. **逐一播放遇到静态图片就走不完**（真 bug）→ 首版把全部可见项排进队列，静态图片永不发"播完"回调，占住队首卡死全队；改为按 `isPlayableNow` 循环筛选出队。
4. 全屏视频不支持缩放（实况照片可以）→ `iv.isZoomable = false` 是视频还只是静态封面帧时留下的，缩放跟随逻辑现成，改 true 即可。

第 3 条暴露了首版设计的一个盲点：D5 只定义了队列的**顺序**，没定义**成员资格**，默认了"可见即可播"。真实的详情网格里静态图片才是多数。

尚未针对修正版再做真机验证，验收 1–16 见 [plan.md](plan.md)。
